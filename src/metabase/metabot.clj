(ns metabase.metabot
  (:refer-clojure :exclude [eval list])
  (:require [clojure [edn :as edn]]
            [aleph.http :as aleph]
            [cheshire.core :as json]
            [korma.core :as k]
            (manifold [bus :as bus]
                      [deferred :as d]
                      [stream :as s])
            [metabase.api.common :refer [let-404]]
            [metabase.db :refer [sel]]
            [metabase.integrations.slack :as slack]
            [metabase.task.send-pulses :as pulses]
            [metabase.util :as u]))

;;; # ------------------------------------------------------------ Metabot Command Handlers ------------------------------------------------------------

(def ^:private ^:dynamic *channel-id* nil)

(defn- keys-description
  ([message m]
   (str message " " (keys-description m)))
  ([m]
   (apply str (interpose ", " (for [k (sort (keys m))]
                                (str \` (name k) \`))))))

(defn- dispatch-fn [verb tag]
  (let [fn-map (into {} (for [[symb varr] (ns-interns *ns*)
                              :let        [dispatch-token (get (meta varr) tag)]
                              :when       dispatch-token]
                          {(if (true? dispatch-token)
                             (keyword symb)
                             dispatch-token) varr}))]
    (println (u/format-color 'cyan verb) fn-map)
    (fn dispatch*
      ([]
       (keys-description (format "Here's what I can %s:" verb) fn-map))
      ([what & args]
       (if-let [f (fn-map (keyword what))]
         (apply f args)
         (format "I don't know how to %s `%s`.\n%s" verb (name what) (dispatch*)))))))

(defn- list:cards {:list :cards} []
  (sel :many :fields ['Card :id :name] (k/limit 10) (k/order :name)))

(def ^:private ^:metabot list
  (dispatch-fn "list" :list))



(defn- show:card {:show :card}
  ([]
   "Show which card? Give me a Card ID and I can show it to you. If you don't know the ID of your Card, try `metabot list cards`.")
  ([card-id]
   (let-404 [card-name (sel :one :field ['Card :name], :id card-id)]
     (pulses/send-pulse! {:name     card-name
                          :cards    [{:id card-id}]
                          :channels [{:channel_type   "slack"
                                      :recipients     []
                                      :details        {:channel *channel-id*}
                                      :schedule_type  "hourly"
                                      :schedule_day   "mon"
                                      :schedule_hour  8
                                      :schedule_frame "first"}]}))
   nil))

(def ^:private ^:metabot show
  (dispatch-fn "show" :show))

(declare apply-metabot-fn)

(defn- ^:metabot help []
  (apply-metabot-fn))

(def ^:private ^:const kanye-quotes
  ["Sometimes people write novels and they just be so wordy and so self-absorbed."
   "Michael Jordan changed so much in basketball, he took his power to make a difference. It's so much going on in music right now and somebody has to make a difference."
   "I know I got angels watchin me from the other side."
   "I don't even listen to rap. My apartment is too nice to listen to rap in."
   "Nothing in life is promised except death."
   "I will go down as the voice of this generation, of this decade, I will be the loudest voice."
   "Damn Yeezy, they all gotta be dimes? Well, Adam gave up a rib so mine better be prime."
   "I feel like I'm too busy writing history to read it."
   "I can always tell if a band has a British rhythm section due to the gritty production."
   "I am not a fan of books."
   "It was a strike against me that I didn't wear baggy jeans and jerseys and that I never hustled, never sold drugs."])

(defn- ^:metabot kanye []
  (str ":kanye:\n> " (rand-nth kanye-quotes)))


;;; # ------------------------------------------------------------ Metabot Command Dispatch ------------------------------------------------------------

(def ^:private apply-metabot-fn
  (dispatch-fn "understand" :metabot))

(defn- eval-command-str [s]
  (when (seq s)
    (when-let [tokens (seq (edn/read-string (str \( s \))))]
      (apply apply-metabot-fn tokens))))


;;; # ------------------------------------------------------------ Metabot Input Handling ------------------------------------------------------------

(defn- message->command-str [{:keys [text]}]
  (u/prog1 (when (seq text)
             (second (re-matches #"^metabot\s*(.*)$" text)))
    (println (u/format-color 'yellow <>))))

(defn- respond-to-message! [message response]
  (when response
    (let [response (if (coll? response) (str "```\n" (u/pprint-to-str response) "```")
                       (str response))]
      (when (seq response)
        (println (u/format-color 'green response))
        (slack/post-chat-message! (:channel message) response)))))

(defn- handle-slack-message [message]
  (respond-to-message! message (try
                                 (binding [*channel-id* (:channel message)]
                                   (eval-command-str (message->command-str message)))
                                 (catch Throwable e
                                   (println (u/pprint-to-str 'red (u/filtered-stacktrace e)))
                                   (.getMessage e)))))

(defn- human-message? [event]
  (and (= (:type event) "message")
       (not= (:subtype event) "bot_message")))

(defn- event-timestamp-ms [{:keys [ts], :or {ts "0"}}]
  (* (Double/parseDouble ts) 1000))


(defonce ^:private websocket (atom nil))

(defn- handle-slack-event [socket start-time event]
  (when-not (= socket @websocket)
    (println "Go home websocket, you're drunk.")
    (s/close! socket)
    (throw (Exception.)))

  (when-let [event (json/parse-string event keyword)]
    (when (and (human-message? event)
               (> (event-timestamp-ms event) start-time))
      (println (u/pprint-to-str 'cyan event))
      (handle-slack-message event))))


;;; # ------------------------------------------------------------ Websocket Connection Stuff ------------------------------------------------------------

(defn- connect-websocket! []
  (when-let [websocket-url (slack/websocket-url)]
    (let [socket @(aleph/websocket-client websocket-url)]
      (println "Connected to websocket:" socket)
      (reset! websocket socket)
      (d/catch (s/consume (partial handle-slack-event socket (System/currentTimeMillis))
                          socket)
          (partial println "ERROR -> ")))))

;;; Websocket monitor

(defonce ^:private websocket-monitor-id (atom nil))

(defn- start-websocket-monitor! []
  (let [id (java.util.UUID/randomUUID)]
    (reset! websocket-monitor-id id)
    (future (loop []
              (Thread/sleep 500)
              (metabase.db/setup-db-if-needed)
              (when (= id @websocket-monitor-id)
                (when (or (not @websocket)
                          (s/closed? @websocket))
                  (connect-websocket!))
                (recur))))))

(start-websocket-monitor!)
