---
title: Predictable record merging
status: final
targetVersion: 0.2.0
tags:
- model
- merge
- accepted
HAS_PLAN:
- plan:plan-003-record-merge-policy.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/entrez/EntrezEntry.java
- main:java/uk/co/terminological/bibliography/record/MergePolicy.java
- main:java/uk/co/terminological/bibliography/record/MergedRecord.java
- main:java/uk/co/terminological/bibliography/api/openalex/OpenAlexRecord.java
---

## Description

When several discovery APIs describe the same work, the merged record must
present **one predictable source of truth**: the same inputs must always yield
the same title, abstract, authors, date and PDF, regardless of the order in
which APIs answered.

Today it does not — field resolution is arbitrary and authors are duplicated
([issue-003](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)).

Scope:

- **Applies to discovery APIs** (CrossRef, Europe PMC, Entrez, Unpaywall) — the
  core citation-graph use case depends on a single coherent statement per work.
- **Does not apply to curated records.** A Zotero item is the user's own object;
  it is never merged into an API-derived record. Duplicates there are acceptable
  because Zotero detects them and provides a UI to resolve them.
- Requires [feat-006](/design/features/feat-006-classify-records-by-type-with-provenance.md):
  provenance is what identifies a curated record and what lets a conflict report
  name its sources.

Policy in outline (detail in
[plan-003](/design/implementation/plans/plan-003-record-merge-policy.md)): a
documented **per-field priority order** over sources instead of `findAny()`,
**unions** for identifiers/authors/licences/citations with author de-duplication,
retention of every contributing record so disagreements can be reported rather
than hidden, and exclusion of curated records from merging.

## A Zotero record is not authoritative either

An earlier draft of this feature made a curated value win unconditionally. That
was wrong, and the library data shows why: `libraryCatalog` names a *publisher
web page* for 240 of a 300-item sample (ScienceDirect, www.nature.com, Springer
Link, PLoS Journals, Silverchair, …) — metadata scraped by the Zotero connector —
and `dateModified` differs from `dateAdded` on 89/300. Neither tells you whether
an item was scraped and left broken or was hand-repaired afterwards, and no other
field does either. So a Zotero record is ranked by the same per-field policy as
every other source, and its provenance is retained so the item it came from
remains identifiable.

Curation therefore belongs to the **write** path, not to field precedence:

- never overwrite a Zotero item with API-fetched values;
- where the API data **agrees** with the item, additional fields may be filled in;
- where it **disagrees**, import the API record as a new item and let Zotero's own
  duplicate detection and the user resolve it.

See [feat-005](/design/features/feat-005-zotero-curated-repository-bridge.md).

## Implemented by


Provenance was added to `UnpaywallResult` and `EntrezEntry` because the policy
cannot rank a source that does not report one — without it the documented
preference for Unpaywall's open-access PDF locations could never take effect.
Their native publication types remain unparsed.


- [issue-003](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)
