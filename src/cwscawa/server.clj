(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json])
  (:use [noir.core :only [defpage]]))

(defpage "/my-page" []
  "Hello!")
;;;

(def workers (ref {}))
(def cached-jobs (ref {}))

(defn add-body [handler]
  (fn [req]
    ;; "text/text"
    (do (println req)
      (let [neue (if (= "application/json"  (get-in req [:headers "content-type"]))

                   (update-in req [:params] assoc :backbone (json/parse-string (slurp (:body req)) true))

                   req)]

        (handler neue)))))

(server/add-middleware add-body)

(defn add-wks[current-wks new-wks]
  (letfn [(empty-wk [[id prop]]
            {id (merge prop {:load 0 :last-jobs []})})]
    (reduce #(merge %1 (empty-wk %2)) current-wks new-wks)))

;;; curl -d @/home/bernard/Code/repositories/cwscawa/test/cwscawa/new-workers.json --header "Content-Type: application/json"  http://localhost:8080/workers/add
(defpage [:post "/workers/add"] {:as params}
  (do (dosync (alter workers #(add-wks % (:backbone params))))
      (println "new workers: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defpage [:post "/workers/remove"] {:as params}
  (do  (dosync (alter workers #(reduce dissoc % (map keyword  (:backbone params)))))
      (println "workers to remove: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn dec-load [wks id]
  (let [wk (wks id)]
    (if wk 
      (assoc wks id (assoc wk :load (dec (wk :load)))))
    wks))
(defpage [:post "/workers/ack"] {:as params}
  (do  (dosync (alter workers #(reduce dec-load % (map keyword  (:backbone params)))))
      (println "workers to remove: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'cwscawa})))

