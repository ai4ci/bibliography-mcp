---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: persistence, serialization
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

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

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/mcp/Session.java)
