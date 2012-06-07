(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json])
  (:use [noir.core :only [defpage]]))

(defpage "/my-page" []
  "Hello!")
;;;

(defn add-body [handler]

  (fn [req]
    ;; "text/text"
    (do (println req)
      (let [neue (if (= "application/json"  (get-in req [:headers "content-type"]))

                   (update-in req [:params] assoc :backbone (json/parse-string (slurp (:body req)) true))

                   req)]

        (handler neue)))))
(server/add-middleware add-body)

;;; added this to my view file

(defpage [:any "/foo"] {:as params}
  (do (println params ) (response/json (assoc  (:backbone params) "firstName" "D00M") )))

(defpage [:post "/workers/add"] {:as params}
  (do (println (str  params "in post /workers/add"))
      (response/json  [ "posting:" params " to /workers/add"])))
(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'cwscawa})))

