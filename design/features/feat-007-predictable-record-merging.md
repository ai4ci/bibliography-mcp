---
status: draft
target-version: 0.2.0
tags: model, merge, accepted
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

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

## Linked artefacts

- [HAS_PLAN](/design/implementation/plans/plan-003-record-merge-policy.md)

## Related records

- [issue-003](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)
