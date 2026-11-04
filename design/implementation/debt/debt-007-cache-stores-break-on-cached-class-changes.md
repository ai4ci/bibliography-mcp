---
title: Cache stores break on cached class changes
status: closed
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- cache
- serialization
IMPACTS:
- main:java/uk/co/terminological/bibliography/BinaryData.java
- main:java/uk/co/terminological/bibliography/CachingApiClient.java
---
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


## Closed — 0.2.0

An unreadable cache is now cleared and rebuilt rather than fatal.

`CachingApiClient.openCache` catches the failure from `init()`, says what it
could not open and why, empties the directory and builds a fresh store. The
cache is disposable — everything in it can be fetched again — so the whole cost
of a format change is the calls it had saved.

The failure is only recovered for a **persistent** cache. An in-memory one has
no stale bytes to blame, so a failure there is real and still thrown.

Proving it needed a reproduction that does not involve two builds. EHCache keeps
the serialized `ObjectStreamClass` descriptors for every cached value type under
`value-serializer/holder-*.bin`, which is the file named in this record's stack
trace, so `TestCacheResilience` damages that file and asserts both that the
warning was logged — otherwise the test proves nothing — and that the rebuilt
cache stores and returns a value afterwards.

The record's second suggestion, a cache-format version in the directory name, is
**not** taken. It would leave the old directory on disk to be found later by
whoever is cleaning up, which trades a loud startup failure for a quiet disk
leak; clearing in place is the honest version of the same idea.

The workaround it documents — reading `BinaryData.byteArray` directly rather
than adding an accessor — is no longer load-bearing, but it costs nothing and
the comment at the call site now says so.
