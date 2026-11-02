---
class: issue
id: issue-020-search-tool-offers-an-api-it-cannot-dispatch.md
title: Search tool offers an api it cannot dispatch
status: closed
sinceVersion: 0.0.6
targetVersion: 0.0.11
tags:
- bug
- mcp
- search
TESTED_BY:
- test:java/uk/co/terminological/bibliography/mcp/TestMcpToolSurface.java
---
## Issue desc

The `search` tool's schema lets an agent restrict the search to named APIs:

```json
"apis": {"type": "array", "items": {"enum": ["crossref", "entrez", "europepmc", "openalex", "medrxiv"]}}
```

`BiblioFacade.dispatchSearch` knows four of those five. Asking for `medrxiv`
logs `No search available for API: medrxiv` and returns nothing, and because the
tool passes the list straight to `withSearchApis`, `["medrxiv"]` is a search
that can only ever return zero results.

Pre-existing: the enum has named medrxiv since the tool was written, and the
dispatch has never had a branch for it. Found while adding `openalex` to the same
enum, which is why the fourth entry works and the fifth does not.

The honest fix is to remove it rather than to add a branch.
[issue-015](issue-015-preprint-html-search-is-blocked-by-cloudflare.md)
established that medRxiv's own search interface is unreachable — Cloudflare serves
a challenge to any programmatic client — and that Europe PMC indexes both preprint
servers essentially completely, so preprint search is already covered by an API
that works. Offering `medrxiv` as a search target advertises a route that cannot
exist.

## Suggested fix

Drop `medrxiv` from the enum in `McpTools`, and say in the tool description that
preprints are searched through Europe PMC. Leave `dispatchSearch` alone: an
unknown name already logs and returns empty, which is the right behaviour for a
name the schema no longer offers.

A test that the schema's enum and the dispatcher's branches are the same set would
stop the two drifting again; `TestMcpToolSurface` is where it belongs.

## Closed

Fixed in 0.0.11, and by removing the second list rather than by removing one
entry from it. `BiblioFacade.searchableApis()` is now the only statement of what
can be searched: `defaultApis().get("search")` returns it, and the tool's schema
builds its `apis` enum from it, so the enum cannot name an API the dispatcher
has no branch for. The tool description says preprints are searched through
Europe PMC.

Pinned by `theSearchToolOffersExactlyTheApisTheFacadeCanSearch` and
`medrxivIsNotOfferedAsASearchApi` in `TestMcpToolSurface`, written and proven to
fail first.
