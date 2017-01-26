;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns leiningen.lux.utils
  (:require (clojure [template :refer [do-template]])
            [leiningen.core.classpath :as classpath])
  (:import (java.io File
                    InputStreamReader
                    BufferedReader)))

(def ^:const ^String default-output-dir (str "target" java.io.File/separator "jvm"))
(def ^:const ^String output-package "program.jar")

(def ^:private unit-separator (str (char 31)))

(def ^:private vm-options
  ""
  ;; "-server -Xms2048m -Xmx2048m -XX:+OptimizeStringConcat"
  )

(defn ^:private prepare-path [path]
  (let [path (if (and (.startsWith path "/")
                      (= "\\" java.io.File/separator))
               (.substring path 1)
               path)
        path (.replace path "/" java.io.File/separator)]
    path))

(def ^:private stdlib-id ["com.github.luxlang" "stdlib"])

(defn ^:private all-jars-in-classloader []
  (->> ^java.net.URLClassLoader (ClassLoader/getSystemClassLoader)
       (.getURLs)
       (map #(.getFile ^java.net.URL %))
       (filter #(.endsWith ^String % ".jar"))))

(do-template [<name> <signal>]
  (defn <name> [jar-paths]
    {:post [(not (nil? %))]}
    (some (fn [^:private path]
            (if (.contains path <signal>)
              path
              nil))
          jar-paths))

  ^:private find-compiler-path "com/github/luxlang/luxc-jvm"
  ^:private find-stdlib-path   "com/github/luxlang/stdlib"
  )

(defn ^:private filter-deps [jar-paths]
  (filter (fn [^:private path]
            (or (.contains path "org/ow2/asm/asm-all")
                (.contains path "org/clojure/core.match")
                (.contains path "org/clojure/clojure")))
          jar-paths))

(defn ^:private java-command [project]
  (str (get project :java-cmd "java")
       " " (->> (get project :jvm-opts) (interpose " ") (reduce str ""))
       " " vm-options))

(defn ^:private lux-command [project mode source-paths]
  (str "lux " mode
       " " (->> (get project :resource-paths (list)) (interpose unit-separator) (apply str))
       " " (->> source-paths (interpose unit-separator) (apply str))
       " " (get-in project [:lux :target] default-output-dir)))

(do-template [<name> <mode>]
  (defn <name> [project module source-paths]
    (let [is-stdlib? (= stdlib-id [(get project :group) (get project :name)])
          jar-paths (all-jars-in-classloader)
          compiler-path (prepare-path (find-compiler-path jar-paths))
          stdlib-path (prepare-path (find-stdlib-path jar-paths))
          sdk-path (get-in project [:lux :android :sdk])
          android-path (str sdk-path java.io.File/separator "platforms" java.io.File/separator "android-" (get-in project [:lux :android :version]) java.io.File/separator "android.jar")
          deps-paths (let [deps (map prepare-path
                                     (filter-deps jar-paths))
                           with-android (if (.exists (new File android-path))
                                          (cons android-path deps)
                                          deps)
                           with-stdlib (if is-stdlib?
                                         with-android
                                         (list* stdlib-path with-android))]
                       with-stdlib)
          class-path (->> (classpath/get-classpath project)
                          (filter #(.endsWith % ".jar"))
                          (concat deps-paths)
                          (list* compiler-path)
                          (interpose java.io.File/pathSeparator)
                          (reduce str ""))
          class-path (.replace class-path "/" java.io.File/separator)]
      (str (java-command project) " -cp " class-path
           " " (lux-command project <mode> source-paths))))

  compile-path (str "release " module)
  repl-path    "repl"
  )

(defn run-process [command working-directory pre post]
  (let [process (.exec (Runtime/getRuntime) command nil working-directory)]
    (with-open [std-out (->> process .getInputStream (new InputStreamReader) (new BufferedReader))
                std-err (->> process .getErrorStream (new InputStreamReader) (new BufferedReader))]
      (println pre)
      (loop [line (.readLine std-out)]
        (when line
          (println line)
          (recur (.readLine std-out))))
      (let [first-error-line (.readLine std-err)]
        (do (loop [line first-error-line]
              (when line
                (println line)
                (recur (.readLine std-err))))
          (println post)
          (nil? first-error-line))))))
