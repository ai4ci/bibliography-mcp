---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: cache, serialization
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: since-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Debt desc

Any change to the shape of a class that is **stored in the persistent cache**
invalidates every existing cache store, and the failure is fatal rather than
degraded: `BibliographicApis.create` throws while opening the cache manager, so
the MCP server cannot start until someone deletes the cache directory.

Observed 2026-09: adding a single method to `BinaryData` (a convenience
`size()`) changed its *computed* `serialVersionUID`, and opening a cache written
minutes earlier failed in
`org.ehcache.impl.internal.store.heap.holders...FileBasedStateRepository.loadMaps`
with a Java deserialisation error. The exception surfaced from
`BibliographicApis.create`, not from a cache operation, so it reads as a
configuration problem rather than a stale cache.

`BinaryData` is the only value type stored in the cache (keys are `String`, and
API JSON is cached as raw bytes), so it is the only class with this exposure —
but records saved by `Session` have the same fragility for a different reason
(see [debt-001](/design/implementation/debt/debt-001-session-persistence-uses-java-serialization.md)).

Workaround used: read `BinaryData.byteArray` directly instead of adding an
accessor.

Cheapest useful steps, none built: make an unreadable cache non-fatal — catch the
open failure, warn, and start with an empty store (or clear and recreate it);
consider a cache-format version in the directory name so a format change lands in
a new directory instead of colliding; document that the cache is disposable.

## Impacts

- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/CachingApiClient.java)
- [IMPACTS](/src/main/java/uk/co/terminological/bibliography/BinaryData.java)
