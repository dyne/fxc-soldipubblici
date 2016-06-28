(ns fxc-soldipubblici.core
  (:require [clj-http.client :as client]
            [clj-http.cookies :as cookies]
            [clojure.string :as string]
            [clojure.walk :refer :all]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure-csv.core :refer :all]
            [semantic-csv.core :refer :all]
            [gorilla-repl.table :refer :all]
            [clojure.contrib.humanize :refer :all]
            [huri.core :as huri]
            )
  )

(defn analizza-dati [dati]
  (let [colonne [:2016 :2015 :2014 :siope :desc]
        ;; prende gli importi numerici, interpreta la stringa in un intero e divide per centesimi
        rilievo (huri/derive-cols {:2016     [#(if (nil? %) 0 (quot (read-string %) 100)) :importo_2016]
                                   :2015     [#(if (nil? %) 0 (quot (read-string %) 100)) :importo_2015]
                                   :2014     [#(if (nil? %) 0 (quot (read-string %) 100)) :importo_2014]
                                   :siope    [read-string :codice_siope]
                                ;; :uscita   [#(if (nil? %) 0 (/ (read-string %) 100)) :imp_uscite_att]
                                   :desc     :descrizione_codice
                                   } dati)]
    (huri/select-cols colonne rilievo))
  )

(defn visualizza-tavola [dati]
  (table-view (map vals dati)
              :columns (some keys dati))
  )

(defn leggibile [num]
  (cond
    (nil? num) "zero"
    (< num 1) "zero"
    :else (intword num))
  )

(defn ordina-analisi
  "ordina il risultato di (analizza-dati) per [chiave] e rende le cifre piu' leggibili"
 [chiave rilievo]
  (reverse ;; assoc rende l'hashmap discendente
   (map #(assoc %
                ;; aggiunge una stringa leggibile alle cifre
               :2016 (let [v (:2016 %)] [v (leggibile v)])
               :2015 (let [v (:2015 %)] [v (leggibile v)])
               :2014 (let [v (:2014 %)] [v (leggibile v)])
               ) (sort-by (first (keys chiave))
                          (where chiave rilievo)))
   )
  )

(defn raccogli-dati [comparto ente chi]
  (let [cs (cookies/cookie-store)
        df (binding [clj-http.core/*cookie-store* cs]
            (client/get "http://soldipubblici.gov.it/it" {:cookie-store cs})
            (-> (client/post "http://soldipubblici.gov.it/it/ricerca"
                             {:form-params {"codicecomparto" comparto
                                            "codiceente" ente
                                            "chi" chi
                                            "cosa" "" }
                              :cookie-store cs
                              :headers {"X-Requested-With" "XMLHttpRequest"}
                              :accept :json
                              :as :json})
                :body
                :data
                ))]
    df
  ))

(defn cerca-enti [needle]
  (let [anag (with-open [in-file (io/reader "assets/ANAG_ENTI_SIOPE.D160624.H0102.csv")]
               (doall
                (csv/read-csv in-file)))]
    (->> anag
        (keep #(if (string/includes? (str %) (string/upper-case needle)) %))
        (into [["codice" "creazione" "scadenza" "pos4" "nome" "pos6" "pos7" "popolazione" "tipo"]])
        mappify
        doall)
  ))


(defn raccogli-tutto
  "Raccoglie tutti i dati disponibili su qualsiasi ente la cui descrizione contiene la stringa"
  [stringa]
  (let [enti (cerca-enti stringa)
        tutto []]
    (map #(into tutto
                (analizza-dati (raccogli-dati "PRO" (:codice %) (:nome %))) enti))
  ))



(defn query-siope-anagrafe
  "TODO: scarica ed indicizza anagrafe dai file zippati di SIOPE.it"
  []
  (let [anazip
        (-> (client/get "https://www.siope.it/Siope2Web/documenti/siope2/open/last/SIOPE_ANAGRAFICHE.zip" {:as :byte-array})
            (:body)
            (io/input-stream)
            (java.util.zip.ZipInputStream.))]
    {:comuni   (.getNextEntry anazip)
     :entrate  (.getNextEntry anazip)
     :uscite   (.getNextEntry anazip)
     :comparti (.getNextEntry anazip)
     :siope    (.getNextEntry anazip)
     :regprov  (.getNextEntry anazip)}
    ))
