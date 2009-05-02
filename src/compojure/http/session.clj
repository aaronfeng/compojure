;; Copyright (c) James Reeves. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;; can be found in the file epl-v10.html at the root of this distribution. By
;; using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

;; compojure.http.session:
;;
;; Functions for creating and updating HTTP sessions.

(ns compojure.http.session
  (:use compojure.str-utils)
  (:use compojure.http.helpers)
  (:use compojure.http.request)
  (:use compojure.http.response)
  (:use compojure.encodings)
  (:use compojure.crypto)
  (:use compojure.dbm)
  (:use compojure.repository)
  (:use clojure.contrib.except))

;; Override these mulitmethods to create your own session storage.
;; Uses the Compojure repository pattern.

(defmulti create-session
  "Create a new session map. Should not attempt to save the session."
  (fn [rep] (use-repository rep)))
  
(defmulti read-session
  "Read in the session using the supplied data. Usually the data is a key used
  to find the session in a store."
  (fn [rep data] (use-repository rep)))
                    
(defmulti write-session
  "Write a new or existing session to the session store."
  (fn [rep session] (use-repository rep)))

(defmulti destroy-session
  "Remove the session from the session store."
  (fn [rep session] (use-repository rep)))

(defmulti session-cookie
  "Return the session data to be stored in the cookie. This is usually the
  session ID."
  (fn [rep new? session] (use-repository rep)))

;; Default implementation uses compojure.dbm

(defmethod create-session :default
  [repository]
  {:id (gen-uuid)})

(defmethod session-cookie :default
  [repository new? session]
  (if new?
    (session :id)))

(defmethod read-session :default
  [repository id]
  (with-db repository
    (fetch id)))

(defmethod write-session :default
  [repository session]
  (with-db repository
    (store (session :id) session)))

(defmethod destroy-session :default
  [repository session]
  (with-db repository
    (delete (session :id))))

;; Cookie sessions

(def *default-secret-key* (gen-uuid))   ; Random secret key

(defn session-hmac
  "Calculate a HMAC for a marshalled session"
  [repository cookie-data]
  (let [secret-key (:secret-key repository *default-secret-key*)]
    (hmac secret-key "HmacSHA256" cookie-data)))

(defmethod create-session ::cookie [repository] {})

(defmethod session-cookie ::cookie
  [repository new? session]
  (let [cookie-data (marshal session)]
    (if (> (count cookie-data) 4000)
      (throwf "Session data exceeds 4K")
      (str cookie-data "--" (session-hmac repository cookie-data)))))

(defmethod read-session ::cookie
  [repository data]
  (let [[session mac] (.split data "--")]
    (if (= mac (session-hmac session))
      (unmarshal session))))

; Do nothing for write or destroy
(defmethod write-session ::cookie [repository session])
(defmethod destroy-session ::cookie [repository session])

;; Session middleware

(defn- get-request-session
  "Retrieve the session using the 'session' cookie in the request."
  [request repository]
  (if-let [session-data (-> request :cookies :compojure-session)]
    (read-session repository session-data)))

(defn- assoc-request-session
  "Associate the session with the request."
  [request repository]
  (if-let [session (get-request-session request repository)]
    (assoc request
      :session session)
    (assoc request
      :session      (create-session repository)
      :new-session? true)))

(defn- assoc-request-flash
  "Associate the session flash with the request and remove it from the
  session."
  [request]
  (let [session (:session request)]
    (-> request 
      (assoc :flash   (session :flash {}))
      (assoc :session (dissoc session :flash)))))

(defn- set-session-cookie
  "Set the session cookie on the response if required."
  [repository request response session]
  (let [new?    (:new-session? request)
        cookie  (session-cookie repository new? session)
        update  (set-cookie :compojure-session cookie, :path "/")]
    (if cookie
      (update-response request response update)
      response)))

(defn- save-handler-session
  "Save the session for a handler if required."
  [repository request response session]
  (when (or (:session response)
            (:new-session? request)
            (not-empty (:flash request)))
    (write-session repository session)))

(defn with-session
  "Wrap a handler in a session of the specified type. Session type defaults to
  :memory if not supplied."
  ([handler]
    (with-session handler ::cookie))
  ([handler repository]
    (fn [request]
      (let [request  (-> request (assoc-cookies)
                                 (assoc-request-session repository)
                                 (assoc-request-flash))
            response (handler request)
            session  (or (:session response) (:session request))]
          (when response
            (save-handler-session repository request response session)
            (set-session-cookie   repository request response session))))))

;; User functions for modifying the session

(defn set-session
  "Return a response map with the session set."
  [session]
  {:session session})

(defn alter-session
  "Use a function to alter the session."
  [func & args]
  (fn [request]
    (set-session
      (apply func (request :session) args))))

(defn session-assoc
  "Associate key value pairs with the session."
  [& keyvals]
  (apply alter-session assoc keyvals))

(defn session-dissoc
  "Dissociate keys from the session."
  [& keys]
  (apply alter-session dissoc keys))

(defn flash-assoc
  "Associate key value pairs with the session flash."
  [& keyvals]
  (alter-session merge {:flash (apply hash-map keyvals)}))
