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

- [x] Recorded fixtures first, because both APIs have shapes worth pinning:
      arXiv Atom for a search, for `id_list`, and for an **error entry**; medRxiv
      `/details` and `/pubs` JSON; a JATS excerpt with a `<ref-list>` (option B).
- [ ] `IdType.ARXIV` and the arXiv client generally — not started. and `PublicationTypes`
      mapping both servers to `posted-content`.
- [ ] `ArxivClient implements Searcher, RecordFetcher`: Atom parsing, `search_query`
      construction (term → `all:`, `from`/`to` → `submittedDate`), `id_list` batching
      keyed by the requested reference, and error-entry detection that fails loudly
      rather than returning no results.
- [x] `MedrxivClient implements RecordFetcher, CitesMapper`: `/details/{server}/{doi}`
      for both servers (latest version), and references from the JATS full text. The
      date-window Searcher and the `/pubs` mapping are still to do.
- [x] Rate limits: a token bucket, and a raised read timeout — the shared 3s default
      times out on api.biorxiv.org, which looks exactly like "this preprint has no
      data" (the same trap as OpenCitations; see debt-009).
- [ ] arXiv etiquette: token bucket at one request per three seconds, `User-Agent`,
      batches of ≤2000, and caching (its results change once a day).
- [x] Wired into `BibliographicApis` and `BiblioFacade.defaultApis()`
      (`search`/`fetch`/`idMap` lists) so the MCP tools and the Zotero clean-up
      report gain preprint coverage without a new tool.
- [ ] Zotero write mapping for preprints: `repository` for the container and
      `archiveID` for the arXiv id, with a test.
- [x] Option B: references from the JATS full text, which revived the `jats` package
      and turned up two defects in it — `ImmutableCitationLink` dropped the citation
      index it was handed, and `JatsEntry.getTitle()` matched `article-title` anywhere,
      including in the reference list, so it found none.
- [x] Deduplicate citations: the same reference now arrives from two sources for a
      preprint, and unresolved endpoints must be kept rather than collapsed onto a
      shared "unknown" key (which turned 31 references into 17 in a live run).
- [x] Merge priority decided and implemented: for a `posted-content` record a
      preprint server outranks the indexed services (the server owns its own record
      and updates first), and for anything else the usual order stands.
- [x] Fixed the Europe PMC gap found while verifying: its citation methods had no
      `DOI` branch at all, so a preprint's references were reported as absent even
      though its API held them (`PPR`). Also fixed the citation index counter, which
      was never incremented in any of those loops.
- [ ] Docs: README supported-APIs list, skill (preprint searching), SCOPE,
      `feat-009` status, roadmap idea 6 (which asked whether the medRxiv client is
      still needed — the answer is that it is, for coverage and for the published
      mapping).

## Tests

- [ ] `TestArxivParsing` (offline): a search response, an `id_list` fetch, version
      handling, `arxiv:doi`/`journal_ref` captured, an error entry rejected, abstract
      whitespace and LaTeX left as-is but unwrapped.
- [x] `TestMedrxivRecords` and `TestCitationDedupe` (offline): `/details` version list (the same DOI has
      several versions), `/pubs` mapping, cursor paging, a missing preprint.
- [ ] Both: the record mapping honours `posted-content` and the server as container.
- [x] Live verification: `cites` on a medRxiv DOI returned 49 links, including the 31
      references extracted from its JATS. Still to verify: arXiv and the /pubs mapping.

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
