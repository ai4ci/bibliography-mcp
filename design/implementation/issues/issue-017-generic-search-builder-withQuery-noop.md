---
title: Generic search builder withquery noop
status: closed
sinceVersion: 0.0.6
targetVersion: 0.0.11
tags:
- bug
- search
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/TestGenericSearchBuilder.java
---
## Issue desc

`BiblioFacade.GenericSearchBuilder.withQuery(Query)` does not store the
passed query — the method body is empty:

```java
@Override
public GenericSearchBuilder withQuery(Query qry) {
    return this;  // this.qry is never assigned
}
```

The builder always returns the same null `qry` to `execute()`, which then
delegates to `biblioFacade.search(this.qry)`. If `this.qry` is `null`, the
call probably throws an NPE or returns zero results depending on what the
clients do with a null Query.

### Impact

- Any code path using `GenericSearchBuilder.withQuery(qry).execute()` gets
  no results instead of the intended search.
- No existing tests exercise this path (all callers go through
  `BiblioFacade.search()` directly), so the bug has not surfaced.


```java
@Override
public GenericSearchBuilder withQuery(Query qry) {
    this.qry = qry;
    return this;
}
```

## Closed

Fixed in 0.0.11 with exactly the one-line change above: `this.qry = qry`.

Pinned by `TestGenericSearchBuilder.withQueryKeepsTheQueryItWasGiven`, written
and proven to fail first. The test reads the builder's field rather than calling
`execute()`, because the only other way to observe the stored query is a live
search, and a one-line contract should not need the network to hold it.
