---
title: Expose bibliographic apis over mcp
status: final
targetVersion: 0.1.0
tags:
- mcp
- api-client
HAS_INTERFACE:
- interface:interface-001-mcp-stdio-protocol.md
- interface:interface-002-configuration-and-secrets.md
HAS_PLAN:
- plan:plan-007-api-test-coverage.md
HAS_TEST:
- testscript:test-001-mcp-protocol-smoke-test.md
- testscript:test-002-api-behaviour-tests.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
- main:java/uk/co/terminological/bibliography/mcp/McpServer.java
- main:java/uk/co/terminological/bibliography/mcp/McpTools.java
---

A downstream agent can query several bibliographic APIs through one MCP
server using a single unified record shape, without knowing which API
answered. Stateless tools cover search, fetch, citation links, id mapping and
PDF reference extraction; the server merges results from all configured APIs
and deduplicates by identifier.

Tools: `search`, `fetch`, `cites`, `cited_by`, `map_ids`,
`references_from_pdf`, `citation_styles`.

