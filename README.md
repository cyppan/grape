# Grape

[![Clojars Project](https://img.shields.io/clojars/v/grape.svg)](https://clojars.org/grape)
[![CircleCI](https://circleci.com/gh/cyppan/grape.svg?style=shield)](https://circleci.com/gh/cyppan/grape)
[![codecov](https://codecov.io/gh/cyppan/grape/branch/master/graph/badge.svg)](https://codecov.io/gh/cyppan/grape)
[![Dependencies Status](https://jarkeeper.com/cyppan/grape/status.svg)](https://jarkeeper.com/cyppan/grape)

A Clojure library designed to build data-first APIs.
This library is a work in progress.

## Features

Everything is built around the concept of **resources**
You can see a resource as a super-powered collection of document. 

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
* pre-fetch: to map the query
* post-fetch: to modify the fetched documents
* ...

From that resources configurations Grape will expose on demand 
a fully functional REST API (Done), and a Relay-compliant GraphQL Server (TODO)

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
