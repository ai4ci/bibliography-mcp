---
status: final
target-version: 0.3.0
tags: zotero, enrichment, metadata
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Scripts

### 1) Comparison (offline)

 1. Setup: sanitised fixtures — a Zotero item's `data` node and one or more API
    records carrying their own provenance.
 2. Execution: compare, then read the per-field classes and the values.
 3. Expectation: equal values agree after normalisation (case, punctuation, entity
    escapes); a Zotero gap the APIs can fill is `fillable`; differing values are a
    `conflict` with both sides shown; a container is reported under the Zotero field
    name the item type uses (`publicationTitle`, `bookTitle`, …) so an update can be
    written from it; a year-only date against a full date is a conflict rather than a
    silent upgrade; markup (JATS abstracts) is not treated as content nor offered for
    writing; placeholder author names such as Unpaywall's "Unknown" are ignored while
    a real difference in authors still conflicts; creators carry a note that an
    update must supply the array itself.
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/zotero/TestMetadataComparison.java)
[TESTED_BY](/src/test/java/uk/co/terminological/zotero/TestZoteroItemWriter.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/record/TestPlainText.java)

### 2) Clean-up against a real collection (report, then write)

 1. Setup: the local API enabled and a properties file whose write target is the
    sanctioned test collection.
 2. Execution: `zotero_cleanup_report` on a collection, then `zotero_apply_updates`
    on an item created for the purpose in that collection.
 3. Expectation: the report lists per-item status (`agree`, `gaps`, `conflicts`,
    `no identifier`, `no API record`), the API sources consulted, and per field both
    values with their source; the update changes only the fields named, reports the
    previous values, and leaves the item's tags, collections and other fields alone.
 4. Cleanup: the created test sub-collections are removed by hand in Zotero.

Recorded outcomes are in
[feat-008](/design/features/feat-008-clean-up-collection-metadata.md).

## Features

- [HAS_FEATURE](/design/features/feat-008-clean-up-collection-metadata.md)
