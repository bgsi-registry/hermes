; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.importer
  "Provides import functionality for processing directories of files"
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io File)))

(defn is-snomed-file? [f]
  (snomed/parse-snomed-filename (.getName (clojure.java.io/file f))))

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files, returning a sequence of maps.

  Each result is a map of SNOMED information from the filename as per
  the [release file documentation](https://confluence.ihtsdotools.org/display/DOCRELFMT/3.3.2+Release+File+Naming+Convention),
  with additional keys:

  path            : the path of the file,
  component       : the canonical name of the SNOMED component (e.g. 'Concept', 'SimpleRefset')
  component-order : the sort order as defined by component type"
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (map #(snomed/parse-snomed-filename (.getPath ^File %)))
       (filter :component)))

(defn importable-files
  "Return a list of importable files from the directory specified."
  [dir]
  (->> (snomed-file-seq dir)
       (filter #(= (:release-type %) "Snapshot"))
       (filter :parser)))

(def ^:private metadata-parsers
  {:effectiveTime snomed/parse-date
   :deltaToDate   snomed/parse-date
   :deltaFromDate snomed/parse-date})

(defn read-metadata-value [k v]
  ((or (k metadata-parsers) identity) v))

(defn read-metadata
  "Reads the metadata from the file specified.

  Unfortunately, some UK releases have invalid JSON in their metadata, so
  we log an error and avoid throwing an exception.
  Raised as issue #34057 with NHS Digital.

  Unfortunately the *name* of the release is not included currently, but as the
  metadata file exists at the root of the release, we can guess the name from
  the parent directory and use that if a 'name' isn't in the metadata.
  Raised as issue #32991 with Snomed International."
  [f]
  (let [parent (when (instance? File f) (.getParentFile ^File f))
        default (when parent {:name (.getName parent)})]
    (try
      (-> default                                           ;; start with sane default
          (merge (json/read-str (slurp f) :key-fn keyword :value-fn read-metadata-value)) ;; read in metadaa
          (update :modules update-keys (fn [x] (-> x name parse-long)))) ;; return all module identifiers as longs
      (catch Throwable e (log/warn e "Invalid metadata in distribution file" (:name default))
                         (assoc default :error "Invalid metadata in distribution file")))))

(defn metadata-files
  "Returns a list of release package information files from the directory.
  Each entry returned in the list will be a `java.io.File`.
  These files have been issued since the July 2020 International edition release."
  [dir]
  (->> (io/file dir)
       (file-seq)
       (filter #(= (.getName ^File %) "release_package_information.json"))))

(defn all-metadata
  "Returns all release metadata from the directory specified."
  [dir]
  (doall (->> (metadata-files dir)
              (map read-metadata))))

(defn- process-file
  "Process the specified file, streaming batched results to the channel
  specified, blocking if channel not being drained.

  Each batch is a map with keys
   - :type      : a type of SNOMED component
   - :parser    : a parser that can take each row and give you data
   - :headings  : a sequence of headings from the original file
   - :data      : a sequence of vectors representing each column."
  [filename out-c & {:keys [batch-size] :or {batch-size 1000}}]
  (with-open [reader (io/reader filename)]
    (let [snofile (snomed/parse-snomed-filename filename)
          parser (:parser snofile)]
      (when parser
        (let [csv-data (map #(str/split % #"\t") (line-seq reader))
              headings (first csv-data)
              data (rest csv-data)
              batches (->> data
                           (partition-all batch-size)
                           (map #(hash-map :type (:identifier snofile)
                                           :parser parser
                                           :headings headings
                                           :data %)))]
          (log/info "Processing: " (:filename snofile) " type: " (:component snofile))
          (log/debug "Processing " (count batches) " batches")
          (doseq [batch batches]
            (log/debug "Processing batch " {:batch (dissoc batch :data) :first-data (-> batch :data first)})
            (when-not (async/>!! out-c batch)
              (log/debug "Processing cancelled (output channel closed)")
              (throw (InterruptedException. "process cancelled")))))))))

(s/fdef load-snomed-files
  :args (s/cat :files (s/coll-of :info.snomed/ReleaseFile)
               :opts (s/keys* :opt-un [::nthreads ::batch-size])))

(defn load-snomed-files
  "Imports a SNOMED-CT distribution from the specified files, returning
  results on the returned channel which will be closed once all files have been
  sent through. Any exceptions will be passed on the channel."
  [files & {:keys [nthreads batch-size] :or {nthreads 4 batch-size 5000}}]
  (let [raw-c (async/chan)                                  ;; CSV data in batches with :type, :headings and :data, :data as a vector of raw strings
        processed-c (async/chan)]                           ;; CSV data in batches with :type, :headings and :data, :data as a vector of SNOMED entities
    (async/thread
      (log/debug "Processing " (count files) " files")
      (try
        (doseq [file files]
          (process-file (:path file) raw-c :batch-size batch-size))
        (catch Throwable e
          (log/debug "Error during raw SNOMED file import: " e)
          (async/>!! processed-c e)))
      (async/close! raw-c))
    (async/pipeline-blocking
      nthreads
      processed-c
      (map snomed/parse-batch)
      raw-c
      true
      (fn ex-handler [err] (log/debug "Error during import pipeline: " (ex-data err)) err))
    processed-c))

(defn load-snomed
  "Imports a SNOMED-CT distribution from the specified directory, returning
  results on the returned channel which will be closed once all files have been
  sent through. Any exceptions will be passed on the channel.

  This streams data in a single pass; in generally usage you will usually want
  to stream data in multiple passes."
  [dir & opts]
  (let [files (snomed-file-seq dir)]
    (load-snomed-files files opts)))

(comment
  (snomed/parse-snomed-filename "sct2_Concept_Full_INT_20190731.txt")
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20190731.txt"))

