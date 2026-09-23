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

## Features

- [HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
- [HAS_FEATURE](/design/features/feat-002-explore-citation-networks-in-a-session.md)
- [HAS_FEATURE](/design/features/feat-003-analyse-citation-graphs-and-topics.md)

## Interfaces

- [HAS_INTERFACE](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
