---
status: draft
target-version: 0.4.0
tags: arxiv, medrxiv, preprints
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

<!-- acts as a description of what will be implemented to allow review and sign-off -->

## Feature

[HAS_FEATURE](/design/features/feat-009-preprint-clients.md) — which carries the
capability matrix measured against the live APIs, and the reasoning for what is not
attempted (arXiv references).

## Implementation steps:

- [ ] Recorded fixtures first, because both APIs have shapes worth pinning:
      arXiv Atom for a search, for `id_list`, and for an **error entry**; medRxiv
      `/details` and `/pubs` JSON; a JATS excerpt with a `<ref-list>` (option B).
- [ ] `IdType.ARXIV` (safe: enums deserialise by name) and `PublicationTypes`
      mapping both servers to `posted-content`.
- [ ] `ArxivClient implements Searcher, RecordFetcher`: Atom parsing, `search_query`
      construction (term → `all:`, `from`/`to` → `submittedDate`), `id_list` batching
      keyed by the requested reference, and error-entry detection that fails loudly
      rather than returning no results.
- [ ] `MedrxivClient implements Searcher, RecordFetcher`: date-window listing with
      cursor paging, `/details/{server}/{doi}`, `/pubs` as a preprint→published
      mapping. Both `medrxiv` and `biorxiv` servers.
- [ ] Rate limits and etiquette: arXiv token bucket at one request per three
      seconds, `User-Agent` set, batches of ≤2000, and caching (its results change
      once a day).
- [ ] Wire into `BibliographicApis` (getters) and `BiblioFacade.defaultApis()`
      (`search`/`fetch`/`idMap` lists) so the MCP tools and the Zotero clean-up
      report gain preprint coverage without a new tool.
- [ ] Zotero write mapping for preprints: `repository` for the container and
      `archiveID` for the arXiv id, with a test.
- [ ] Optionally (option B) medRxiv references from the JATS full text, reviving the
      `jats` package for a real purpose.
- [ ] Docs: README supported-APIs list, skill (preprint searching), SCOPE,
      `feat-009` status, roadmap idea 6 (which asked whether the medRxiv client is
      still needed — the answer is that it is, for coverage and for the published
      mapping).

## Tests

- [ ] `TestArxivParsing` (offline): a search response, an `id_list` fetch, version
      handling, `arxiv:doi`/`journal_ref` captured, an error entry rejected, abstract
      whitespace and LaTeX left as-is but unwrapped.
- [ ] `TestMedrxivParsing` (offline): `/details` version list (the same DOI has
      several versions), `/pubs` mapping, cursor paging, a missing preprint.
- [ ] Both: the record mapping honours `posted-content` and the server as container.
- [ ] Live verification, read-only: a keyword search on arXiv, a DOI fetch on
      medRxiv, and a preprint→published mapping. No writes.

## Verification

- [ ] `mvn -o test`, `./scripts/mcp-smoke-test.sh`, `Rscript …/design-check.R -d .`

## Open questions

- Should `search` on medRxiv honour a keyword by filtering the date window
  client-side, or only work as a date-window listing (with Europe PMC as the keyword
  route)? The API supports only the latter honestly.
- Is a preprint→published mapping worth exposing as a first-class id-mapping
  provider (it feeds record merging and the clean-up report), or is it enough that
  the mapping rides along in `fetch`?
- arXiv versions: fetch the latest, or support `vN` in an `IdType.ARXIV` value? The
  manual is explicit that `id_list` handles versions properly.
