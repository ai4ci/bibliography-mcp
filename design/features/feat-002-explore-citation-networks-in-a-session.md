---
title: Explore citation networks in a session
status: final
targetVersion: 0.1.0
tags:
- mcp
- citations
- session
HAS_INTERFACE:
- interface:interface-001-mcp-http-transport.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/mcp/BiblioTools.java
---
A downstream agent can build and traverse a citation network across multiple
tool calls: search results are collected into a stateful in-memory repository,
records are merged across id schemes, the citation graph is expanded
upwards (citing) and downwards (references) with a caller-supplied size
limit, and the whole session can be saved to and restored from disk.

Tools: `collect_search`, `collect_search_by_api`, `add_records`,
`remove_records`, `find_alternate_ids`, `fetch_missing`,
`expand_citation_graph`, `session_status`, `session_records`,
`session_citations`, `save_repository`, `load_repository`.


## Superseded — 0.2.0

The session is gone. The twelve tools this record lists became the six of
[plan-012](/design/implementation/plans/plan-012-workflow-tool-surface.md), and
their state moved from an in-memory `Session` to the durable SQLite repository of
[feat-010](/design/features/feat-010-crawl-engine-with-zotero-mirror.md): writes
land as they happen, and there is nothing to save or load, which is why
`save_repository` and `load_repository` are among the tools that disappeared.

`Session.java` is deleted on `main` and preserved on the
`feature/topic-modelling` branch, where
[feat-012](/design/features/feat-012-topic-modelling-over-the-repository.md)
records why, because `Analysis.topicModel` read its corpus from it.
