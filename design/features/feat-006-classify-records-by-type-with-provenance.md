---
title: Classify records by type with provenance
status: final
targetVersion: 0.2.0
tags:
- model
- provenance
- zotero
- accepted
HAS_PLAN:
- plan:plan-002-record-type-publisher-provenance.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/crossref/CrossRefWork.java
- main:java/uk/co/terminological/bibliography/api/europepmc/EuropePMCLiteResult.java
- main:java/uk/co/terminological/bibliography/mcp/BiblioTools.java
- main:java/uk/co/terminological/bibliography/record/Record.java
- main:java/uk/co/terminological/bibliography/record/MergedRecord.java
- main:java/uk/co/terminological/bibliography/record/Provenance.java
- main:java/uk/co/terminological/bibliography/record/PublicationTypes.java
- main:java/uk/co/terminological/bibliography/record/Record.java
---

## Description

Give a `Record` the ability to say **what kind of work it is**, **who issued
it**, and **where the claim came from** — so that the ~25% of a curated library
that is not a journal article can be represented, ranked and published
correctly, and so a field's origin is knowable.

Today `Record` is an article-shaped citation: identity, title, abstract,
`journal` (a bare string), authors, date, one PDF URI. There is no way to
express "this is a book chapter", "this is a preprint", "this is a report
issued by an institution", or "this title was asserted by CrossRef rather than
curated by the user".

Four additive changes (detailed in [plan-002](/design/implementation/plans/plan-002-record-type-publisher-provenance.md)):

1. `Optional<String> getPublicationType()` — **CSL type vocabulary as a string**,
   not a Java enum, so unknown types round-trip.
2. `Optional<Provenance> getProvenance()` — `Provenance{source, nativeType,
   fetchedAt, url}`; `nativeType` keeps the source API's own vocabulary (Zotero
   maps `preprint` to CSL `article`, so the native value matters).
3. `IdType.ISBN` — so books and chapters are addressable at all.
4. `Optional<String> getPublisher()` — one canonical field for publisher /
   issuing body (Zotero's own CSL export maps a report's `institution` here).

Optionally `getContainerTitle()` defaulting to `getJournal()`.

## Why these four

Measured against the developer's library (2060 top-level items):
`article-journal` 75.2%, `webpage` 6.6%, `report` 4.8%, `document` 4.3% — a type
field plus a publisher field covers **90.9%** of content faithfully; the
remaining 9% is a tail needing one extra thing each (container, ISBN, version).
Decisions and the full evidence are in [roadmap idea 3](/design/roadmap/index.md).

## Not in scope

Notes, tags, collections, relations, attachments beyond a single PDF URI, full
text, child items, Zotero `itemKey` as an identity, and any merge-precedence
engine (duplicates are tolerated and resolved in Zotero). Type is deliberately
**not** added to `RecordReference` — the graph acquires types when nodes are
fetched, and only one of four citation sources carries a type at all.

## Implemented by


The Zotero-side population (itemType, institution/publisher) lands with
[feat-005](/design/features/feat-005-zotero-curated-repository-bridge.md);
Entrez `PublicationTypeList` is deliberately left unparsed.


- [issue-003](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)
