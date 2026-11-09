---
class: issue
id: issue-027-pdf-merge-priority-names-a-source-that-never-answers.md
title: PDF merge priority names a source that never answers
status: open
sinceVersion: 0.1.0
targetVersion: unassigned
tags:
- bug
- merge-policy
---
## Issue desc

`MergePolicy.standard()` ranks the `PDF` field over three sources:

```java
PDF,
List.of(
  Provenance.OPENALEX,
  Provenance.EUROPEPMC,
  Provenance.CROSSREF
),
```

One of the three cannot contribute and two that can are missing.

**CrossRef never answers.** Both CrossRef record types return an empty location
unconditionally — `CrossRefWork.getPdfUri()` and
`CrossRefReference.getPdfUri()` are each `return Optional.empty();`. Its place
in the list is dead, and `CrossRefClient` carries a
`// TODO: get pdf using content negotiation and a PdfFetcher...` saying why:
the capability was intended and never built.

**Entrez and medRxiv answer and are not listed.** `EntrezEntry.getPdfUri()`
builds `https://www.ncbi.nlm.nih.gov/pmc/articles/<pmcid>/pdf` and
`MedrxivSearchEntry.getPdfUri()` builds
`https://www.<server>.org/content/<doi>v<n>.full.pdf`. Neither provenance
appears in the `PDF` list, so `MergePolicy.rank` returns `Integer.MAX_VALUE`
for them.

The consequence is mild rather than severe, because `rank` is deliberately
forgiving — "an unknown source ranks last rather than being dropped" — so an
unlisted location is still used when nothing better exists. What is lost is the
*ordering*: two real sources are ranked behind a source that has nothing, and
the table no longer documents what it claims to document. For posted content
the preprint rule already overrides this (`PREPRINT_SERVER_RANK = -1` puts a
medRxiv `.full.pdf` ahead of OpenAlex), which is almost certainly the right
answer on the merits — a preprint server serves a robot where a publisher
often will not — but it arrives from a rule about preprints generally, not from
anything the `PDF` row says.

Pre-existing. Found while tracing the PDF path for
[feat-010](/design/features/feat-010-crawl-engine-with-zotero-mirror.md); related
to, but distinct from, the unmeasured `MergePolicy`/OpenAlex loose end in
`PROGRESS.md`.

## Suggested fix

Drop `Provenance.CROSSREF` from the `PDF` list and add `ENTREZ` and the three
preprint servers, ordered by what is likely to serve a programmatic client:

```java
PDF,
List.of(
  Provenance.OPENALEX,
  Provenance.EUROPEPMC,
  Provenance.ENTREZ,
  Provenance.MEDRXIV,
  Provenance.BIORXIV,
  Provenance.ARXIV
),
```

Worth settling first, because it is a judgement and not a bug: whether a PMC
`/pdf` URL should outrank OpenAlex's `best_oa_location`.
[prototype-003](/design/prototypes/prototype-003-zotero-attachment-paths.md)
measured a PMC `/pdf` URL returning *nothing at all* and an arXiv URL returning
a real 6.3 MB file, which argues for leaving OpenAlex first and placing Entrez
below it. That is one probe of each, so the ordering below OpenAlex is a guess
until someone measures a sample.

If CrossRef is meant to answer one day, the `TODO` in `CrossRefClient` is the
other half of this and would make the existing row correct instead of removing
it.
