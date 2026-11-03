---
title: Workflow shaped tool surface
status: final
targetVersion: unassigned
tags:
- mcp
- tools
- zotero
- pdf
HAS_FEATURE:
- feature:feat-010-crawl-engine-with-zotero-mirror.md
DEPENDS_ON:
- plan:plan-010-versioned-repository-store.md
- plan:plan-011-background-crawl-engine.md
---
# Plan 012 — Workflow shaped tool surface

Collapse 29 tools into 12, surface the scores that are already computed, make
structured search reachable, and give `PdfFetcher` a sink so Zotero can receive
attachments. This is the plan the developer actually feels: it is where "you
have to sequence fourteen calls correctly" stops being the interface.

Depends on plan-010 (the repository actions have somewhere to act on) and
plan-011 (the crawl tools wrap the engine). The PDF sink and the score plumbing
do not depend on either and can land first.

## Problem

`mcp/McpTools.java` is 1113 lines and 29 tools, all primitives. Three concrete
consequences, each verified in the current source:

1. **Scores are computed and thrown away.** `BiblioFacade.rrfScore` sorts the
   deduped collection, then `recordJson` emits no score field. The agent cannot
   see *why* an order was chosen, and workflow 1 ("identify high value hits")
   has no signal at search time.
2. **Structured search is unreachable.** `collect_search` builds
   `ImmutableQuery.builder().allFields(str)`, and
   `GenericSearchBuilder.withQuery` is a no-op
   ([issue-017](/design/implementation/issues/issue-017-generic-search-builder-withquery-noop.md)),
   so the tested per-API query translations in
   `src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslation.java`
   cannot be driven from a tool call. This is roadmap idea 8.
3. **No attachments exist.** `ZoteroClient` has `createSubCollection`,
   `createItems`, `updateItems`, `addTags` — no attachment item, no file
   registration. `PdfFetcher.getPdfFromUrl` returns `Optional<InputStream>` into
   the ehcache blob store and never writes a file. Workflows 2 and 7 are blocked
   here, and so is `prag`'s input.

## Goals

1. Twelve tools, each one a step of a workflow rather than a library call.
2. Every record the tools return carries the scores that were used to order it.
3. `search` accepts title / author / journal / year / date window.
4. Selection is a mark on the repository, queryable, reversible.
5. `zotero_sync` publishes the selection with metadata updates and PDF
   attachments.
6. Everything except `zotero_sync` works with Zotero closed.

## Tool surface

| tool | arguments | replaces |
|---|---|---|
| `search` | `query`, `title`, `author`, `journal`, `year`, `from`, `to`, `apis[]`, `limit`, `collect: bool` | `search`, `collect_search`, `collect_search_by_api` |
| `repository` | `action`: `status` \| `records` \| `citations` \| `add` \| `remove` \| `save` \| `load` \| `select` \| `selected` | `session_status`, `session_records`, `session_citations`, `add_records`, `remove_records`, `save_repository`, `load_repository` |
| `start_crawl` | `seeds[]` or `fromSelection: bool`, `direction`, `depth`, `maxRecords` | `expand_citation_graph` |
| `crawl_status` | `job` | *(new)* |
| `stop_crawl` | `job` | *(new)* |
| `rank` | `method`: `page_rank` \| `rrf` \| `cited_by`, `top`, `minCitations`, `onlySelected` | `page_rank` |
| `analyse` | `method`: `topics`, `stopwords[]`, `topics[]`, `alpha`, `beta` | `topic_model` |
| `enrich` | `what`: `details` \| `ids`, `limit` | `fetch_missing`, `find_alternate_ids`, `map_ids`, `fetch` |
| `zotero_sync` | `collection`, `direction`: `publish` \| `report`, `includePdf`, `limit` | `zotero_publish`, `zotero_import`, `zotero_check`, `zotero_cleanup_report` |
| `zotero` | `action`: `status` \| `collections` \| `apply_updates` \| `tag` | `zotero_status`, `zotero_collections`, `zotero_apply_updates`, `zotero_tag` |
| `pdf_references` | `path` or `url` | `references_from_pdf` |
| `citation_styles` | — | unchanged |

`fetch`, `cites`, `cited_by`, `map_ids` stop being tools. They stay public
library methods — `BiblioFacade` and the `client/` SPI keep them, and the
`java` API is the escape hatch for anything the tools do not cover. Deleting a
tool is not deleting a capability; it is refusing to make the model schedule
it.

## Work items

### A. Score plumbing (independent, do first)

1. Carry the score with the record at the tool boundary. `BiblioFacade.search`
   returns `Collection<Record>`; the RRF score is lost in that return. Either
   return a ranked type or keep a per-search score map — a small
   `ScoredRecord` wrapper at the `api` boundary, *not* a new field on `Record`,
   which would make a score look like a property of the work rather than of the
   query.
2. `recordJson` emits `score` and `matchedApis` when a score is present.
   `matchedApis` is the cheap, legible version of corroboration: 3 beats 1 even
   when the RRF float is not interesting.
3. PageRank and LDA already return scores; make their output go through the same
   `recordJson` so one code path emits scores for all three.

### B. Structured search (independent, do early)

1. Fix `GenericSearchBuilder.withQuery` — it is `return this;` and never
   assigns `this.qry`.
2. Add `title`, `author`, `journal`, `year`, `from`, `to` to the `search` schema
   and build an `ImmutableQuery` from them.
3. Decision from roadmap idea 8: `query` and `title` are **AND-combined**, not
   exclusive — record the answer in
   [roadmap](/design/roadmap/index.md) idea 8's open question.
4. The translation tests already exist; add tool-level tests that a `title`
   argument reaches each client's native syntax.

### C. PDF materialisation (independent of A/B)

1. `PdfFetcher.fetchTo(Path dest)` writing the stream to disk, returning the
   path. Keep `getPdfFromUrl` for existing callers.
2. A directory convention owned by the repository (`<repository>/pdfs/`), so
   `prag` can be pointed at a plain directory with no knowledge of this library.
3. Unpaywall → best-OA-PDF resolution is already in `UnpaywallClient`; wire
   "record → best pdf url → file" as one call.
4. **Prototype the Zotero side before writing this up as done.** Attaching a PDF
   is either one `POST /<lib>/import` call (translator, metadata + file) or
   three: create attachment item, register the file with md5, link it. Which is
   available in the developer's Zotero build is unverified. If `/import` works,
   `zotero_sync` gets simpler and the metadata work partly disappears — so
   verify first, it may delete a third of this plan.

### D. Selection

`repository(action=select, ids[], value)` writes plan-010's selection column;
`action=selected` reads it back. `rank(onlySelected)` and
`start_crawl(fromSelection)` consume it. Nothing is deleted, so the citation
graph the metrics run over stays whole and the decision is reversible.

### E. The collapse

Rename and group last, once the behaviour above exists, so the diff that changes
behaviour is separate from the diff that changes names. Update
`scripts/mcp-smoke-test.sh` in the same commit — it has never been run and will
fail on the first rename, which is the only proof it works.

## Tests

- Tool-level offline tests: `search` with `title` only produces the same
  per-API request as the equivalent `Query` (reuse the
  `TestQueryTranslation` assertions rather than duplicating them).
- `search` output contains `score` and `matchedApis`, and a record matched by
  two APIs scores above one matched at rank 1 by a single API. (That is the
  RRF property already pinned in `TestSearchDedup`; the new part is that it
  survives to JSON.)
- PDF sink writes a real file to a temp dir; Zotero attachment tests are
  `@Category(LiveTest.class)` because they need the local API, and are skipped
  without it.
- `zotero_sync` with Zotero closed fails with a message naming the port, not a
  stack trace.
- The smoke script exercises the 12-tool surface end to end.

## Open questions

- Twelve is a proposal, not a constraint. If `repository(action=…)` grows nine
  branches with nine different argument sets, an enum discriminator in a JSON
  schema is worse for a model to use than three separate tools — the win is
  fewer *sequences*, not fewer *names*. Re-evaluate after the smoke script is
  rewritten against it.
- Does `enrich` belong, or is `details`/`ids` backfill just part of what a crawl
  and a search do?
- Keep `citation_styles` as a tool at all? It answers a question about the
  library, not about the user's corpus.
- `pdf_references` overlaps with what `prag` will do to PDF content. They are
  different today (reference extraction vs. parsing and indexing) — do not merge
  them on the assumption they are the same feature, but do keep them adjacent so
  the handoff is obvious.

## Revision — six tools, not twelve

The developer's instruction is the minimum surface that supports the use case,
and this plan's own open question is the right test of it: *the win is fewer
sequences, not fewer names*. A `repository(action=…)` with nine branches and
nine argument sets fails that test as badly as nine tools do.

Twelve becomes six, and every one of the seven workflows is one or two calls:

| tool | arguments | workflow |
|---|---|---|
| `search` | `query`, `title`, `author`, `journal`, `year`, `from`, `to`, `apis[]`, `limit`, `collect` | 1 |
| `list_records` | `filter`: `all` \| `selected` \| `incomplete`, `top`, `minCitations`, `sort`, `style` | 1, 6 |
| `select` | `ids[]`, `value` | 6 |
| `crawl` | `action`: `start` \| `status` \| `stop`, `seeds[]` \| `fromSelection`, `direction`, `depth`, `maxRecords`, `job` | 3, 4 |
| `rank` | `method`: `page_rank` \| `rrf` \| `cited_by`, `top`, `minCitations`, `onlySelected` | 5 |
| `zotero_sync` | `action`: `collections` \| `report` \| `publish`, `collection`, `includePdf`, `limit` | 2, 7 |

### What the durable store deletes outright

- **`save` and `load` stop existing.** Writes land in the store as they happen,
  so there is nothing to save; and the server is started against one repository,
  so there is nothing to load. A pair of tools disappears because plan-010
  removed the reason for them, which is the best kind of surface reduction.
- **`add_records` stops existing.** Since
  [issue-023](../issues/issue-023-identifier-queries-are-text-searched.md) an
  identifier-shaped query is a lookup, so `search(query: "10.1136/bmj.n579",
  collect: true)` is how a known paper enters the repository. One behaviour
  serving two purposes rather than two tools.
- **`remove_records` stops existing.** Selection is a mark and rejected records
  stay in the graph, which is what makes PageRank meaningful and the decision
  reversible; a delete contradicts that.

### What becomes a library call rather than a tool

`enrich` (backfill is a phase of `search` and `crawl`, not a thing to schedule),
`analyse`/`topic_model` (the LDA is real but no workflow asks for it),
`pdf_references`, `fetch`, `cites`, `cited_by`, `map_ids`. Deleting a tool is
not deleting a capability — `BiblioFacade` and the `client/` SPI keep all of it.

### One deliberate removal to confirm

`citation_style` and `citation_styles` shipped in 0.1.0 and are **not** in the
six. Rendering is presentation, Zotero does citations, and no workflow asks for
one. The capability survives as a `style` argument on `list_records`: name a CSL
style and the records come back with a `citation` field, which is the same
behaviour without a tool whose only job is to set a mode. If a style is never
named, nothing renders.

That is a removal of something the developer asked for two days ago, so it is
flagged rather than assumed.

### Still true from the original plan

Scores reach the JSON (work item A), structured search reaches the clients (B —
`withQuery` was fixed in 0.1.0), the PDF sink is needed for workflows 2 and 7
(C), and selection is a column (D). The collapse (E) is now a collapse onto six
names, and it happens in the same commit as the SDK server
([plan-014](plan-014-mcp-sdk-http-server.md)) rather than before it: the
old server is deleted whole rather than renamed twice.

## Built — 0.2.0, six tools

`search`, `list_records`, `select`, `crawl`, `rank`, `zotero_sync`, in
`BiblioTools`, served by [plan-014](plan-014-mcp-sdk-http-server.md)'s
server. Thirty tools became six in one commit rather than by renaming twice: the
old surface was deleted with the server it hung off.

Work item A, the scores, is done at the source rather than at the boundary:
`BiblioFacade.scoredSearch` returns `ScoredRecord` — the record, the RRF score
and the APIs that found it — where `search` dropped all three at the return.
`RecordJson` emits `score` and `matchedApis`, and `rank` writes its scores back
to the store so `list_records` agrees with the ranking it was given.

Two deviations from the plan above, both deliberate:

- **`minCitations` and `incomplete` are not arguments of `list_records`.**
  Filtering by citation count is what `rank(method=cited_by)` answers, and
  putting it in two places would mean two implementations of the same count.
  `list_records` filters `all` or `selected`, and nothing else.
- **The citation style is an argument on `search`, `list_records` and `rank`**
  rather than a tool. Naming a style on the call that returns records says what
  `citation_style` said without a mode to set, and the static field that held it
  is gone with `McpTools`.

### Work item C is done — 0.2.0

`PdfSink` writes the file, `PdfFetcher.fetchTo` downloads into it,
`ZoteroItemWriter.toAttachment` builds the child item, and
`ZoteroClient.attachPdf` runs prototype-003's four-call protocol — confined by
`checkWriteTarget`, because an attachment inherits its place from its parent and
a child written to the wrong parent is a write outside the sanctioned collection
by another route. `zotero_sync(includePdf: true)` ties them together and reports
`pdfsAttached` with a `pdfsNotAttached` list that says *why* for each one.

Proven live against the developer's library, writing only inside `api-test`:
an item and its attachment created through the production client, Zotero
recording our md5, and a write aimed outside the collection refused by name.

**A PDF is attached for a minority of a crawl, and that is the expected state.**
`best_oa_location` points at the publisher for most records; measured the same
day, arXiv serves a PDF while BMJ answers its own `.full.pdf` with 403 and PMC
refuses. `PdfSink` rejects a body that is not a PDF, so a paywall page never
lands in the library looking like a success.

### Not built, and feat-010 says so

**Zotero attachments.** `zotero_sync` publishes metadata into a sub-collection
and reports what the library already holds; it does not attach PDFs. That is the
one piece of feat-010's seven workflows this release does not deliver.

Work item C, the PDF sink, **is** built: `PdfSink` plus `PdfFetcher.fetchTo`,
verified against a live 6.3 MB open-access PDF. What remains is the Zotero side,
and [prototype-003](../prototypes/prototype-003-zotero-attachment-paths.md)
already chose its shape — the local Web API rather than the connector protocol,
because every call then goes through a path this code constructs and
`checkWriteTarget` keeps confining writes to the sanctioned collection.

An earlier note here said that prototype was unrun and might delete a third of
this plan. Both were wrong. It ran on 2026-11-02, read-only, and it says the
optimism was misplaced: the file has to be fetched and named either way, so what
a one-call import would remove is the md5/register code, not the plan.
