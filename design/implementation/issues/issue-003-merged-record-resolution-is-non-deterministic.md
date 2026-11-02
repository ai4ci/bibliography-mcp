---
title: Merged record resolution is non deterministic
status: closed
sinceVersion: 0.1.0
targetVersion: 0.2.0
tags:
- model
- merge
- data-integrity
TESTED_BY:
- test:java/uk/co/terminological/bibliography/record/TestRecordMerge.java
---

## Issue desc

When the same work is described by several APIs, `MergedRecord` is supposed to
present a single source of truth — but it resolves conflicting field values
arbitrarily:

- `getTitle/getAbstract/getJournal/getDate/getPdfUri` use
  `stream().flatMap(...).findAny()` over records collected into an `UNORDERED`
  collector, so the value returned depends on insertion/stream order;
- `getAuthors()` concatenates the authors of every contributing record, with
  `//TODO: resolve duplicates`, so a work described by two APIs reports its
  authors twice;
- `merge()` itself carries `//TODO: Check for duplicates`.

Nothing downstream may depend on the result, which undermines any statement the
framework makes about a work. Fixed up front (developer, 2026-09), independently
of the Zotero path — where duplicates are acceptable because Zotero detects them
and gives the user a UI to resolve them.

Reproduction: `TestRecordMerge` demonstrates that swapping insertion order
changes the returned title, and that a shared author appears twice.

This was first recorded as deferred debt, which understated it: a merge that
depends on insertion order is a defect, not a trade-off.

## Reproduced by

[TestRecordMerge.java](/src/test/java/uk/co/terminological/bibliography/record/TestRecordMerge.java)

## Resolution

Fixed by [feat-007](/design/features/feat-007-predictable-record-merging.md) /
[plan-003](/design/implementation/plans/plan-003-record-merge-policy.md): deterministic
per-field priority resolution, author de-duplication, unions for list-valued
fields, and curated values treated as authoritative. Fixed a second defect found
while doing so: merging a `MergedRecord` into another flattened it to a single
record, silently discarding every contribution but the first.


[feat-007](/design/features/feat-007-predictable-record-merging.md) /
[plan-003](/design/implementation/plans/plan-003-record-merge-policy.md).
