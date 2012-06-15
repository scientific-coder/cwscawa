(ns cwscawa.server
  (:require [noir.server :as server]
            [noir.response :as response]
            [cheshire.core :as json]
            [clojure.data.priority-map :as p-m])
  (:use [noir.core :only [defpage]]))

;;; the three mutable states
;; workers is a map from worker-id to {
;; :cache-size nb of jobs that can fit in cache (int)
;; :last-jobs [ordered seq of the last :cache-size jobs] }
(def workers (ref {}))
;;  :load nb of running jobs(int)
(def loads (ref (p-m/priority-map)))
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
            (do (println id " with " prop) {id (merge prop { :last-jobs []})}))]
;; TODO think
    (reduce #(merge %1 (empty-wk %2)) current-wks new-wks)))

;; curl -X PUT -d @/home/bernard/Code/repositories/cwscawa/test/cwscawa/new-workers.json --header "Content-Type: application/json"  http://localhost:8080/workers
(defpage [:put "/workers"] {:as params}
  (let [ids (:backbone params)]
    (do (dosync (alter workers #(add-wks % ids))
                (alter loads #(reduce conj %  (map vector (map first ids) (repeat 0)))))
        (println "backbone:" (:backbone params) "new workers: " ids "all workers: " @workers "all loads:" @loads)
        (response/json @workers ))))

;; curl -X DELETE -d "[\"id3\"]" --header "Content-Type: application/json"  http://localhost:8080/workers
(defpage [:delete "/workers"] {:as params}
  (do  (dosync
        (let [ids (map keyword  (:backbone params))]
          (doseq [pm [workers loads]]; not removed from caches
            (alter pm #(reduce dissoc % ids)))))
      (println "workers to remove: " (:backbone params) "all workers: " @workers)
      (response/json @workers )))

(defn dec-load [wks id]
  "reduce  the load of id in"
  (if-let [wk (wks id)]
    (assoc wks id (assoc wk :load (dec (wk :load))))
    wks))
;; could indicate the job completed
;; we assume that jobs leave cache of a node in fifo order of
;; submittion while it should be in order of ack !
(defpage [:post "/ack"] {:as params}
  (do  (dosync (alter loads #(reduce
                                (fn [pm id]
                                  (do (println "id:" id " pm " pm) (assoc pm id (dec (pm id )))))
                                % (map keyword (:backbone params)))))
      (println "dec load of worker : " (:backbone params) "all loads: " @loads)
      (response/json @loads )))

(defn find-worker [j-id]
  "find candidate worker for job j-id"
  (let [in-cache (select-keys @workers (@cached-jobs j-id))]
    (if (empty? in-cache)
      (first  (first (seq @loads)))
      ;; TODO check if @ is a slow path
      (reduce #(if (< (@loads %1) (@loads %2)) %1 %2)
              (keys in-cache)))))

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
       (alter workers #(assoc % w-id (assoc wk :last-jobs current-jobs)))
       (alter loads #(assoc % w-id (inc (% w-id))))
       (alter cached-jobs #(merge-with into
                                         (reduce rm-cached % evicted-jobs)
                                         {j-id #{w-id}}))
         (println "now workers" @workers
                  "now cached-jobs" @cached-jobs)))))

(defpage [:get "/workers"] []
  (response/json @workers ))

(defpage [:get "/jobs"] []
  (response/json @cached-jobs ))

(defpage [:get "/loads"] []
  (response/json @loads ))


;;; curl -X POST -d "[\"j1\",\"j2\"]" --header "Content-Type: application/json"  http://localhost:8080/jobs
(defpage [:post "/jobs"] {:as params}
  (do  (doseq  [j-id (:backbone params)] (assign (keyword j-id)))
       (println "jobs to assign: " (:backbone params) "all cached jobs: " @cached-jobs
                "loads:" @loads)
       (response/json @cached-jobs )))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'cwscawa})))

