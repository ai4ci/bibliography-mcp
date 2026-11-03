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
- main:java/uk/co/terminological/bibliography/mcp/Analysis.java
- main:java/uk/co/terminological/bibliography/mcp/BiblioTools.java
---

A downstream agent can rank the records in a session by page rank over the
citation graph (joined with titles), and fit an LDA topic model over the
session's titles and abstracts, returning topic/document weights. Both
operate on whatever the session has collected, so they follow the citation
network workflow.

Tools: `page_rank`, `topic_model`.

