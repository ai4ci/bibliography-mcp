---
title: Analyse citation graphs and topics
status: final
targetVersion: 0.1.0
tags:
- mcp
- analysis
HAS_INTERFACE:
- interface:interface-001-mcp-http-transport.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/mcp/BiblioTools.java
---
A downstream agent can rank the records in a session by page rank over the
citation graph (joined with titles), and fit an LDA topic model over the
session's titles and abstracts, returning topic/document weights. Both
operate on whatever the session has collected, so they follow the citation
network workflow.

Tools: `page_rank`, `topic_model`.


## Superseded — 0.2.0

Page rank survives, reimplemented over the durable store as
`repository/Ranking.java` and reachable as `rank(method=page_rank)` — that half
of the description is now
[feat-010](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)'s.

Topic modelling does not survive. `topic_model` was one of the twenty-nine tools
the 0.2.0 surface reduction retired, and its implementation (`Analysis`) became
unreachable when `Session` did. The code is preserved, with a description of
what reinstating it would mean, on the `feature/topic-modelling` branch and in
[feat-012](/design/features/feat-012-topic-modelling-over-the-repository.md).

This record is superseded rather than closed: its page-rank half shipped, its
topic half is a branch someone may want.
