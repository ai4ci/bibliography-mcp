---
class: issue
id: issue-028-pdffetcher-singleton-ignores-a-second-cache-path.md
title: PdfFetcher singleton ignores a second cache path
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
tags:
- bug
- configuration
- caching
---
## Issue desc

`PdfFetcher.create(Path)` is a lazy singleton that keeps the *first* cache
directory it was ever given and silently discards every later one:

```java
static PdfFetcher instance = null;

public static PdfFetcher create(Path cachePath) {
	if (instance == null)
		instance = new PdfFetcher(Optional.ofNullable(cachePath));
	return instance;
}
```

`BibliographicApis.create` calls this with `cacheDir.resolve("pdf")`, so a
second `BibliographicApis` built against a different cache directory — a second
test, or a second configuration in one process — gets a fetcher still writing
to the first directory. Nothing says so. The sibling clients built around it in
the same constructor (`CrossRefClient.create`, `EuropePMCClient.create`) take
their cache path per instance, so the inconsistency is invisible at the call
site: all three lines look alike and one of them does not mean what it says.

This is the same failure shape as
[issue-021](issue-021-secrets-are-loaded-into-system-properties.md) — a static
holding configuration that bleeds from one `BibliographicApis.create` into the
next — and it shows up the same way, as a test that passes alone and behaves
oddly beside another.

Two smaller things in the same few lines:

- **`maxRedirects` is mutable on a shared instance.** `PdfFetcher.maxRedirects(int)`
  mutates a field that the redirect-chasing `ClientFilter` reads, so one
  caller's limit applies to every other caller holding the same singleton.
- **The `cache` field is dead.** `Path cache = null;` is declared and never
  assigned or read; the real path goes to the `CachingApiClient` superclass.

Pre-existing, and not currently causing a visible failure: this project builds
one `BibliographicApis` per process in normal use, and the offline test suite
runs one Maven JVM at a time (two at once fight over the EHCache directory
anyway). Found while tracing the PDF path.

## Suggested fix

Make it an ordinary constructor, as the other clients are:

```java
public static PdfFetcher create(Path cachePath) {
	return new PdfFetcher(Optional.ofNullable(cachePath));
}
```

and delete the `instance` and `cache` fields. `BibliographicApis` already holds
the one instance anything reaches — `getPdfFetcher()` is the only public route —
so the static adds nothing a field does not already do.

Worth checking before changing: `EntrezClient.getPubMedCentralPdfByPMEntry(EntrezEntry)`
calls `PdfFetcher.create()` with no argument, which builds an **uncached**
fetcher on every call and is unaffected by this; and whether any caller depends
on two `create(Path)` calls sharing one EHCache lock on the same directory,
since two live managers over one persistence directory is exactly the
`Persistence directory already locked` failure the project already knows. If
`BibliographicApis` can be constructed twice over the same cache root, removing
the singleton turns a silent wrong-directory into a loud lock error — which is
better, but it is a behaviour change and not only a cleanup.

Making `maxRedirects` final and set at construction would remove the other
shared-mutable-state hazard at the same time; it has no callers in this
repository.
