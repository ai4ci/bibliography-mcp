---
title: Session persistence uses java serialization
status: open
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

