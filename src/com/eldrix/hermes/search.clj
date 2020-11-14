(ns com.eldrix.hermes.search
  (:import (java.nio.file Files)
           (org.apache.lucene.index IndexWriter IndexWriterConfig DirectoryReader)
           (org.apache.lucene.store FSDirectory)
           (org.apache.lucene.document Document Field TextField)
           (java.nio.file.attribute FileAttribute)
           (org.apache.lucene.search IndexSearcher)
           (org.apache.lucene.queryparser.classic QueryParser)))

(comment
  (def analyzer (org.apache.lucene.analysis.standard.StandardAnalyzer.))
  (def path (Files/createTempDirectory "tempIndex" (into-array FileAttribute [])))
  (def directory (FSDirectory/open path))
  (def writer-config (IndexWriterConfig. analyzer))
  (def writer (IndexWriter. directory writer-config ))
  (def doc (Document.))
  (.add doc (Field. "fieldName" "this is the text to be indexed" TextField/TYPE_STORED))
  (.addDocument writer doc)
  (.close writer)

  
  (def reader (DirectoryReader/open directory))
  (def searcher (IndexSearcher. reader))
  (def parser (QueryParser. "fieldName" analyzer))
  (def query (.parse parser "text"))
  (def hits (.-scoreDocs (.search searcher query 10)))
  (map #(.doc searcher %) (map #(.-doc %) (seq hits)))
  (map #(.get % "fieldName") (map #(.doc searcher %) (map #(.-doc %) (seq hits))))
  )