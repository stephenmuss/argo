(ns example.api
  (:require [argo.core :refer [defapi defresource CreateRequest]]
            [schema.core :as s])
  (:import java.util.UUID))

(defonce -artists (atom (array-map)))
(defonce -albums (atom (array-map)))

(def NewArtist
  (CreateRequest :artists {:name (s/named s/Str "Artist name is required")}))

(def NewAlbum
  (let [artist-rel {:name :artist :type :artists :foreign-key :artist-id}]
    (CreateRequest :albums
                   {:name (s/named s/Str "Name is required")
                    :label (s/named s/Str "Label is required")
                    :year (s/named s/Int "Year is required")
                    :catalog_number (s/named s/Str "Catalog number is required")}
                   artist-rel)))

(defresource artists
  {:primary-key :id

   :find (fn [req]
           {:data (-> -artists deref vals)})

   :create (fn [req]
               (if-let [errors (s/check NewArtist (:body req))]
                 {:errors errors}
                 (let [id (str (UUID/randomUUID))
                       artist (-> req :body :data :attributes (assoc :id id))]
                   (swap! -artists assoc id artist)
                   {:data artist})))

   :get (fn [req]
          {:data (@-artists (-> req :params :id))})

   :rels {:albums {:type [:albums]}}})

(defresource albums
  {:get (fn [req]
          {:data (@-albums (-> req :params :id))})

   :find (fn [req]
           {:data (-> -albums deref vals)})

   :create (fn [req]
             (let [errors (s/check NewAlbum (:body req))]
               (if errors
                 {:errors errors}
                 (let [id (str (UUID/randomUUID))
                       artist-id (-> req :body :data :relationships :artist :data :id)
                       album (-> req :body :data :attributes (assoc :id id :artist-id artist-id))]
                   (if-not (@-artists artist-id)
                     {:errors {:data {:relationships {:artist {:data {:id (str "An artist with id " artist-id " does not exist")}}}}}}
                     (do
                       (swap! -albums assoc id album)
                       {:data album}))))))

   :delete (fn [req]
             (swap! -albums dissoc (-> req :params :id)))

   :rels {:artist {:type :artists
                   :foreign-key :artist-id
                   :get (fn [req]
                          {:data (@-artists (:artist-id (@-albums (-> req :params :id))))})}}})

(defapi api
  {:resources [artists albums]})
