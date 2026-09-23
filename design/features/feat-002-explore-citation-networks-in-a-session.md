---
status: final
target-version: 0.1.0
tags: mcp, citations, session
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

A downstream agent can build and traverse a citation network across multiple
tool calls: search results are collected into a stateful in-memory repository,
records are merged across id schemes, the citation graph is expanded
upwards (citing) and downwards (references) with a caller-supplied size
limit, and the whole session can be saved to and restored from disk.

Tools: `collect_search`, `collect_search_by_api`, `add_records`,
`remove_records`, `find_alternate_ids`, `fetch_missing`,
`expand_citation_graph`, `session_status`, `session_records`,
`session_citations`, `save_repository`, `load_repository`.

## Linked artefacts

- [HAS_INTERFACE](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
- [HAS_TEST](/design/test-scripts/test-002-api-behaviour-tests.md)

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/Session.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpTools.java)
