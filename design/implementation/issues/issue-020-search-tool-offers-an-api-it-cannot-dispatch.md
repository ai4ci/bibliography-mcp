---
class: issue
id: issue-020-search-tool-offers-an-api-it-cannot-dispatch.md
title: Search tool offers an api it cannot dispatch
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
tags:
- bug
- mcp
- search
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
