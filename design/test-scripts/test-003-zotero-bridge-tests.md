---
title: Zotero bridge tests
status: final
targetVersion: 0.2.0
tags:
- zotero
- mcp
- integration
HAS_FEATURE:
- feature:feat-005-zotero-curated-repository-bridge.md
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/zotero/TestZoteroItemWriter.java
- test:java/uk/co/terminological/bibliography/api/zotero/TestZoteroLibraryIndex.java
- test:java/uk/co/terminological/bibliography/api/zotero/TestZoteroMapping.java
---

### 1) Mapping and matching (offline)

 1. Setup: sanitised fixtures modelled on the real payload shapes (same fields,
    invented values) so the developer's library is not committed; a stub record for
    the other side of a match.
 2. Execution: map one item of each type (`journalArticle`, `report`, `book`,
    `bookSection`, `webpage`); build a library index and look records up in it;
    render records as new-item JSON.
 3. Expectation: CSL types and containers are right (a chapter gets its book title,
    a report its institution, a book its ISBN); only the token `author` creators
    count as authors, with editors as a fallback for edited books; a year-only date
    becomes Jan 1 and unparseable text becomes no date; provenance carries the
    Zotero vocabulary and the item key; matching works on DOI with or without a
    resolver prefix, on ISBN with hyphens, and on normalised title+year for the
    30.8% of the library with no identifier at all; an item with no title is
    refused rather than created.
 4. Cleanup: none — offline.

[TestZoteroMapping.java](/src/test/java/uk/co/terminological/bibliography/api/zotero/TestZoteroMapping.java)
[TestZoteroLibraryIndex.java](/src/test/java/uk/co/terminological/bibliography/api/zotero/TestZoteroLibraryIndex.java)
[TestZoteroItemWriter.java](/src/test/java/uk/co/terminological/bibliography/api/zotero/TestZoteroItemWriter.java)


 1. Setup: the Zotero desktop client running with the local API enabled; a
    properties file whose `zotero-write-collection` is the sanctioned test
    collection `api-test`.
 2. Execution, read: `zotero_status`, `zotero_collections`, `zotero_import` a real
    collection into the session, `zotero_check` the session.
 3. Expectation: 2060 items and 128 collections reported; the import brings the
    collection's records in with no citation links (Zotero has no citation graph);
    every imported record is reported as already in the library — it came from
    there — by DOI or title+year.
 4. Execution, write: `zotero_publish` a session whose records the library already
    holds, then one with genuinely new records and `limit` set.
 5. Expectation: the all-held publish creates nothing **and leaves no empty
    sub-collection behind**; the second creates only the new items, in a
    sub-collection of `api-test`, tagged, reporting how many were already held and
    how many were left for a next call; no item is refused.
 6. Cleanup: the created sub-collections are deleted by hand in Zotero (the bridge
    has no delete).

Recorded outcomes are in [feat-005](/design/features/feat-005-zotero-curated-repository-bridge.md).

