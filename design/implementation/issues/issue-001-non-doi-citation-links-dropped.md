---
status: open
since-version: 0.1.0
target-version: 0.2.0
tags: opencitations, citations, data-loss
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

OpenCitations Index v2 returns citing and cited entities as space-separated
PID lists (`omid:br/... doi:... openalex:...`).
[OpenCitationsCitation.doiFromPids](/src/main/java/uk/co/terminological/bibliography/opencitations/OpenCitationsCitation.java)
extracts the `doi:` token and returns `null` when there is none, which makes
the `CitationLink` incomplete, and
[Session.add(CitationLink)](/src/main/java/uk/co/terminological/bibliography/mcp/Session.java)
silently skips incomplete links. Citations to entities that have no DOI
(books, chapters, non-DOI venues) are therefore lost, and the loss is
invisible to the caller.

Proposed fix: [feat-004](/design/features/feat-004-opencitations-meta-enrichment.md).
Related: [debt-002](/design/implementation/debt/debt-002-opencitations-queries-dois-only.md).

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/pubmedclient/TestApiDriftParsing.java)
