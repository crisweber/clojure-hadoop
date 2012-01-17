(ns clojure-hadoop.config
  (:require [clojure-hadoop.imports :as imp]
            [clojure-hadoop.load :as load])
  (:use [clojure.contrib.string :only [trim replace-re]]
        [clojure.contrib.def :only [defvar]])
  (:import (org.apache.hadoop.filecache DistributedCache)))

;; This file defines configuration options for clojure-hadoop.
;;
;; The SAME options may be given either on the command line (to
;; clojure_hadoop.job) or in a call to defjob.
;;
;; In defjob, option names are keywords.  Values are symbols or
;; keywords.  Symbols are resolved as functions or classes.  Keywords
;; are converted to Strings.
;;
;; On the command line, option names are preceeded by "-".
;;
;; Options are defined as methods of the conf multimethod.
;; Documentation for individual options appears with each method,
;; below.

(imp/import-io)
(imp/import-io-compress)
(imp/import-fs)
(imp/import-mapreduce)
(imp/import-mapreduce-lib)

(defvar combine-cleanup "clojure-hadoop.job.combine.cleanup"
  "The name of the property that stores the cleanup function name of
  the combiner.")

(defvar combine-setup "clojure-hadoop.job.combine.setup"
  "The name of the property that stores the setup function name of the
  combiner.")

(defvar map-cleanup "clojure-hadoop.job.map.cleanup"
  "The name of the property that stores the cleanup function name of
  the mapper.")

(defvar map-setup "clojure-hadoop.job.map.setup"
  "The name of the property that stores the setup function name of the
  mapper.")

(defvar reduce-cleanup "clojure-hadoop.job.reduce.cleanup"
  "The name of the property that stores the cleanup function name of
  the reducer.")

(defvar reduce-setup "clojure-hadoop.job.reduce.setup"
  "The name of the property that stores the setup function name of the
  reducer.")

(defn- ^String as-str [s]
  (cond (keyword? s) (name s)
        (class? s) (.getName ^Class s)
        (fn? s) (throw (Exception. "Cannot use function as value; use a symbol."))
        :else (str s)))

(defn configuration
  "Returns the configuration for the job."
  [^Job job] (.getConfiguration job))

(defmulti conf (fn [job key value] key))

(defmethod conf :job [^Job job key value]
  (cond
   (string? value) (conf job :job (load/load-name value))
   (fn? value) (conf job :job (value))
   :else (doseq [[k v] value] (conf job k v))))

(defmethod conf :name [^Job job key value]  
  (.setJobName job value))

;; Job input paths, separated by commas, as a String.
(defmethod conf :input [^Job job key value]
  (FileInputFormat/setInputPaths job (as-str value)))

;; Job output path, as a String.
(defmethod conf :output [^Job job key value]
  (FileOutputFormat/setOutputPath job (Path. (as-str value))))

;; Job cache file, as a String.
(defmethod conf :cache-file [^Job job key value]
  (DistributedCache/addCacheFile (.toUri (Path. (as-str value))) (configuration job)))

;; When true or "true", deletes output path before starting.
(defmethod conf :replace [^Job job key value]
  (when (= (as-str value) "true")
    (.set (configuration job) "clojure-hadoop.job.replace" "true")))

;; The mapper function.  May be a class name or a Clojure function as
;; namespace/symbol.  May also be "identity" for IdentityMapper.
(defmethod conf :map [^Job job key value]
  (let [value (as-str value)]
    (cond
     (= "identity" value)
     (.setMapperClass job Mapper)

     (.contains value "/")
     (.set (configuration job) "clojure-hadoop.job.map" value)

     :else
     (.setMapperClass job (Class/forName value)))))

;; The name of the mapper cleanup function as namespace/symbol.
(defmethod conf :map-cleanup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) map-cleanup value))))

;; The name of the mapper setup function as namespace/symbol.
(defmethod conf :map-setup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) map-setup value))))

;; The reducer function.  May be a class name or a Clojure function as
;; namespace/symbol.  May also be "identity" for IdentityReducer or
;; "none" for no reduce stage.
(defmethod conf :reduce [^Job job key value]
  (let [value (as-str value)]
    (cond
     (= "identity" value)
     (.setReducerClass job Reducer)

     (= "none" value)
     (.setNumReduceTasks job 0)

     (.contains value "/")
     (.set (configuration job) "clojure-hadoop.job.reduce" value)

     :else
     (.setReducerClass job (Class/forName value)))))

;; The name of the reducer cleanup function as namespace/symbol.
(defmethod conf :reduce-cleanup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) reduce-cleanup value))))

;; The name of the reducer setup function as namespace/symbol.
(defmethod conf :reduce-setup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) reduce-setup value))))

(defmethod conf :reduce-tasks [^Job job key value]
  (if (integer? value)
    (.setNumReduceTasks job value)
    (try
      (.setNumReduceTasks job (Integer/parseInt (trim value)))
      (catch NumberFormatException _
        (throw (IllegalArgumentException. "The reduce-tasks option must be an integer."))))))

(defmethod conf :combine [^Job job key value]
  (let [value (as-str value)]
    (cond
     (.contains value "/")      
     (do
       (.setCombinerClass job (Class/forName "clojure_hadoop.job_combiner"))
       (.set (configuration job) "clojure-hadoop.job.combine" value))

     :else
     (.setCombinerClass job (Class/forName value)))))

;; The name of the combiner cleanup function as namespace/symbol.
(defmethod conf :combine-cleanup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) combine-cleanup value))))

;; The name of the reducer setup function as namespace/symbol.
(defmethod conf :combine-setup [^Job job key value]
  (let [value (as-str value)]
    (if (.contains value "/")
      (.set (configuration job) combine-setup value))))

;; The mapper reader function, converts Hadoop Writable types to
;; native Clojure types.
(defmethod conf :map-reader [^Job job key value]
  (.set (configuration job) "clojure-hadoop.job.map.reader" (as-str value)))

;; The mapper writer function; converts native Clojure types to Hadoop
;; Writable types.
(defmethod conf :map-writer [^Job job key value]
  (doto (configuration job)
    (.set "clojure-hadoop.job.map.writer" (as-str value))
    (.set "clojure-hadoop.job.combine.writer" (as-str value))))

;; The mapper output key class; used when the mapper writer outputs
;; types different from the job output.
(defmethod conf :map-output-key [^Job job key value]
  (.setMapOutputKeyClass job (Class/forName value)))

;; The mapper output value class; used when the mapper writer outputs
;; types different from the job output.
(defmethod conf :map-output-value [^Job job key value]
  (.setMapOutputValueClass job (Class/forName value)))

;; The job output key class.
(defmethod conf :output-key [^Job job key value]
  (.setOutputKeyClass job (Class/forName value)))

;; The job output value class.
(defmethod conf :output-value [^Job job key value]
  (.setOutputValueClass job (Class/forName value)))

;; The reducer reader function, converts Hadoop Writable types to
;; native Clojure types.
(defmethod conf :reduce-reader [^Job job key value]
  (doto (configuration job)
    (.set "clojure-hadoop.job.reduce.reader" (as-str value))
    (.set "clojure-hadoop.job.combine.reader" (as-str value))))

;; The reducer writer function; converts native Clojure types to
;; Hadoop Writable types.
(defmethod conf :reduce-writer [^Job job key value]
  (.set (configuration job) "clojure-hadoop.job.reduce.writer" (as-str value)))

;; The input file format.  May be a class name or "text" for
;; TextInputFormat, "kvtext" fro KeyValueTextInputFormat, "seq" for
;; SequenceFileInputFormat.
(defmethod conf :input-format [^Job job key value]
  (let [val (as-str value)]
    (cond
     (= "text" val)
     (.setInputFormatClass job TextInputFormat)

     (= "seq" val)
     (.setInputFormatClass job SequenceFileInputFormat)

     :else
     (.setInputFormatClass job (Class/forName value)))))

;; The output file format.  May be a class name or "text" for
;; TextOutputFormat, "seq" for SequenceFileOutputFormat.
(defmethod conf :output-format [^Job job key value]
  (let [val (as-str value)]
    (cond
     (= "text" val)
     (.setOutputFormatClass job TextOutputFormat)

     (= "seq" val)
     (.setOutputFormatClass job SequenceFileOutputFormat)

     :else
     (.setOutputFormatClass job (Class/forName value)))))

;; If true, compress job output files.
(defmethod conf :compress-output [^Job job key value]
  (let [val (.toLowerCase (as-str value))]
    (cond
     (= "true" val)
     (FileOutputFormat/setCompressOutput job true)

     (= "false" val)
     (FileOutputFormat/setCompressOutput job false)

     :else
     (throw (Exception. (str "compress-output value must be true or false, but given '" val "'"))))))

;; Codec to use for compressing job output files.
(defmethod conf :output-compressor [^Job job key value]
  (let [val (as-str value)]
    (cond
     (= "default" val)
     (FileOutputFormat/setOutputCompressorClass job DefaultCodec)

     (= "gzip" val)
     (FileOutputFormat/setOutputCompressorClass job GzipCodec)

     (= "bzip2" val)
     (FileOutputFormat/setOutputCompressorClass job BZip2Codec)

     :else
     (FileOutputFormat/setOutputCompressorClass job (Class/forName value)))))

;; Type of compression to use for sequence files.
(defmethod conf :compression-type [^Job job key value]
  (let [val (as-str value)]
    (cond
     (= "block" val)
     (SequenceFileOutputFormat/setOutputCompressionType
      job SequenceFile$CompressionType/BLOCK)

     (= "none" val)
     (SequenceFileOutputFormat/setOutputCompressionType
      job SequenceFile$CompressionType/NONE)

     (= "record" val)
     (SequenceFileOutputFormat/setOutputCompressionType
      job SequenceFile$CompressionType/RECORD))))

(defn- to-keyword [^String k]
  (keyword 
   (let [fk (first k)]
     (if (or (= fk \:) (= fk \-))
       (.substring k 1)
       k))))

(defn parse-command-line-args [^Job job args]
  (when (empty? args)
    (throw (IllegalArgumentException. "Missing required options.")))
  (when-not (even? (count args))
    (throw (IllegalArgumentException. "Number of options must be even.")))
  (doseq [[k v] (partition 2 args)]
    (conf job (to-keyword k) v)))

(defn print-usage []
  (println "Usage: java -cp [jars...] clojure_hadoop.job [options...]
Required options are:
 -input     comma-separated input paths
 -output    output path
 -map       mapper function, as namespace/name or class name
 -reduce    reducer function, as namespace/name or class name
OR
 -job       job definition function, as namespace/name

Mapper or reducer function may also be \"identity\".
Reducer function may also be \"none\".

Other available options are:
 -input-format      Class name or \"text\" or \"seq\" (SeqFile)
 -output-format     Class name or \"text\" or \"seq\" (SeqFile)
 -output-key        Class for job output key
 -output-value      Class for job output value
 -map-count         Number of Mapper instances
 -map-output-key    Class for intermediate Mapper output key
 -map-output-value  Class for intermediate Mapper output value
 -map-reader        Mapper reader function, as namespace/name
 -map-writer        Mapper writer function, as namespace/name
 -reduce-count      Number of Reducer instances
 -reduce-reader     Reducer reader function, as namespace/name
 -reduce-writer     Reducer writer function, as namespace/name
 -combine           Combiner function, as namespace/name or class name
 -name              Job name
 -replace           If \"true\", deletes output dir before start
 -compress-output   If \"true\", compress job output files
 -output-compressor Compression class or \"gzip\",\"bzip2\",\"default\"
 -compression-type  For seqfiles, compress \"block\",\"record\",\"none\"
 -cache-file        File to be added to DistributedCache
"))

