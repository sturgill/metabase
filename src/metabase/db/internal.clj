(ns metabase.db.internal
  "Internal functions and macros used by the public-facing functions in `metabase.db`."
  (:require [clojure.string :as s]
            [clojure.tools.logging :as log]
            [korma.core :as k]
            [metabase.config :as config]
            [metabase.models.interface :as models]
            [metabase.util :as u]))

(declare entity->korma)

(defn- pull-kwargs
  "Where FORMS is a sequence like `[:id 1 (limit 3)]`, return a map of kwarg pairs and sequence of remaining forms.

     (pull-kwargs [:id 1 (limit 3) (order :id :ASC]) -> [{:id 1} [(limit 3) (order :id ASC)]"
  ([forms]
   (pull-kwargs {} [] forms))
  ([kwargs-acc forms-acc [arg1 & rest-args]]
   (if-not arg1 [kwargs-acc forms-acc]
           (if (keyword? arg1)
             (recur (assoc kwargs-acc arg1 (first rest-args)) forms-acc (rest rest-args))
             (recur kwargs-acc (conj forms-acc arg1) rest-args)))))

(defn sel-apply-kwargs
  "Pull kwargs from forms and add korma `where` form if applicable."
  [forms]
  (let [[kwargs-map forms] (pull-kwargs forms)]
    (if-not (empty? kwargs-map) (conj forms `(k/where ~kwargs-map))
            forms)))

(defn destructure-entity
  "Take an ENTITY of the form `entity` or `[entity & field-keys]` and return a pair like `[entity field-keys]`."
  [entity]
  (if-not (vector? entity) [entity nil]
          [(first entity) (vec (rest entity))]))

(def ^{:arglists '([entity])} entity->korma
  "Convert an ENTITY argument to `sel` into the form we should pass to korma `select` and to various multi-methods such as
   `post-select`.

    *  If entity is a vector like `[User :name]`, only keeps the first arg (`User`)
    *  Converts fully-qualified entity name strings like `\"metabase.models.user/User\"` to the corresponding entity
       and requires their namespace if needed.
    *  Symbols like `'metabase.models.user/User` are handled the same way as strings.
    *  Infers the namespace of unqualified symbols like `'CardFavorite`"
  (memoize
   (fn -entity->korma [entity]
     {:post [:metabase.models.interface/entity]}
     (cond (vector? entity) (-entity->korma (first entity))
           (string? entity) (-entity->korma (symbol entity))
           (symbol? entity) (let [[_ ns symb] (re-matches #"^(?:([^/]+)/)?([^/]+)$" (str entity))
                                  _    (assert symb)
                                  ns   (symbol (or ns
                                                   (str "metabase.models." (-> symb
                                                                               (s/replace #"([a-z])([A-Z])" "$1-$2") ; convert something like CardFavorite
                                                                               s/lower-case)))) ; to ns like metabase.models.card_favorite
                                  symb (symbol symb)]
                              (require ns)
                              @(ns-resolve ns symb))
           :else entity))))


;;; ## ---------------------------------------- SEL 2.0 FUNCTIONS ----------------------------------------

;;; Low-level sel implementation

(defmacro sel-fn
  "Part of the internal implementation for `sel`, don't call this directly!"
  [& forms]
  (let [forms (sel-apply-kwargs forms)
        entity (gensym "ENTITY")]
    (loop [query `(k/select* ~entity), [[f & args] & more] forms]
      (cond
        f          (recur `(~f ~query ~@args) more)
        (seq more) (recur query more)
        :else      `[(fn [~entity]
                       ~query) ~(str query)]))))

(defn sel-exec
  "Part of the internal implementation for `sel`, don't call this directly!
   Execute the korma form generated by the `sel` macro and process the results."
  [entity [select-fn log-str]]
  (let [[entity field-keys] (destructure-entity entity)
        entity              (entity->korma entity)
        entity+fields       (assoc entity :fields (or field-keys
                                                      (models/default-fields entity)))]
    ;; Log if applicable
    (future
      (when (config/config-bool :mb-db-logging)
        (when-not @(resolve 'metabase.db/*sel-disable-logging*)
          (log/debug "DB CALL:" (:name entity)
                     (or (:fields entity+fields) "*")
                     (s/replace log-str #"korma.core/" "")))))

    (for [obj (k/exec (select-fn entity+fields))]
      (models/do-post-select entity obj))))

(defmacro sel*
  "Part of the internal implementation for `sel`, don't call this directly!"
  [entity & forms]
  `(sel-exec ~entity (sel-fn ~@forms)))

;;; :field

(defmacro sel:field
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field ...)` forms."
  [[entity field] & forms]
  `(let [field# ~field]
     (map field# (sel* [~entity field#] ~@forms))))

;;; :id

(defmacro sel:id
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :id ...)` forms."
  [entity & forms]
  `(sel:field [~entity :id] ~@forms))

;;; :fields

(defn sel:fields*
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :fields ...)` forms."
  [fields results]
  (for [result results]
    (select-keys result fields)))

(defmacro sel:fields
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :fields ...)` forms."
  [[entity & fields] & forms]
  `(let [fields# ~(vec fields)]
     (sel:fields* (set fields#) (sel* `[~~entity ~@fields#] ~@forms))))

;;; :id->fields

(defn sel:id->fields*
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :id->fields ...)` forms."
  [fields results]
  (->> results
       (map (u/rpartial select-keys fields))
       (zipmap (map :id results))))

(defmacro sel:id->fields
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :id->fields ...)` forms."
  [[entity & fields] & forms]
  `(let [fields# ~(conj (set fields) :id)]
     (sel:id->fields* fields# (sel* `[~~entity ~@fields#] ~@forms))))

;;; :field->field

(defn sel:field->field*
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->field ...)` forms."
  [f1 f2 results]
  (into {} (for [result results]
             {(f1 result) (f2 result)})))

(defmacro sel:field->field
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->field ...)` forms."
  [[entity f1 f2] & forms]
  `(let [f1# ~f1
         f2# ~f2]
     (sel:field->field* f1# f2# (sel* [~entity f1# f2#] ~@forms))))

;;; :field->fields

(defn sel:field->fields*
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->fields ...)` forms."
  [key-field other-fields results]
  (into {} (for [result results]
             {(key-field result) (select-keys result other-fields)})))

(defmacro sel:field->fields
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->fields ...)` forms."
  [[entity key-field & other-fields] & forms]
  `(let [key-field# ~key-field
         other-fields# ~(vec other-fields)]
     (sel:field->fields* key-field# other-fields# (sel* `[~~entity ~key-field# ~@other-fields#] ~@forms))))

;;; : id->field

(defmacro sel:id->field
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :id->field ...)` forms."
  [[entity field] & forms]
  `(sel:field->field [~entity :id ~field] ~@forms))

;;; :field->id

(defmacro sel:field->id
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->id ...)` forms."
  [[entity field] & forms]
  `(sel:field->field [~entity ~field :id] ~@forms))

;;; :field->obj

(defn sel:field->obj*
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->obj ...)` forms."
  [field results]
  (into {} (for [result results]
             {(field result) result})))

(defmacro sel:field->obj
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel ... :field->obj ...)` forms."
  [[entity field] & forms]
  `(sel:field->obj* ~field (sel* ~entity ~@forms)))

;;; :one & :many

(defmacro sel:one
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel :one ...)` forms."
  [& args]
  `(first (metabase.db/sel ~@args (k/limit 1))))

(defmacro sel:many
  "Part of the internal implementation for `sel`, don't call this directly!
   Handle `(sel :many ...)` forms."
  [& args]
  `(metabase.db/sel ~@args))
