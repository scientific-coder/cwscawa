(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json])
  (:use [noir.core :only [defpage]]))

(defpage "/my-page" []
  "Hello!")
;;;

(def workers (ref {}))
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
(defn add-wks[current-wks new-wks]
  (do
    (println "the current in add-wks: " current-wks)
;;    current-wks
    (reduce #(merge %1
                    {(keyword (%2 :id))
                     (-> %2
                         (dissoc :id)
                         (assoc :load 0
                                :last-jobs []))})
            current-wks
            new-wks)))

;;; curl -d @/home/bernard/Code/repositories/cwscawa/test/cwscawa/new-workers.json --header "Content-Type: application/json"  http://localhost:8080/workers/add
(defpage [:post "/workers/add"] {:as params}
(do (dosync (alter workers #(add-wks % (:backbone params))))
      (println "new workers: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))
(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'cwscawa})))

