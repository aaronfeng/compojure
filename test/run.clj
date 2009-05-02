(ns test.run
  (:use clojure.contrib.test-is)
  (:require test.compojure.dbm.memory)
  (:require test.compojure.html.gen)
  (:require test.compojure.html.form-helpers)
  (:require test.compojure.http.routes)
  (:require test.compojure.http.request)
  (:require test.compojure.http.response)
  (:require test.compojure.http.session)
  (:require test.compojure.http.helpers)
  (:require test.compojure.validation))

(run-tests
  'test.compojure.dbm.memory
  'test.compojure.html.gen
  'test.compojure.html.form-helpers
  'test.compojure.http.routes
  'test.compojure.http.request
  'test.compojure.http.response
  'test.compojure.http.session
  'test.compojure.http.helpers
  'test.compojure.validation)
