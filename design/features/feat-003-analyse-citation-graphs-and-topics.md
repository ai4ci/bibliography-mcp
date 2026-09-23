---
status: final
target-version: 0.1.0
tags: mcp, analysis
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

A downstream agent can rank the records in a session by page rank over the
citation graph (joined with titles), and fit an LDA topic model over the
session's titles and abstracts, returning topic/document weights. Both
operate on whatever the session has collected, so they follow the citation
network workflow.

Tools: `page_rank`, `topic_model`.

## Linked artefacts

- [HAS_INTERFACE](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
- [HAS_TEST](/design/test-scripts/test-002-api-behaviour-tests.md)

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/Analysis.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpTools.java)
