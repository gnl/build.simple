;; Copyright (c) George Lipov. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 2.0 (https://choosealicense.com/licenses/epl-2.0/) which can
;; be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

(ns ^:no-doc build.simple.core
  "
  1. Update project.edn
  2. `clj -T:build install`
  3. `git commit --all --gpg-sign`
  4. `git tag --sign <v0.1.0>`
  5. `clj -T:build deploy` (optional: `:sign false`, `:check false`)
  "
  (:require
   [clojure.tools.build.api :as b]
   [build.simple.utils :refer :all]))


(defonce !config (atom nil))


(defn clean [{:keys [dir]}]
  (maybe-load-config! !config)
  (step "Cleaning build")
  (let [clean-dirs (if dir
                     (let [dir (str dir)]
                       (assert (contains? (:clean-dirs @!config) dir)
                               "Dir not found in project.edn -> `:clean-dirs`.")
                       [dir])
                     (:clean-dirs @!config))]
    (doseq [path clean-dirs]
      (step (format "\nRemoving `./%s`" path))
      (b/delete {:path path})))
  (done))


(defn pom [_]
  (maybe-load-config! !config)
  (validate-version (:version @!config))
  (step "Generating pom.xml")
  (b/write-pom (select-keys @!config
                            [:basis
                             :class-dir
                             :lib
                             :version
                             :scm
                             :src-dirs
                             :resource-dirs
                             :repos
                             :pom-data]))
  (done))


(defn jar [_]
  (maybe-load-config! !config)
  (clean {:dir "target"})
  (pom nil)
  (step "Building jar")
  (b/copy-dir {:src-dirs   (mapcat val
                                   (select-keys @!config [:src-dirs :resource-dirs]))
               :target-dir (:class-dir @!config)})
  (b/jar (select-keys @!config [:class-dir :jar-file]))
  (done))


(defn install [_]
  (maybe-load-config! !config)
  (jar nil)
  (step "Installing jar to local repo")
  (b/install (select-keys @!config [:basis :lib :version :class-dir :jar-file]))
  (done))


(defn deploy
  "Securely handles the Clojars credentials by prompting the user and passing
  them on to Maven via the environment. gpg-agent should be set up correctly as
  well (incl. a graphical pinentry interface) so that the signing key passphrase
  doesn't have to be cached, stored or provided to Maven in an insecure manner
  (or at all)."
  [{:keys [sign check]}]
  (assert (or (nil? sign) (boolean? sign))
          "`:sign` has to be `true` or `false`")
  (assert (or (nil? check) (boolean? check))
          "`:check` has to be `true` or `false`")
  (maybe-load-config! !config)
  (let [{:keys [lib version signing-key class-dir jar-file]} @!config
        snapshot? (snapshot-version? version)
        sign?     (and signing-key
                       (not (false? sign))
                       (not snapshot?))
        repos     (distribution-repos @!config)]
    (assert (not-empty repos)
            "Distribution repositories not found in project.edn – add `[:distributionManagement [:repository ...]]` element to `:pom-data` hiccup.")
    (when (and (not snapshot?)
               (not (false? check)))
      (predeploy-checks @!config))
    (jar nil)
    (doseq [{:keys [id url]} repos]
      (step (format "\nDeploying %s jar to `%s` repo"
                    (if sign? "signed" "unsigned")
                    id))
      (b/process {:env (read-credentials id)
                  :command-args
                  (remove nil?
                          ["mvn"
                           (if sign?
                             "gpg:sign-and-deploy-file"
                             "deploy:deploy-file")
                           (str "-Dfile=" jar-file)
                           (format "-DpomFile=%s/META-INF/maven/%s/pom.xml"
                                   class-dir
                                   lib)
                           (str "-DrepositoryId=" id)
                           (str "-Durl=" url)
                           (when sign?
                             (str "-Dgpg.keyname=" signing-key))])}))
    (when sign?
      (println "\n")
      (doseq [hash-algo ["sha512" "rmd160"]]
        (b/process {:command-args ["openssl" hash-algo jar-file]})))))
