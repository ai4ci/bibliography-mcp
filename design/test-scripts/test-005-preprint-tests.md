---
status: final
target-version: 0.4.0
tags: medrxiv, preprint, jats
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Scripts

### 1) medRxiv records and references (offline)

 1. Setup: sanitised fixtures — one version of a `/details` response, and a JATS
    article with a three-entry `<ref-list>` (one reference with a DOI, one without,
    one to another preprint).
 2. Execution: map the JSON to a `Record`; parse the JATS into a `JatsEntry`; take its
    citations.
 3. Expectation: a preprint is `posted-content`, its container is the server, it has
    no publisher and no PDF (the full text is XML); markup is cleaned out of title and
    abstract; authors split on the server's `"Surname, Initials;"` convention with an
    institutional author kept whole; the `published` DOI is *not* an identifier,
    because the published article is a different work; only `10.1101/` DOIs are worth
    a request; each JATS reference becomes a citation from the preprint, with its
    position, and a reference without a DOI is still a (incomplete) citation.
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/medrxiv/TestMedrxivRecords.java)

### 2) Citations from two sources are one citation (offline)

 1. Setup: stub citation links sharing a source.
 2. Execution: dedupe.
 3. Expectation: the same target from two APIs collapses; different targets are kept
    apart; references distinguished only by title are not merged; and links with
    neither identifier nor title are **kept**, because collapsing them merges
    distinct citations (which happened in a live run: 31 references became 17).
 4. Cleanup: none — offline.

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/mcp/TestCitationDedupe.java)

### 3) Live, read-only

 1. Setup: a medRxiv DOI (`10.1101/2021.02.09.21250937`) and the MCP server.
 2. Execution: `cites` and `cited_by` with that DOI.
 3. Expectation: the log shows the JATS yielding its references (31 for that
    preprint) **and** Europe PMC yielding its own 31 for the same work by resolving
    the DOI to `PPR285723`; the merged, deduplicated result contains them (80 distinct
    links in the recorded run), each with an incrementing citation index rather than
    every one claiming position 0; `cited_by` finds the works that cite it. Also
    exercised: `fetch` by the same DOI.
 4. Cleanup: none.

## Features

- [HAS_FEATURE](/design/features/feat-009-preprint-clients.md)
