(ns clojure-lsp.interop
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [medley.core :as medley])
  (:import
    (org.eclipse.lsp4j
      CompletionItem
      CompletionItemKind
      Diagnostic
      DiagnosticSeverity
      DocumentSymbol
      Hover
      Location
      MarkedString
      MarkupContent
      Position
      PublishDiagnosticsParams
      Range
      SymbolKind
      SymbolInformation
      TextDocumentEdit
      TextEdit
      VersionedTextDocumentIdentifier
      WorkspaceEdit)
    (org.eclipse.lsp4j.jsonrpc.messages Either)
    (java.net URLDecoder)))

(s/def ::line (s/and integer? (s/conformer int)))
(s/def ::character (s/and integer? (s/conformer int)))
(s/def ::position (s/and (s/keys :req-un [::line ::character])
                         (s/conformer #(Position. (:line %1) (:character %1)))))
(s/def ::start ::position)
(s/def ::end ::position)
(s/def ::range (s/and (s/keys :req-un [::start ::end])
                      (s/conformer #(Range. (:start %1) (:end %1)))))

(def completion-kind-enum
  {:text 1 :method 2 :function 3 :constructor 4 :field 5 :variable 6 :class 7 :interface 8 :module 9
   :property 10 :unit 11 :value 12 :enum 13 :keyword 14 :snippet 15 :color 16 :file 17 :reference 18
   :folder 19 :enummember 20 :constant 21 :struct 22 :event 23 :operator 24 :typeparameter 25})

(s/def :completion-item/kind (s/and keyword?
                     completion-kind-enum
                     (s/conformer (fn [v] (CompletionItemKind/forValue (get completion-kind-enum v))))))
(s/def ::new-text string?)
(s/def ::text-edit (s/and (s/keys :req-un [::new-text ::range])
                          (s/conformer #(TextEdit. (:range %1) (:new-text %1)))))
(s/def ::additional-text-edits (s/coll-of ::text-edit))
(s/def ::completion-item (s/and (s/keys :req-un [::label]
                                  :opt-un [::additional-text-edits ::filter-text ::detail ::text-edit :completion-item/kind])
                                (s/conformer (fn [{:keys [label additional-text-edits filter-text detail text-edit kind]}]
                                               (let [item (CompletionItem. label)]
                                                 (cond-> item
                                                   filter-text (doto (.setFilterText filter-text))
                                                   kind (doto (.setKind kind))
                                                   additional-text-edits (doto (.setAdditionalTextEdits additional-text-edits))
                                                   detail (doto (.setDetail detail))
                                                   text-edit (doto (.setTextEdit text-edit))))))))
(s/def ::completion-items (s/coll-of ::completion-item))
(s/def ::version (s/and integer? (s/conformer int)))
(s/def ::uri string?)
(s/def ::edits (s/coll-of ::text-edit))
(s/def ::text-document (s/and (s/keys :req-un [::version ::uri])
                              (s/conformer #(doto (VersionedTextDocumentIdentifier. (:version %1))
                                              (.setUri (:uri %1))))))
(s/def ::text-document-edit (s/and (s/keys :req-un [::text-document ::edits])
                                   (s/conformer #(TextDocumentEdit. (:text-document %1) (:edits %1)))))
(s/def ::changes (s/coll-of (s/tuple string? ::edits) :kind map?))
(s/def ::document-changes (s/and (s/coll-of ::text-document-edit)
                                 (s/conformer (fn [c] (map #(Either/forLeft %) c)))))
(s/def ::workspace-edit (s/and (s/keys :opt-un [::document-changes ::changes])
                               (s/conformer #(if-let [changes (:changes %)]
                                               (WorkspaceEdit. changes)
                                               (WorkspaceEdit. (:document-changes %))))))
(s/def ::location (s/and (s/keys :req-un [::uri ::range])
                         (s/conformer #(Location. (:uri %1) (:range %1)))))
(s/def ::references (s/coll-of ::location))

(def symbol-kind-enum
  {:file 1 :module 2 :namespace 3 :package 4 :class 5 :method 6 :property 7 :field 8 :constructor 9
   :enum 10 :interface 11 :function 12 :variable 13 :constant 14 :string 15 :number 16 :boolean 17
   :array 18 :object 19 :key 20 :null 21 :enum-member 22 :struct 23 :event 24 :operator 25
   :type-parameter 26})

(s/def :symbol/kind (s/and keyword?
                           symbol-kind-enum
                           (s/conformer (fn [v] (SymbolKind/forValue (get symbol-kind-enum v))))))

(s/def :document-symbol/selection-range ::range)

(s/def :document-symbol/detail string?)

(s/def ::document-symbol (s/and (s/keys :req-un [::name :symbol/kind ::range :document-symbol/selection-range]
                                        :opt-un [:document-symbol/detail :document-symbol/children])
                                (s/conformer (fn [m]
                                               (DocumentSymbol. (:name m) (:kind m) (:range m) 
                                                                (:selection-range m) (:detail m) (:children m))))))

(s/def :document-symbol/children (s/coll-of ::document-symbol))

(s/def ::document-symbols (s/and (s/coll-of ::document-symbol)
                                 (s/conformer (fn [c]
                                                (map #(Either/forRight %) c)))))

(s/def ::symbol-information (s/and (s/keys :req-un [::name :symbol/kind ::location])
                                   (s/conformer (fn [m]
                                                  (SymbolInformation. (:name m) (:kind m) (:location m))))))

(s/def ::workspace-symbols (s/coll-of ::symbol-information))

(s/def ::severity (s/and integer?
                         (s/conformer #(DiagnosticSeverity/forValue %1))))

(s/def ::code (s/conformer name))

(s/def ::diagnostic (s/and (s/keys :req-un [::range ::message]
                                   :opt-un [::severity ::code ::source ::message])
                           (s/conformer #(Diagnostic. (:range %1) (:message %1) (:severity %1) (:source %1) (:code %1)))))
(s/def ::diagnostics (s/coll-of ::diagnostic))
(s/def ::publish-diagnostics-params (s/and (s/keys :req-un [::uri ::diagnostics])
                                           (s/conformer #(PublishDiagnosticsParams. (:uri %1) (:diagnostics %1)))))

(s/def ::marked-string (s/and (s/or :string string?
                                    :marked-string (s/and (s/keys :req-un [::language ::value])
                                                          (s/conformer #(MarkedString. (:language %1) (:value %1)))))
                              (s/conformer (fn [v]
                                             (case (first v)
                                               :string (Either/forLeft (second v))
                                               :marked-string (Either/forRight (second v)))))))

(s/def :markup/kind #{"plaintext" "markdown"})
(s/def :markup/value string?)
(s/def ::markup-content (s/and (s/keys :req-un [:markup/kind :markup/value])
                               (s/conformer #(doto (MarkupContent.)
                                               (.setKind (:kind %1))
                                               (.setValue (:value %1))))))

(s/def ::contents (s/and (s/or :marked-strings (s/coll-of ::marked-string)
                               :markup-content ::markup-content)
                         (s/conformer second)))

(s/def ::hover (s/and (s/keys :req-un [::contents]
                              :opt-un [::range])
                      (s/conformer #(Hover. (:contents %1) (:range %1)))))

(defn debeaner [inst]
  (when inst
    (->> (dissoc (bean inst) :class)
         (into {})
         (medley/remove-vals nil?)
         (medley/map-keys #(as-> % map-key
                             (name map-key)
                             (string/split map-key #"(?=[A-Z])")
                             (string/join "-" map-key)
                             (string/lower-case map-key)
                             (keyword map-key))))))

(s/def ::debean (s/conformer debeaner))
(s/def ::value-set (s/conformer (fn [value-set]
                                  (set (map #(.getValue %) value-set)))))
(s/def :capabilities/code-action ::debean)
(s/def :capabilities/code-lens ::debean)
(s/def :capabilities/color-provider ::debean)
(s/def :capabilities/completion-item ::debean)
(s/def :capabilities/definition ::debean)
(s/def :capabilities/document-highlight ::debean)
(s/def :capabilities/document-link ::debean)
(s/def :capabilities/formatting ::debean)
(s/def :capabilities/implementation ::debean)
(s/def :capabilities/on-type-formatting ::debean)
(s/def :capabilities/publish-diagnostics ::debean)
(s/def :capabilities/range-formatting ::debean)
(s/def :capabilities/references ::debean)
(s/def :capabilities/rename ::debean)
(s/def :capabilities/signature-information ::debean)
(s/def :capabilities/synchronization ::debean)
(s/def :capabilities/type-definition ::debean)

(s/def :capabilities/symbol-kind (s/and ::debean
                                        (s/keys :opt-un [::value-set])))
(s/def :capabilities/document-symbol (s/and ::debean
                                            (s/keys :opt-un [:capabilities/symbol-kind])))
(s/def :capabilities/signature-help (s/and ::debean
                                           (s/keys :opt-un [:capabilities/signature-information])))

(s/def :capabilities/completion-item-kind (s/and ::debean
                                                 (s/keys :opt-un [::value-set])))
(s/def :capabilities/completion (s/and ::debean
                                       (s/keys :opt-un [:capabilities/completion-item
                                                        :capabilities/completion-item-kind])))
(s/def :capabilities/hover (s/and ::debean
                                  (s/keys :opt-un [:capabilities/content-format])))
(s/def :capabilities/text-document (s/and ::debean
                                          (s/keys :opt-un [:capabilities/hover
                                                           :capabilities/completion
                                                           :capabilities/definition
                                                           :capabilities/formatting
                                                           :capabilities/publish-diagnostics
                                                           :capabilities/code-action
                                                           :capabilities/document-symbol
                                                           :capabilities/code-lens
                                                           :capabilities/document-highlight
                                                           :capabilities/color-provider
                                                           :capabilities/type-definition
                                                           :capabilities/rename
                                                           :capabilities/references
                                                           :capabilities/document-link
                                                           :capabilities/synchronization
                                                           :capabilities/range-formatting
                                                           :capabilities/on-type-formatting
                                                           :capabilities/signature-help
                                                           :capabilities/implementation])))

(s/def :capabilities/workspace-edit ::debean)
(s/def :capabilities/workspace-edit ::debean)
(s/def :capabilities/did-change-configuration ::debean)
(s/def :capabilities/did-change-watched-files ::debean)
(s/def :capabilities/execute-command ::debean)
(s/def :capabilities/symbol (s/and ::debean
                                   (s/keys :opt-un [:capabilities/symbol-kind])))
(s/def :capabilities/workspace (s/and ::debean
                                      (s/keys :opt-un [:capabilities/workspace-edit
                                                       :capabilities/did-change-configuration
                                                       :capabilities/did-change-watched-files
                                                       :capabilities/execute-command
                                                       :capabilities/symbol])))
(s/def ::client-capabilities (s/and ::debean
                                    (s/keys :opt-un [:capabilities/workspace :capabilities/text-document])))

(defn conform-or-log [spec value]
  (when value
    (try
      (let [result (s/conform spec value)]
        (if (= :clojure.spec.alpha/invalid result)
          (log/error (s/explain-data spec value))
          result))
      (catch Exception ex
        (log/error ex spec value)))))

(defn json->clj [json-element]
  (cond
    (nil? json-element)
    nil

    (.isJsonNull json-element)
    nil

    (.isJsonArray json-element)
    (mapv json->clj (iterator-seq (.iterator (.getAsJsonArray json-element))))

    (.isJsonObject json-element)
    (->> json-element
         (.getAsJsonObject)
         (.entrySet)
         (map (juxt key (comp json->clj val)))
         (into {}))

    (.isJsonPrimitive json-element)
    (let [json-primitive (.getAsJsonPrimitive json-element)]
      (cond
        (.isString json-primitive) (.getAsString json-primitive)
        (.isNumber json-primitive) (.getAsLong json-primitive)
        (.isBoolean json-primitive) (.getAsBoolean json-primitive)
        :else json-primitive))

    :else
    json-element))

(defn- typify-json [root]
  (walk/postwalk (fn [n]
                   (if (string? n)
                     (keyword n)
                     n))
                 root))

(defn- clean-symbol-map [m]
  (->> (or m {})
       (medley/map-keys #(if (string/starts-with? % "#")
                           (re-pattern (subs % 1))
                           (symbol %)))
       (medley/map-vals typify-json)))

(defn clean-client-settings [client-settings]
  (let [kwd-keys #(medley/map-keys keyword %)]
    (-> client-settings
        (update "dependency-scheme" #(or % "zipfile"))
        (update "source-paths" #(if (seq %) (set %) #{"src" "test"}))
        (update "macro-defs" clean-symbol-map)
        (update "project-specs" #(->> % (mapv kwd-keys) not-empty))
        (update "cljfmt" kwd-keys)
        (update-in ["cljfmt" :indents] clean-symbol-map))))

(defn document->decoded-uri [document]
  (-> document
      .getUri
      URLDecoder/decode))
