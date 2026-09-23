---
status: open
since-version: 0.1.0
target-version: 0.2.0
tags: model, merge, data-integrity
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

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

Supersedes [debt-004](/design/implementation/debt/debt-004-merged-field-resolution-has-no-policy.md),
which recorded this as deferred debt when it should have been a defect.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestRecordMerge.java)

## Fix

[feat-007](/design/features/feat-007-predictable-record-merging.md) /
[plan-003](/design/implementation/plans/plan-003-record-merge-policy.md).
