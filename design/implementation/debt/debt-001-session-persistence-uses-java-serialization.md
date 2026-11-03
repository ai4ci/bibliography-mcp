---
title: Session persistence uses java serialization
status: closed
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- persistence
- serialization
IMPACTS:
- main:java/uk/co/terminological/bibliography/mcp/Session.java
---
`Session` is persisted with plain Java serialization
([Session.save](/src/main/java/uk/co/terminological/bibliography/mcp/Session.java)).
Saved sessions are therefore brittle: adding or reordering fields, changing
the record classes, or upgrading a dependency (jgrapht, exotic-datatypes)
invalidates existing `.ser` files with an obscure `InvalidClassException`, and
the format is unreadable and untestable by inspection.

Inherited from the R-era repository, where it was equally invisible.

Acceptable while sessions are short-lived scratch state within one client
session. If saved sessions are meant to be durable (shared, versioned, or
committed), this should become JSON or another schema'd format, with a
version field and a migration/decoding error message that says what happened.


## Closed — 0.2.0

`Session` no longer implements `Serializable`, `Session.save` and
`BiblioFacade.loadSession` are gone, and nothing writes an `ObjectOutputStream`
any more.

What replaced it is not a better file format but a different thing:
[plan-010](../plans/plan-010-versioned-repository-store.md)'s `RepositoryStore`,
which is durable, append-friendly, queryable without a full load, and refuses a
file from a schema it does not know **naming both versions** — the thing an
`InvalidClassException` carrying a `serialVersionUID` could never do.

`Session` itself survives as an in-memory helper for the LDA topic model, which
is a library call rather than a tool. It holds nothing that outlives the
process, so the fragility this record is about has nowhere left to bite.

No migration reader was written. A `.ser` file from before this is already
fragile to every class change since it was written, which is what this record
says; if one exists that matters, reading it is a piece of work to schedule
rather than something to assume.
