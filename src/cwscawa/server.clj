(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json])
  (:use [noir.core :only [defpage]]))

;;; the two mutable states
;; workers is a map from worker-id to { :load nb of running jobs(int)
;; :cache-size nb of jobs that can fit in cache (int)
;; :last-jobs [ordered seq of the last :cache-size jobs] }
(def workers (ref {}))
;; cached-jobs is a map from job-id to #{ worker-ids }
;; when a job is evicted from the cache of a worker, we remove the
;; worker from the set in this map and when the set is empty we remove
;; the entry
(def cached-jobs (ref {}))


(defn add-body [handler]
  "handler to get the data from the body of a POST request (c) Chris Granger
https://groups.google.com/forum/#!msg/clj-noir/INqvBo6oXIA/G2hfpUYIpjcJ"
  (fn [req]
    ;; "text/text"
    (do;; (println req)
      (let [neue (if (= "application/json"  (get-in req [:headers "content-type"]))

                   (update-in req [:params] assoc :backbone (json/parse-string (slurp (:body req)) true))

                   req)]

        (handler neue)))))

(server/add-middleware add-body)

(defn add-wks[current-wks new-wks]
  "add workers (received from json) to the state, adding the :load and :last-jobs entries"
  (letfn [(empty-wk [[id prop]]
            {id (merge prop {:load 0 :last-jobs []})})]
    (reduce #(merge %1 (empty-wk %2)) current-wks new-wks)))

;; curl -d @/home/bernard/Code/repositories/cwscawa/test/cwscawa/new-workers.json --header "Content-Type: application/json"  http://localhost:8080/workers/add
(defpage [:post "/workers/add"] {:as params}
  (do (dosync (alter workers #(add-wks % (:backbone params))))
      (println "new workers: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

;; curl -d "[\"id3\"]" --header "Content-Type: application/json"  http://localhost:8080/workers/remove
(defpage [:post "/workers/remove"] {:as params}
  (do  (dosync (alter workers #(reduce dissoc % (map keyword  (:backbone params)))))
      (println "workers to remove: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn dec-load [wks id]
  "if a worker with id is in wks, dec its :load"
  (if-let [wk (wks id)]
    (assoc wks id (assoc wk :load (dec (wk :load))))
    wks))
;; could indicate the job completed
;; we assume that jobs leave cache of a node in fifo order of
;; submittion while it should be in order of ack !
(defpage [:post "/workers/ack"] {:as params}
  (do  (dosync (alter workers #(reduce dec-load % (map keyword  (:backbone params)))))
      (println "dec load of worker : " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn find-worker [j-id]
  "find candidate worker for job j-id"
  (let [in-cache (select-keys @workers (@cached-jobs j-id))
        wks  (if (empty? in-cache) @workers in-cache)
        get-load #((wks % ) :load)
        min-load #(if (< (get-load %1) (get-load %2)) %1 %2)]
    (reduce min-load (keys wks))))

;; cannot only do a
;; (merge-with (fn [s k](remove #(= % k) s)) @cached-jobs {j-id w-id})
;; as we want to garbage collect j-id that have an empty set of w-ids
(defn assign [j-id]
  (dosync
   (let [w-id (find-worker j-id)
         wk (@workers w-id)
         [current-jobs evicted-jobs] (split-at (wk :cache-size)
                                               (into [j-id]
                                                     (filter #(not= % j-id)
                                                             (wk :last-jobs))))
         rm-cached (fn [current-cache jid]
                     (let [updated-wks (remove #(= % w-id) (current-cache jid))]
                       (if (empty? updated-wks)
                         (dissoc current-cache jid)
                         (assoc current-cache jid updated-wks))))]
     (do
       (alter workers #(assoc % w-id (assoc wk :load (inc (wk :load)) :last-jobs current-jobs)))
       (alter cached-jobs #(merge-with into
                                         (reduce rm-cached % evicted-jobs)
                                         {j-id #{w-id}}))
         (println "now workers" @workers
                  "now cached-jobs" @cached-jobs)))))

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

