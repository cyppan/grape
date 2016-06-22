# Grape

[![Clojars Project](https://img.shields.io/clojars/v/grape.svg)](https://clojars.org/grape)
[![CircleCI](https://circleci.com/gh/cyppan/grape.svg?style=shield)](https://circleci.com/gh/cyppan/grape)
[![codecov](https://codecov.io/gh/cyppan/grape/branch/master/graph/badge.svg)](https://codecov.io/gh/cyppan/grape)
[![Dependencies Status](https://jarkeeper.com/cyppan/grape/status.svg)](https://jarkeeper.com/cyppan/grape)

A Clojure library designed to build data-first APIs.
This library is a work in progress, stay tuned ;)

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

From that resources configurations Grape will expose on demand: 
* A fully functional REST API (Done)
* A real time subscription end-point as a websocket (TODO)
* A Relay-compliant GraphQL Server (TODO)
* A Falcor backend (TODO)

**Examples:**

You'll find in the `examples` folder Grape showcases for:

* A comment thread with users, comments and likes
* The same project implemented with Component (lifecycle management)


## What's Implemented for the moment

* Full REST Support
* MongoDB backend


## Roadmap

* Support different datasources by resource
* Real time subscriptions (probably using MongoDB tailable cursors + core.async channels with websocket)
* GraphQL + Relay support
* Falcor support


## License

Distributed under the Eclipse Public License 1.0 (EPL-1.0)
