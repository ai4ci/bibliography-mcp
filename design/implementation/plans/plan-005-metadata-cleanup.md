---
status: final
target-version: 0.3.0
tags: zotero, enrichment, metadata
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-008-clean-up-collection-metadata.md)

Terse implementation checklist. The comparison is pure and unit-tested; the write
reuses the batched, version-preconditioned write from plan-004.

## Implementation steps:

- [x] `MetadataComparison` (pure, in the zotero package): given a Zotero item's
      `data` node and a set of API records for the same work, produce, per field,
      `{zotero, apis: {source: value}, class: agree|fillable|conflict|zotero-only}`
      and an item-level summary. Fields: title, date, abstractNote, publisher,
      container, DOI, URL, creators (count and surnames only). Normalised-exact via
      `ZoteroMatching`; year-only dates compare on the year.
- [x] `ZoteroClient.updateItems(Map<String, Map<String, String>>)`: one request for up to 50 items,
      each `{key, version, <changed fields>}` — PATCH semantics, so unrelated fields
      are untouched — returning what landed, what Zotero refused, and the previous
      values for the report.: one request for up to 50 items,
      each `{key, version, <changed fields>}` — PATCH semantics, so unrelated fields
      are untouched — returning what landed and what Zotero refused.
- [x] `zotero_cleanup_report {collection, limit?}`: for each item in the collection,
      find an identifier, fetch the API records through `BiblioFacade` (one merged
      record is enough — the agent needs the values and their sources), compare, and
      return the per-item report. Items with no identifier or no API record are
      reported as such. Read-only.
- [x] `zotero_apply_updates {updates:[{itemKey, fields}]}`: validate each item is in
      the configured collection and writes are enabled, read each item for its
      version, apply, and report per item what changed with the previous values.
- [x] `zotero_publish` gains `includeHeld` (default false) so the agent can choose
      the duplicate route for a conflict it cannot resolve.
- [x] `PlainText`: markup reduced to text at the record mapping (Crossref), in the
      writer as a last gate, and in the comparison; the report flags markup already in
      the library rather than forgiving it.
- [x] Design links, `test-004`, `feat-008` status, README and skill tool lists. Also
      fixed the writer, which omitted the container entirely — items published by the
      bridge had no journal at all.

## Tests

- [x] `TestMetadataComparison` (offline, sanitised fixtures), 11 tests: agreement after
      normalisation; a fillable gap; a conflict where both sides have a value;
      year-only vs full date; an item with no identifier; several API sources for
      the same field, each attributed.
- [x] `TestZoteroItemWriter` (existing, extended): update patches and the container field name: the update object carries key,
      version and only the named fields, includes the previous value for the report,
      and refuses an empty change set.
- [x] Live verification: report on a real collection, and an update applied to an item
      created for the purpose in the test collection, read back afterwards (only the
      named fields changed; the previous values were reported) to an item
      created for the purpose inside the configured test collection, then read it
      back. Never on the user's own records without them watching.

## Verification

- [x] `mvn -o test` (70 tests), `./scripts/mcp-smoke-test.sh` (29 tools), `Rscript …/design-check.R -d .`

## Open questions

- Should the report take an optional `fields` argument so a caller can scope the
  comparison (abstracts only, say)?
- Does a "conflict" need to distinguish "differs in precision" (2019 vs 2019-01-01)
  from "differs in substance" (two different publishers)? The agent can see both
  values, so a flag may be enough — decide from the first real clean-up.
