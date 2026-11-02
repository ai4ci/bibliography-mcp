---
title: Non doi citation links dropped
status: open
sinceVersion: 0.1.0
targetVersion: 0.2.0
tags:
- opencitations
- citations
- data-loss
TESTED_BY:
- test:java/uk/co/terminological/bibliography/TestApiDriftParsing.java
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
