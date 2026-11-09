---
class: issue
id: issue-029-downloaded-pdfs-share-a-cache-sized-by-entry-count.md
title: Downloaded PDFs share a cache sized by entry count
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
tags:
- caching
- memory
- crawl
---
## Issue desc

`PdfFetcher.getPdfFromUrl` stores whole PDFs in the shared `forever` cache,
whose heap tier is bounded by **number of entries** rather than by bytes:

```java
.withCache(
  "forever",
  configure(
    ResourcePoolsBuilder.heap(10000)
      .disk(400, MemoryUnit.MB, true),
    LONG_LIVED
  )
)
```

Every other client in the project puts API responses there — JSON and XML
measured in kilobytes — and for them 10,000 entries is a sensible bound.
`PdfFetcher` uses the same cache via `cacheForever()` and puts in the largest
thing the project downloads: `prototype-003` measured one arXiv PDF at **6.3 MB**.
The entries are `BinaryData`, so the bytes are held on heap, not streamed.

Two consequences, neither observed yet:

1. **The heap tier has no byte ceiling.** A crawl that downloads enough PDFs can
   fill it with large entries. 10,000 × 6.3 MB is far beyond any ordinary heap;
   the real limit is whichever comes first, the entry count or the JVM.
2. **The disk tier silently evicts what the entry count permits.** The cache
   directory is capped at 400 MB, so roughly sixty PDFs of that size fill it and
   the rest are evicted — which is correct behaviour for a cache, but it means
   the 180-day `LONG_LIVED` expiry is not what governs how long a downloaded PDF
   survives. A caller reasoning from the expiry will be wrong about PDFs.

Both tiers are shared with the metadata clients only in *configuration*, not in
storage: `BibliographicApis.create` gives the fetcher its own directory
(`cacheDir.resolve("pdf")`) and therefore its own `CacheManager`, so a PDF
cannot evict a Crossref response. The problem is that one configuration is used
for two kinds of payload with a thousandfold size difference.

Pre-existing, and mild in current use: the crawl writes real files through
`PdfSink` for anything that matters, and a cached blob is only a saved
re-download. Found by reading the EHCache configuration while tracing the PDF
path, **not** by observing a failure — nobody has run a crawl large enough to
test it.

## Suggested fix

Give PDFs their own cache rather than resizing the shared one, since the shared
numbers are right for what they were chosen for:

```java
.withCache(
  "pdf",
  configure(
    ResourcePoolsBuilder.heap(32, MemoryUnit.MB)
      .disk(2, MemoryUnit.GB, true),
    LONG_LIVED
  )
)
```

and have `PdfFetcher` ask for that cache. A byte-sized heap tier requires
EHCache to size entries, which for a `byte[]`-backed `BinaryData` is cheap and
accurate.

The cheaper alternative, if a second cache name is not wanted, is to **not cache
PDF bodies at all** and let `PdfSink`'s output directory be the cache — a file
already on disk is what both consumers (Zotero, an indexer) actually want, and
`PdfSink.pathFor` makes the name deterministic, so "have I got it?" is a
`Files.exists` rather than a cache lookup. That removes a tier instead of tuning
one, at the cost of re-downloading when the pdf directory is cleared.

Worth measuring before either: how much is actually in the pdf cache directory
after the crawl runs this project has done. If it is a handful of megabytes,
this stays a note.
