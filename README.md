# argo

[![Clojarsroject](http://clojars.org/argo/latest-version.svg)](http://clojars.org/argo)

Argo is a Clojure library to help build APIs in Clojure that conform to the [JSON API 1.0 specification](http://jsonapi.org/format/).

The library is still in its early stages and does not yet fully adhere to the specification. However, most of the main areas of the spec
are already covered.

## Resources

### Basics

To create a new resource use argo's `defresource` macro.

```clojure
(require '[argo.core :refer [defresource]])

(defresource heroes {})
```

The above is the minimum required to define a new resource. Although in this case only `OPTIONS` requests would be allowed
at the endpoint `/heroes`. In order to allow the usual CRUD operations the following should be implemented:

* `:find`: This will allow GET requests at `/heroes` in order to return a response of many heroes.
* `:get`: Implementing this allows for a GET request to be made at `/heroes/:id` in order to be able to retrieve a single hero.
* `:create`: Allows for POST requests to be made at `/heroes` in order to add or create new heroes.
* `:update`: Implementing this will allow for PATCH requests to be sent to `/heroes/:id` in order to update an existing hero.
* `:delete`: DELETE requests will be allowed at `/heroes/:id` in order to remove or delete an existing hero.

Each operation expects its implementation to be a function with a ring request object as its sole argument.

Argo expects as the result of these functions a map with the following keys:

* `:data`: The data which will be returned in the API response. This should be a map for a single resource (as implemented with `:get`) or a vector/sequence for `:find`.
* `:errors`: A map of keywords and string values. Will be converted to the JSON API error format. Works well with Prismatic schema error types.
* `:status`: Use to override the status of responses. Defaults to 400 for error responses and 200 for valid responses.
* `:exclude-source`: Use this to exclude the source object as per the JSON API spec in error responses.
* `:count`: argo provides automatic generation of pagination links if using pagination for `:find`. Use `:count` to let argo know how many total objects exist when implementing pagination.

In most circumstances it will probably only be necessary to include either `:data` or `:errors`.

To expand on the previous example:

```clojure
(require '[argo.core :refer [defresource]])

(defresource heroes {
  :find (fn [req]
          {:data (heroes/get-all)})

  :get (fn [req]
         {:data (heroes/get-one (-> req :params :id))})  ; will raise 404 if :data is nil

  :create (fn [req]
            (if-let [errors (heroes/validate-new (:body req))]
              {:errors errors}
              {:data (heroes/new (:body req))}))

  :update (fn [req]
            (if-let [errors (heroes/validate-update (:body req))]
              {:errors errors}
              {:data (heroes/update (-> req :params :id) (:body req))}))

  :delete (fn [req]
            (when-let [errors (heroes/delete (-> req :params :id))]
              {:errors errors}))})
```

### Relationships

In order for argo to be able to generate relationship links it is necessary to implement relationships with the key `:rels`.

`:rels` is a map of maps. They key used in the map will be that which is used for url generation.
Relationships may have the following keys.

* `:type`: (required) indicates the relationship type. Use a string or keyword for to-one relationships and a vector of a single keyword or string for to-many relationships.
* `:foreign-key`: For to-one relationships this should match the key in the parent resource in order for the key to be removed from the JSON API attributes object.
* `:get`: Implement in order to be able to allow get requests to the relationships endpoint.
* `:create`: Implement this in order to create a new relationship.
* `:update`: Updates an existing relationship.
* `:delete`: Remove an existing relationship.

Generally, only errors should be returned for relationship requests. The expected response will usually be a 204 No Content response.

Extending the heroes example let's create an `:achievements` relationship to our resource. This will create endpoints at `/heroes/:id/achievements`.

```clojure
  :rels {:achievements {:type [:achievements]

                        :get (fn [req]
                               {:data (heroes/get-achievements (-> req :params :id))})

                        :create (fn [req]
                                  (if-let [errors (heroes/validate-achievement (:body req))]
                                  {:errors errors}
                                  (heroes/add-achievement (-> req :params :id) (:body req))))

                        :update (fn [req]
                                  (if-let [errors (heroes/validate-achievement-update (:body req))
                                    {:errors errors}]
                                    (heroes/update-achievement (-> req :params :id) (:body req))))

                        :delete (fn [req]
                                  (when-let [errors (heroes/remove-achievement (-> req :params :id) (:body req))]
                                    {:errors errors}))}}
```

## APIs

In order to create a new API use argo's defapi macro. This can then be set as your main ring handler.

It is possible to use the following keys in the api implementation:

* `:resources` a vector or sequence of the resources to include in the api.
* `:middleware` a vector or sequence of ring middleware to include on all endpoints.
* `:base-url` can be used to prepend a base-url to all urls generated.

```clojure
(require '[argo.core :refer [defapi]]
         '[myapi.resources :refer [heroes achievements]])  ; assuming a resource has been created for both heroes and achievements using defresource.

(defapi my-api {:resources [heroes achievements]})
```

In this case, we could now add `my-api` as the ring handler in our project.clj file.

## Example

An [example implentation](https://github.com/stephenmuss/argo/blob/master/example/src/example/api.clj) is provided.

To run the example locally use `lein example`.

Let's add some data:

```
curl -XPOST \
  localhost:3000/v1/heroes \
  -H Content-Type:application/vnd.api+json \
  -d '
  {
    "data": {
      "type": "heroes",
      "attributes": {
        "name": "Jason",
        "birthplace": "Iolcos"
      }
    }
  }'; echo
```

You should see the following response:

```javascript
{
    "data": {
        "attributes": {
            "birthplace": "Iolcos",
            "created": "2015-07-09T06:42:33Z",
            "name": "Jason"
        },
        "id": "1",
        "links": {
            "self": "/v1/heroes/1"
        },
        "relationships": {
            "achievements": {
                "links": {
                    "related": "/v1/heroes/1/achievements"
                }
            }
        },
        "type": "heroes"
    }
}
```

Now enter

```
curl -XPOST \
  -H Content-Type:application/vnd.api+json \
  localhost:3000/v1/achievements \
  -d '
  {
    "data": {
      "type": "achievements",
      "attributes": {
        "name": "Acquisition of the Golden Fleece"
      },
      "relationships": {
        "hero": {
          "data": {"type": "heroes", "id": "1"}
        }
      }
    }
  }'; echo
```

This should return the following reponse.

```javascript
{
    "data": {
        "attributes": {
            "created": "2015-07-09T06:25:23Z",
            "name": "Acquisition of the Golden Fleece"
        },
        "id": "1",
        "links": {
            "self": "/v1/achievements/1"
        },
        "relationships": {
            "hero": {
                "links": {
                    "related": "/v1/achievements/1/hero"
                }
            }
        },
        "type": "achievements"
    }
}
```

You should now be able to make GET requests to the following endpoints and receive responses with data.

* `/v1/heroes`
* `/v1/heroes/1`
* `/v1/heroes/1/achievements`
* `/v1/achievements`
* `/v1/achievements/1`
* `/v1/achievements/1/hero`

You can experiment with adding more data and trying other request methods such as `OPTIONS`, `PATCH` and `DELETE`.
