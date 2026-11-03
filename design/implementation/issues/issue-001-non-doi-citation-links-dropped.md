---
title: Non doi citation links dropped
status: closed
sinceVersion: 0.1.0
targetVersion: 0.2.0
tags:
- opencitations
- citations
- data-loss
TESTED_BY:
- test:java/uk/co/terminological/bibliography/TestApiDriftParsing.java
- test:java/uk/co/terminological/bibliography/api/opencitations/TestOpenCitationsIdentifiers.java
---
## Issue desc

OpenCitations Index v2 returns citing and cited entities as space-separated
PID lists (`omid:br/... doi:... openalex:...`).
[OpenCitationsCitation.doiFromPids](/src/main/java/uk/co/terminological/bibliography/api/opencitations/OpenCitationsCitation.java)
extracts the `doi:` token and returns `null` when there is none, which makes
the `CitationLink` incomplete, and
[Session.add(CitationLink)](/src/main/java/uk/co/terminological/bibliography/mcp/Session.java)
silently skips incomplete links. Citations to entities that have no DOI
(books, chapters, non-DOI venues) are therefore lost, and the loss is
invisible to the caller.

Proposed fix: [feat-004](/design/features/feat-004-opencitations-meta-enrichment.md).
Related: [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md).


[TestApiDriftParsing.java](/src/test/java/uk/co/terminological/bibliography/TestApiDriftParsing.java)

## Mostly closed — 0.2.0, and smaller than it looked

`pidFromPids` now names every scheme this project models — `doi`, `pmid`,
`pmcid`, `openalex`, `isbn` — in that order, where everything but a DOI used to
become `IdType.UNK`. An endpoint known by PMID and not by DOI arrives as a PMID
and can be fetched, crawled and deduplicated. A scheme this project has no type
for keeps its whole handle, so the link still identifies something.

**The measurement is worth more than the change.** Across four papers and 120
references:

| | |
|---|---|
| cited entities with a DOI | 117 of 120 |
| without a DOI, nameable as PMID | 2 |
| without a DOI, unnameable (`omid` + `arxiv`) | 1 |

So the premise this was partly built on — that OpenAlex handles would unlock a
lot — does **not** hold: OpenCitations pairs an `openalex:` PID with a DOI
essentially always, and 40 of 42 endpoints carrying one changes nothing, because
the DOI is preferred and is already there. What the typing actually buys is the
2-in-120 known by PMID alone. Small, correct, and worth knowing before anyone
plans around it.

### What is left

The unnameable remainder is **arXiv** (and `omid`, OpenCitations' own identity
scheme). Naming an arXiv preprint would mean an `IdType.ARXIV`, which is a
change to the model rather than to this client, and the preprint clients would
want to agree with it. Left undone deliberately; the link is kept with its full
`arxiv:1711.09883v1` handle rather than dropped, which is the part of this issue
that was about losing citations entirely.
