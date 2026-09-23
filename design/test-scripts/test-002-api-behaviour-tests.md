---
status: final
target-version: 0.1.0
tags: api-client, citations, analysis, parsing
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Scripts

### 1) Citation network and analysis end to end

 1. Setup: a live `BiblioFacade` with a real secrets file and a disk cache.
 2. Execution: `collectSearch`, `fetchRecords`, `citesReferences`,
    `addSingle`, `expandUpCitationGraph`, `expandDownCitationGraph`,
    `fetchMissingRecords`, then `Analysis.pageRank`.
 3. Expectation: records, ids and citation links all increase; page rank
    returns a score per record uuid; `save`/`load` round trips the session to
    a `.ser` file and the restored session reports the same counts.
 4. Cleanup: process exits; cache persists for cheaper reruns.

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestCombinedApi.java)
<!-- @optional: Paths to the test implementation -->

Notes: this is a `main()`-method harness, not a JUnit test — it hits live APIs
and is therefore deliberately not executed by surefire. Cache-backed reruns
make it cheap. Transient per-API failures are expected and tolerated by
design.

### 2) Third-party response parsing

 1. Setup: recorded JSON payloads inline, a Jackson mapper with the Jdk8 module.
 2. Execution: parse the current PMC IDConverter response shape into
    `PMCIDResult`, and OpenCitations Index v2 citation rows into
    `OpenCitationsCitation`.
 3. Expectation: the drifted fields parse (hyphenated `response-date`, object
    `request`, numeric `pmid`, `requested-id`); a PID-list `citing`/`cited`
    value yields the DOI; a PID list with no DOI yields an incomplete
    `CitationLink` (the documented limitation behind
    [issue-001](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)).
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestApiDriftParsing.java)

### 3) Record model: type, publisher, provenance

 1. Setup: recorded payloads inline, a Jackson mapper with the Jdk8 module.
 2. Execution: build records from CrossRef and Europe PMC payloads (lite
    `pubType` string and core `pubTypeList.pubType` array), merge records with
    different provenances, build an ISBN-only record, and serialise a record for
    MCP (`McpTools.recordJson`).
 3. Expectation: native vocabularies map to the CSL vocabulary
    (`journal-article`→`article-journal`, `book-chapter`→`chapter`, a systematic
    review→`review`), an unknown type passes through unchanged, `containerTitle`
    falls back to `journal`, every contributing provenance survives the merge,
    an ISBN-only record reports `IdType.ISBN`, and the MCP JSON carries
    `publicationType`/`publisher`/`provenance` while omitting absent fields.
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestRecordModel.java)

### 4) Record merging: predictable and robust

 1. Setup: stub records carrying provenance, authors and a PDF location, merged
    in both insertion orders.
 2. Execution: merge two or three records and compare the resolved title,
    abstract, journal, date, authors, identifiers, licences and provenance.
 3. Expectation: the same inputs give the same result regardless of order; the
    documented per-field priority decides each field; a lower-priority source
    still fills a gap; authors are de-duplicated (by ORCID when present); unions
    are used for identifiers and licences; a curated value is never overridden by
    an API value but API values fill its gaps; and merging an already-merged
    record absorbs its contributions instead of flattening them.
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestRecordMerge.java)

## Features

- [HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
- [HAS_FEATURE](/design/features/feat-002-explore-citation-networks-in-a-session.md)
- [HAS_FEATURE](/design/features/feat-003-analyse-citation-graphs-and-topics.md)
- [HAS_FEATURE](/design/features/feat-006-classify-records-by-type-with-provenance.md)
- [HAS_FEATURE](/design/features/feat-007-predictable-record-merging.md)

## Interfaces

- [HAS_INTERFACE](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
