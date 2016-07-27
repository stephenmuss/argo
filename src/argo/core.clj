(ns argo.core
  (:require
    [clojure.core.match :refer [match]]
    [clojure.string :as str]
    [cheshire.generate :refer [add-encoder]]
    [clj-time.format :as f]
    [compojure.core :refer [ANY defroutes context routes]]
    [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
    [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.nested-params :refer [wrap-nested-params]]
    [ring.middleware.params :refer [wrap-params]]
    [schema.core :as s]
    [schema.utils :as su]
    [taoensso.timbre :as timbre])
  (:import [java.util UUID]
           [org.joda.time DateTime]
           [schema.utils ValidationError NamedError]
           [schema.core NamedSchema]))


(add-encoder DateTime
             (fn [date json-generator]
               (.writeString json-generator (f/unparse (f/formatters :date-time) date))))

(add-encoder ValidationError
             (fn [err json-generator]
               (.writeString json-generator (str (su/validation-error-explain err)))))

(add-encoder NamedError
             (fn [err json-generator]
               (.writeString json-generator (str (.-name err)))))

(def base-url "")

(defn ok
  [data & {:keys [status headers links meta included]}]
  {:status (or status 200)
   :headers (merge {"Content-Type" "application/vnd.api+json"} headers)
   :body (merge {:data data} (when links {:links links}) (when meta {:meta meta}) (when included {:included included}))})

(defn flatten-errors
  ([errors]
     (into {} (flatten-errors errors nil)))
  ([errors pre]
     (mapcat (fn [[k v]]
               (let [prefix (if pre (str pre "/" (name k)) (name k))]
                 (cond (instance? NamedSchema v) [[(str "/" prefix) (:name v)]]
                       (map? v) (flatten-errors v prefix)
                       :else [[(str "/" prefix) v]])))
               errors)))

(defn make-errors
  [errors & {:keys [exclude-source]}]
  (let [err (if (instance? ValidationError errors) (.schema errors) errors)
        e (if exclude-source
            (map (fn [[k v]] {:title v}) (flatten-errors err))
            (map (fn [[k v]] {:source {:pointer k} :title v}) (flatten-errors err)))]
    {:errors e}))

(defn bad-req
  [errors & {:keys [status exclude-source]}]
  {:status (or status 400)
   :headers {"Content-Type" "application/vnd.api+json"}
   :body (make-errors errors :exclude-source exclude-source)})

(defn map-to-qs [m]
  (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) m)))

(defn gen-qs
  [uri params offset limit]
  (let [qs (str/join "&" (filter not-empty [params (str "page[offset]=" offset) (str "page[limit]=" limit)]))]
    (str/join "?" [uri qs])))

(defn gen-pagination-links
  [req {cnt :count offset :offset limit :limit}]
  (when cnt
    (let [uri (:uri req)
          qs (:query-string req)
          params (dissoc (:query-params req) "page[offset]" "page[limit]")
          params-encoded (map-to-qs params)
          next-offset (+ offset limit)
          prev-offset (- offset limit)
          last-offset (- cnt limit)]
      (-> {:self (str/join "?" (filter not-empty [uri qs]))}
          (merge (when (< next-offset cnt)
                   {:next (gen-qs uri params-encoded next-offset limit)}))
          (merge (when (>= prev-offset 0)
                   {:prev (gen-qs uri params-encoded prev-offset limit)}))
          (merge (when (> last-offset offset)
                   {:last (gen-qs uri params-encoded last-offset limit)}))
          (merge (when (> offset 0)
                   {:first (gen-qs uri params-encoded 0 limit)}))))))

(comment (dissoc (apply dissoc x (map (fn [[k v]] (:foreign-key v)) rels)) primary-key))

(defn x-to-api
  [type x primary-key & [rels]]
  (when x
    (merge {:type type
            :id (str (get x primary-key))
            :attributes (-> x
                            (dissoc (map (fn [[k v]] (:foreign-key v)) rels))
                            (dissoc primary-key)
                            (dissoc :included))
            :links {:self (str base-url "/" type "/" (get x primary-key))}}
           (when rels {:relationships (apply merge (map (fn [[k v]]
                                                          {k {:links {:related (str base-url "/" type "/" (get x primary-key) "/" (name k))}
                                                              :data (get-in x [:included k])}})
                                                        rels))}))))

(defn wrap-pagination
  [default-limit max-limit]
  (fn [handler]
    (fn [req]
      (if (= :get (:request-method req))
        (let [page (-> req :params :page)]
          (if (nil? page)
            (handler (assoc req :page {:offset 0 :limit default-limit}))
            (let [offset (try (Integer/parseInt (:offset page "0")) (catch Throwable t t))
                  limit (try (Integer/parseInt (:limit page (str default-limit))) (catch Throwable t t))
                  err (fn [e] {:status 400 :headers {"Content-Type" "application/vnd.api+json"} :body {:errors e}})]
              (cond
                (instance? Throwable offset) (err [{:title "Invalid page offset" :status "400" :source {:parameter "page[offset]"}}])
                (instance? Throwable limit) (err [{:title "Invalid page limit" :status "400" :source {:parameter "page[limit]"}}])
                (< offset 0) (err [{:title "page offset cannot be below 0" :status "400" :source {:parameter "page[offset]"}}])
                (< limit 1) (err [{:title "page limit cannot be below 1" :status "400" :source {:parameter "page[limit]"}}])
                (> limit max-limit) (err [{:title (format "page limit cannot exceed %d" max-limit) :status "400" :source {:parameter "page[limit]"}}])
                :else (handler (assoc req :page {:offset offset :limit limit}))))))
        (handler req)))))

(defn wrap-error [handler]
  (fn [req]
    (try (handler req)
         (catch Throwable t
           (let [id (str (UUID/randomUUID))]
             (timbre/error t id)
             {:status 500
              :headers {"Content-Type" "application/vnd.api+json"}
              :body {:errors [{:status "500" :id id :title "Internal server error."}]}})))))

(defn merge-relationships
  [data rel]
  (let [t (:type rel)
        tt (if (sequential? t) (name (first t)) (name t))
        data-constraint {:id s/Str :type (s/named (s/eq tt) (str "type must equal " tt))}]
    (merge-with merge data {:relationships {(:name rel) {:data (if (sequential? t)
                                                                 [data-constraint]
                                                                 data-constraint)}}})))

(defn CreateRequest
  [typ schema & relationships]
  (let [type-name (name typ)
        data {:data {:attributes schema
                     :type (s/named (s/eq type-name) (str "type must equal " type-name))}}]
    (if relationships
      (merge-with merge data {:data (reduce merge-relationships {} relationships)})
      data)))

(defn not-found [& _]
  {:status 404
   :headers {"Content-Type" "application/vnd.api+json"}
   :body {:errors [{:title "resource not found" :status "404"}]}})

(defmacro defapi
  [label api]
  (let [resources (:resources api)
        middleware (:middleware api)
        base (or (:base-url api) "")]
    `(def ~label
       (let [wp# (wrap-pagination 10 50)] ; TODO: allow user defined values
         (alter-var-root #'base-url (fn [_#] ~base))
         (-> (routes (context ~base [] ~@resources) not-found)
           ~@middleware
           wp#
           (wrap-json-body {:keywords? true :bigdecimals? true})
           wrap-keyword-params
           wrap-nested-params
           wrap-params
           (wrap-defaults api-defaults)
           wrap-error
           wrap-json-response)))))


(defn rel-req
  [func req]
  (let [{errors :errors
         status :status
         exclude-source :exclude-source} (func req)]
    (if errors
      (bad-req errors :status status :exclude-source exclude-source)
      {:status 204})))

(defmacro defresource
  [label resource]

  (let [typ (str label)
        path (str "/" label)
        req (gensym)
        primary-key (:primary-key resource :id)
        rels (:rels resource)
        get-many (:find resource)
        get-one (:get resource)
        create (:create resource)
        update (:update resource)
        delete (:delete resource)
        allowed-many (str/join ", " (concat ["OPTIONS"]
                                            (when get-many ["GET"])
                                            (when create ["POST"])))
        allowed-one (str/join ", " (concat ["OPTIONS"]
                                           (when get-one ["GET"])
                                           (when update ["PATCH"])
                                           (when delete ["DELETE"])))]

    `(defroutes ~label
       (context ~path []
          (ANY "/" []
            (fn [~req]
              (match (:request-method ~req)
                ~@(when get-many
                    `(:get (let [{data# :data
                                  errors# :errors
                                  exclude-source# :exclude-source
                                  status# :status
                                  total# :count
                                  m# :meta
                                  included# :included} (~get-many ~req)
                                 pag# (assoc (:page ~req) :count total#)
                                 links# (gen-pagination-links ~req pag#)]
                             (if errors#
                               (bad-req errors# :status status# :exclude-source exclude-source#)
                               (ok (map (fn [x#] (x-to-api ~typ x# ~primary-key ~rels)) data#) :links links# :meta m# :included included#)))))

                ~@(when create
                    `(:post (let [{data# :data
                                   errors# :errors
                                   exclude-source# :exclude-source
                                   status# :status
                                   m# :meta} (~create ~req)]
                              (if errors#
                                (bad-req errors# :status status# :exclude-source exclude-source#)
                                (ok (x-to-api ~typ data# ~primary-key ~rels) :status 201 :meta m#)))))

                :options {:headers {"Allowed" ~allowed-many}}
                :else {:status 405 :headers {"Allowed" ~allowed-many}})))

          (ANY "/:id" []
            (fn [~req]
              (match (:request-method ~req)
                ~@(when get-one
                    `(:get (let [{data# :data
                                  status# :status
                                  exclude-source# :exclude-source
                                  errors# :errors
                                  m# :meta} (~get-one ~req)]
                             (cond
                               errors# (bad-req errors# :status status# :exclude-source exclude-source#)
                               (nil? data#) (not-found)
                               :else (ok (x-to-api ~typ data# ~primary-key ~rels) :meta m#)))))

                ~@(when update
                    `(:patch (let [{data# :data
                                    status# :status
                                    exclude-source# :exclude-source
                                    errors# :errors
                                    m# :meta} (~update ~req)]
                               (cond
                                 errors# (bad-req errors# :status status# :exclude-source exclude-source#)
                                 data# (ok (x-to-api ~typ data# ~primary-key ~rels) :meta m#)
                                 :else {:status 204}))))

                ~@(when delete
                    `(:delete (let [{errors# :errors status# :status exclude-source# :exclude-source} (~delete ~req)]
                                (if errors#
                                  (bad-req errors# :status status# :exclude-source exclude-source#)
                                  {:status 204}))))

                :options {:headers {"Allow" ~allowed-one}}
                :else {:status 405 :headers {"Allow" ~allowed-one}})))

          ~@(when rels
              (map (fn [[rel handler]]
                     (let [{getf :get create :create update :update delete :delete} handler
                           allowed (str/join ", " (concat (when getf ["GET"])
                                                          (when create ["POST"])
                                                          (when update ["PATCH"])
                                                          (when delete ["DELETE"])
                                                          ["OPTIONS"]))
                           many? (sequential? (:type handler))
                           typ (name (if many? (-> handler :type first) (:type handler)))
                           path (str "/:id/" (name rel))
                           total (gensym)]
                       `(ANY ~path []
                             (fn [~req]
                               (match (:request-method ~req)
                                      ~@(when getf
                                          (let [data (gensym)
                                                relations (gensym)
                                                m (gensym)]
                                            `(:get (let [{~data :data
                                                          errors# :errors
                                                          status# :status
                                                          exclude-source# :exclude-source
                                                          ~total :count
                                                          ~relations :rels
                                                          ~m :meta} (~getf ~req)]
                                                     (if errors#
                                                       (bad-req errors# :status status# :exclude-source exclude-source#)
                                                       ~@(if many?
                                                           `((ok (map #(x-to-api ~typ % ~primary-key ~relations) ~data)
                                                                 :links (gen-pagination-links ~req (assoc (:page ~req) :count ~total))
                                                                 :meta ~m))
                                                           `((ok (x-to-api ~typ ~data ~primary-key ~relations) :meta ~m))))))))
                                      ~@(when create
                                          `(:post (rel-req ~create ~req)))

                                      ~@(when update
                                          `(:patch (rel-req ~update ~req)))

                                      ~@(when delete
                                          `(:delete (rel-req ~delete ~req)))

                                      :options {:headers {"Allowed" ~allowed}}

                                      :else {:status 405 :headers {"Allowed" ~allowed}}))))) rels))))))
