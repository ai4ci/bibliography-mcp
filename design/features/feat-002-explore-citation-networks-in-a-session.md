---
title: Explore citation networks in a session
status: final
targetVersion: 0.1.0
tags:
- mcp
- citations
- session
HAS_INTERFACE:
- interface:interface-001-mcp-stdio-protocol.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/mcp/McpTools.java
- main:java/uk/co/terminological/bibliography/mcp/Session.java
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

