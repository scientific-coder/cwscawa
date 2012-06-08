(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json])
  (:use [noir.core :only [defpage]]))

;;;

(def workers (ref {}))
(def cached-jobs (ref {}))

(defn add-body [handler]
  (fn [req]
    ;; "text/text"
    (do;; (println req)
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
  (let [wk (wks id)
        _ (println "dec-load " wk)]
    (if wk 
      (assoc wks id (assoc wk :load (dec (wk :load))))
      wks)))
;; could indicate the job completed
;; we assume that jobs leave cache of a node in fifo order of
;; submittion while it should be in order of ack !
(defpage [:post "/workers/ack"] {:as params}
  (do  (dosync (alter workers #(reduce dec-load % (map keyword  (:backbone params)))))
      (println "dec load of worker : " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn find-worker [j-id]
  (let [_ (println (@cached-jobs j-id) "and " (comment (select-keys @workers (@cached-jobs j-id))))
        in-cache (select-keys @workers (@cached-jobs j-id))
        wks  (if (empty? in-cache) @workers in-cache)
        _ (println "WKS: " wks)
        get-load #((wks % ) :load)
        min-load #(if (< (get-load %1) (get-load %2)) %1 %2)]
    (do (println "FIND" (keys wks) @workers)
      (reduce min-load (keys wks)))))

;; cannot only do a
;; (merge-with (fn [s k](remove #(= % k) s)) @cached-jobs {j-id w-id})
;; as we want to garbage collect j-id that have an empty set of w-ids
(defn assign [j-id]
  (do (println "ASSIGN")  (dosync
        (let [w-id (find-worker j-id)
              wk (@workers w-id)
              [current-jobs evicted-jobs] (split-at (wk :cache-size) (into [j-id]
                                                                           (filter #(not= % j-id)
                                                                                   (wk :last-jobs))))
              rm-cached (fn [current-cache jid]
                          (let [updated-wks (remove #(= % w-id) (current-cache jid))]
                            (if (empty? updated-wks)
                              (dissoc current-cache jid)
                              (assoc current-cache jid updated-wks))))]
          (do (println w-id)
              (alter workers #(assoc % w-id (assoc wk :load (inc (wk :load)) :last-jobs current-jobs)))
              (alter cached-jobs #(merge-with into
                                              (reduce rm-cached % evicted-jobs)
                                              {j-id #{w-id}}))
              (println "now workers" @workers)
              (println "now cached-jobs" @cached-jobs))))))

(defpage "/workers/status" []
  (response/json @workers ))

(defpage "/jobs/status" []
  (response/json @cached-jobs ))

(defpage [:post "/jobs/submit"] {:as params}
  (do  (doseq  [j-id (:backbone params)] (assign (keyword j-id)))
       (println "jobs to assign: " (:backbone params) "all cached jobs: " @cached-jobs)
       (response/json @cached-jobs )))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'cwscawa})))

