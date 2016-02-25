(ns metabase.api.slack
  "/api/slack endpoints"
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [compojure.core :refer [GET PUT DELETE POST]]
            [metabase.api.common :refer :all]
            [metabase.config :as config]
            [metabase.integrations [slack :as slack]]
            [metabase.models.setting :as setting]))

(defendpoint PUT "/settings"
  "Update multiple `Settings` values.  You must be a superuser to do this."
  [:as {settings :body}]
  {settings [Required Dict]}
  (check-superuser)
  (let [slack-token (:slack-token settings)
        response    (try (if-not config/is-test?
                           ;; in normal conditions, validate connection
                           (slack/GET :channels.list, :exclude_archived 1, :token slack-token)
                           ;; for unit testing just respond with a success message
                           {:ok true})
                         (catch clojure.lang.ExceptionInfo info
                           (ex-info info)))]
    (if (:ok response)
      ;; test was good, save our settings
      (setting/set :slack-token slack-token)
      ;; test failed, return response message
      {:status 500
       :body   response})))

(define-routes)
