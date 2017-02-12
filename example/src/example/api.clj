(ns example.api
  (:require [argo.core :refer [defapi defresource CreateRequest]]
            [example.db :as db]
            [schema.core :as s])
  (:import [java.lang NumberFormatException]))

(def NewHero
  (CreateRequest :heroes {:name (s/named s/Str "Hero name must be a string")
                          :birthplace (s/named s/Str "Hero birthplace must be a string")}))

(def NewAchievement
  (CreateRequest :achievements
                 {:name (s/named s/Str "Achievement name must be a string")}
                 {:name :hero :type :heroes}))

(defn parse-id
  [id]
  (try (Integer/parseInt id)
       (catch NumberFormatException t nil)))

(defresource heroes
  {:primary-key :id

   :find (fn [req]
           {:data (db/find-heroes)})

   :create (fn [req]
               (if-let [errors (s/check NewHero (:body req))]
                 {:errors errors}
                 (let [attr (-> req :body :data :attributes)]
                   {:data (db/insert-hero! (:name attr) (:birthplace attr))})))

   :get (fn [req]
          {:data (db/get-hero (-> req :params :id parse-id))})

   :delete (fn [req]
             (when (false? (db/remove-hero! (parse-id (-> req :params :id))))
               {:errors {:id "The hero with the specified id could not be found"}
                :exclude-source true
                :status 404}))

   :rels {:achievements {:type [:achievements]
                         :get (fn [req] {:data (db/find-achievements (parse-id (-> req :params :id)))
                                         :rels {:hero {:foreign-key :hero}}})}}})

(defresource achievements
  {:find (fn [req]
           {:data (db/find-achievements)})

   :get (fn [req]
          {:data (db/get-achievement (parse-id (-> req :params :id)))
           :included {:heroes (db/get-achievement-hero (parse-id (-> req :params :id)))}})

   :create (fn [req]
             (if-let [errors (s/check NewAchievement (:body req))]
               {:errors errors}
               (let [name (-> req :body :data :attributes :name)
                     hero (-> req :body :data :relationships :hero :data :id parse-id)
                     result (db/insert-achievement! name hero)]
                 (if (instance? Throwable result)
                   {:errors {:data {:relationships {:hero {:data {:id "The hero specified does not exist"}}}}}}
                   {:data result}))))

   :rels {:hero {:type :heroes
                 :foreign-key :hero
                 :get (fn [req]
                        {:data (db/get-achievement-hero (parse-id (-> req :params :id)))})}}})

(defapi api
  {:base-url "/v1"
   :resources [heroes achievements]})
