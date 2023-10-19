;; Copyright (c) George Lipov. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 2.0 (https://choosealicense.com/licenses/epl-2.0/) which can
;; be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

(ns build.simple.utils
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.tools.build.api :as b]))


(def version-tag-regex #"^v(\d+\.\d+\.\d+)(-\w+)?$")


(defn snapshot-version?
  [version]
  (string/ends-with? version "-SNAPSHOT"))


(defn step [s]
  (print (str s "... "))
  (flush))


(defn done []
  (println "DONE"))


(defn validate-version [version]
  (step (format "Validating version %s" version))
  (assert (re-matches version-tag-regex (str "v" version))
          "Invalid version format – should look like 0.0.0 or 0.0.0-qualifier.")
  (done))


(defn predeploy-checks [config]
  (step "Performing predeploy checks")
  (assert (string/blank? (b/git-process {:git-args ["status" "--porcelain"]}))
          "Git working tree is not clean.")
  (let [git-tag (b/git-process {:git-args ["describe" "--tags"]})]
    (assert (not (string/blank? git-tag))
            "Missing Git version tag.")
    (assert (re-matches version-tag-regex git-tag)
            "Invalid Git tag – should look like v0.0.0 or v0.0.0-qualifier.")
    (assert (= (str "v" (:version config)) git-tag)
            "Git tag doesn't match version in project.edn."))
  (done))


(defn read-credentials [repo-id]
  (println "\n")
  (let [console (System/console)
        user    (.readLine console (format "Username for `%s` repo: " repo-id) nil)
        ;; REVIEW: This prints the prompt twice for some reason.
        token   (apply str (.readPassword console "Deploy password or token: " nil))]
    ;; Make sure the username and password vars in ~/.m2/settings.xml are set
    ;; correctly for each repository ID, e.g:
    ;<server>
    ;  <id>clojars</id>
    ;  <username>${env.clojars_username}</username>
    ;  <password>${env.clojars_password}</password>
    ;</server>
    (println "...")
    {(str repo-id "_username") user
     (str repo-id "_password") token}))


(defn distribution-repos
  [{:keys [pom-data] :as _config}]
  (let [repos (some->> pom-data
                       (drop-while #(not= (first %) :distributionManagement))
                       first
                       rest
                       (filter #(= (first %) :repository))
                       (map rest))]
    (for [repo-entries repos]
      (into {} repo-entries))))


(defn maybe-load-config!
  [!config]
  (when (nil? @!config)
    (let [deps-config    (edn/read-string (slurp "deps.edn"))
          project-config (edn/read-string (slurp "project.edn"))
          scm-url-root   (some->> project-config
                                  :scm
                                  :url
                                  (re-matches #"^https?:/+(.+?)/*$")
                                  second)
          default-scm    (when scm-url-root
                           {:connection
                            (format "scm:git:git://%s.git" scm-url-root)
                            :developerConnection
                            (format "scm:git:ssh://git@%s.git" scm-url-root)})
          version        (:version project-config)
          lib            (:lib project-config)
          ;; NOTE: Automatically setting Clojars as the default deployment
          ;; target here – when none are set explicitly – was briefly considered
          ;; and ultimately rejected due to the risk of accidentally dumping a
          ;; closed-source project onto the internets.
          full-config    (merge {:src-dirs (:paths deps-config)}
                                project-config
                                {:clean-dirs (->> (:clean-dirs project-config)
                                                  (concat ["target"])
                                                  set)
                                 :scm        (merge default-scm
                                                    (:scm project-config)
                                                    (when-not (snapshot-version? version)
                                                      {:tag (str "v" version)}))
                                 :basis      (b/create-basis (select-keys project-config [:aliases]))
                                 :class-dir  "target/classes"
                                 :jar-file   (format "target/%s-%s.jar"
                                                     (name lib)
                                                     version)})]
      (reset! !config full-config))))
