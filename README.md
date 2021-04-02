# Grape

A Clojure library designed to build data-first APIs.

```
$ lein cloverage
|-------------------------------+---------+---------|
|                     Namespace | % Forms | % Lines |
|-------------------------------+---------+---------|
|                           dev |   31,43 |   57,14 |
|                    grape.core |   62,01 |   84,52 |
|        grape.hooks.auth-field |   91,94 |   95,31 |
|              grape.hooks.core |  100,00 |  100,00 |
|      grape.hooks.default-sort |   33,33 |   55,56 |
|      grape.hooks.inject-dates |   93,33 |  100,00 |
| grape.hooks.inject-pagination |   81,82 |   95,65 |
|             grape.hooks.oplog |   47,12 |   58,33 |
|  grape.hooks.restricts-fields |   86,18 |   94,74 |
|             grape.hooks.utils |   98,66 |  100,00 |
|                    grape.http |   57,52 |   96,23 |
|                   grape.query |   88,71 |  100,00 |
|             grape.rest.parser |   80,81 |   97,78 |
|              grape.rest.route |   84,18 |   88,78 |
|                  grape.schema |   83,73 |   89,06 |
|                   grape.store |   73,63 |   82,81 |
|                   grape.utils |   68,13 |   65,38 |
|-------------------------------+---------+---------|
|                     ALL FILES |   74,15 |   88,67 |
|-------------------------------+---------+---------|
```

## Features

Everything is built around the concept of **resources**
You can see a resource as a super-powered collection of document with graph capabilities.

A resource corresponds generally to a MongoDB collection, it's a dictionary
 configuration to expose your collection safely to the external world, providing:
* One or more Rest endpoint for your resource with a JSON DSL for fetching
* Authorization
* Soft Delete
* Function Hooks
* Relations (automatic fetching of related resources)
* Schema validations (using the powerful Prismatic Schema lib)

This library is highly functional and you can easily add features around your resources
using hooks on:
* pre-read: to map the query
* post-read: to modify the fetched documents
* pre-create-pre-validate: the payload has been parsed, but occurs before the validation
* pre-create-post-validate: the payload has been parsed and the document validated and coerced
* post-create: after being inserted into the database, synchronous (you can map the result)
* post-create-async: after being inserted into the database, asynchronous (used to feed the oplog for example)
* pre-update-pre-validate: either for update or partial update
* pre-update-post-validate
* post-update
* post-update-async
* pre-delete
* post-delete
* post-delete-async

**Hooks use cases:** 
* Synchronize a secondary database (like an Elastic Search)
* Increment counters (that comment has n like)
* Denormalize your data (Maintain an array of the 3 last replies for a comment to ensure efficient comments fetching)
* Map the users fetched to inject a Facebook graph url for their avatar
* Perform complex validation involving multiple fields / collections
* ...

**Examples:**

You'll find in the `examples` folder Grape showcases for:

* A comment thread with users, comments and likes
* The same project implemented with Component (lifecycle management)
