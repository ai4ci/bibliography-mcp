---
status: final
target-version: 0.1.0
tags: mcp, api-client
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

A downstream agent can query several bibliographic APIs through one MCP
server using a single unified record shape, without knowing which API
answered. Stateless tools cover search, fetch, citation links, id mapping and
PDF reference extraction; the server merges results from all configured APIs
and deduplicates by identifier.

Tools: `search`, `fetch`, `cites`, `cited_by`, `map_ids`,
`references_from_pdf`, `citation_styles`.

## Linked artefacts

- [HAS_INTERFACE](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
- [HAS_INTERFACE](/design/external-interfaces/interface-002-configuration-and-secrets.md)
- [HAS_TEST](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
- [HAS_TEST](/design/test-scripts/test-002-api-behaviour-tests.md)
- [HAS_PLAN](/design/implementation/plans/plan-007-api-test-coverage.md)

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpServer.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpTools.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/BiblioFacade.java)
