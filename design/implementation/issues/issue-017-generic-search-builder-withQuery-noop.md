---
status: open
since-version: 0.0.6
target-version: 0.1.0
tags: bug, search
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

### Suggested fix

```java
@Override
public GenericSearchBuilder withQuery(Query qry) {
    this.qry = qry;
    return this;
}
```
