---
status: draft
target-version: 0.4.0
tags: arxiv, medrxiv, preprints, proposed
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Description

Cover the two preprint servers properly: **medRxiv/bioRxiv** (one client, both
servers) and **arXiv**. Today the medRxiv client is a stub and the arXiv API is not
used at all, even though the developer's library holds 37 medRxiv and 5 bioRxiv
items, and preprints are now a significant part of the literature.

The work is mostly wiring, because the abstractions already fit: both clients
implement `Searcher` and `RecordFetcher`, register in `BiblioFacade` so the
existing MCP tools (`search`, `fetch`, `collect_search`, `expand_citation_graph`)
and the Zotero clean-up report pick them up with no new tool surface.

## What each source can actually do

Measured 2026-09 against the live APIs, because the shape of the data decides the
design — and it contradicts one of the assumptions in the request.

| capability | medRxiv / bioRxiv (`api.biorxiv.org`) | arXiv (`export.arxiv.org/api/query`) |
|---|---|---|
| **Searcher** | date-window listing `/details/{server}/{from}/{to}[/{cursor}]` (authoritative, paginated by cursor, 30–100 per page). Keyword search exists only as **HTML scraping** (`MedrxivSearch`, already written) — prefer Europe PMC, which indexes preprints as `source: PPR` | proper keyword search: `search_query` with field prefixes (`ti`, `au`, `abs`, `co`, `jr`, `cat`, `rn`, `all`), `AND`/`OR`/`ANDNOT`, quoted phrases, and a `submittedDate:[…TO…]` filter that matches our `from`/`to`; `sortBy`/`sortOrder` |
| **RecordFetcher** | by DOI: `/details/{server}/{doi}` → title, authors, corresponding author + institution, version, type, license, category, funder, abstract, `jatsxml` URL, `published` | by `id_list` (comma-separated, versions via `vN`); also `search_query`+`id_list` together as a filter |
| **preprint → published mapping** | explicit: `/pubs/{server}/{doi}` → `preprint_doi`, `published_doi`, `published_journal`, both dates | `arxiv:doi` and `arxiv:journal_ref` on the entry — present only if the author supplied them, so weaker |
| **references (CitesMapper)** | **only from the JATS full text**: the `jatsxml` URL returns the article XML and it carries the reference list (sample: 28 `<ref>` in one `<ref-list>`). No API for it | **not available at all** — the API returns metadata only, and scraping PDFs/HTML is out of scope under arXiv's terms |
| **cited-by** | none | none |
| **citations, either direction** | Europe PMC has them for indexed preprints (sample `PPR285723`: **31 references, 10 citations**), and OpenCitations once published | same |

So: **all three interfaces are possible for medRxiv, but `CitesMapper` only via the
full-text JATS**; for arXiv, `CitesMapper` is not possible and should not be
attempted. Citation data for preprints continues to come from Europe PMC and
OpenCitations, which the facade already routes by DOI — the gap the preprint
clients fill is *metadata* (and, for medRxiv, the published-version link).

## Design

- **`ArxivClient implements Searcher, RecordFetcher`.** Atom 1.0 parsed with
  fluentxml, in the style of the existing clients. `search(term, from, to, limit)`
  maps to `search_query=all:<term>` plus `submittedDate:[from TO to]`; `fetch(ids)`
  maps to `id_list` in batches, keyed by the requested reference so results merge
  with the other APIs. `arxiv:doi`/`arxiv:journal_ref` become other identifiers and
  a published-version mapping where present.
- **`MedrxivClient implements Searcher, RecordFetcher`** for real: the date-window
  listing (cursor-paged) as the search, `/details` for fetch, `/pubs` as the
  preprint→published mapping. The existing HTML-scraping classes are left alone:
  they work, but the API is authoritative and Europe PMC is the better keyword route.
- **`IdType.ARXIV`** — arXiv ids are the canonical handle for a preprint and DOIs
  (10.48550/arXiv.*) are optional; adding an enum constant is safe for serialised
  sessions (enums deserialise by name).
- **Record mapping**: `publicationType` = `posted-content`, container/repository =
  the server name, `PlainText` on title and abstract (arXiv abstracts carry LaTeX
  and hard line breaks), provenance `source` = `arxiv` / `medrxiv` with the server's
  own vocabulary as `nativeType`.
- **Zotero write mapping** needs two additions: a preprint item type uses
  `repository` (not `publicationTitle`) for the container, and the arXiv id belongs
  in `archiveID`. Without this, publishing an arXiv preprint creates a Zotero item
  with no repository and no identifier.
- **Etiquette, and it is not optional for arXiv**: a 3-second delay between requests,
  a `User-Agent` identifying this client, `max_results` ≤2000 per slice (≤30000
  total, >30000 is an HTTP 400), and — explicitly documented — **results only change
  once a day**, so answers must be cached. That suits the existing
  `CachingApiClient` EHCache and token bucket, and argues for small batches.
- **Ambiguous success is failure.** arXiv returns errors as an **HTTP 200 Atom feed
  with a single entry** whose id contains `/api/errors#`. Treating that as "no
  results" is precisely the mistake of
  [issue-004](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md),
  so the parser detects it and fails loudly.

## Implemented so far (option B, medRxiv)

- `MedrxivRecord` and a real `MedrxivClient` (`RecordFetcher` + `CitesMapper`), wired
  into `BiblioFacade`'s `fetch` and `cites` lists so the existing tools gained
  preprint coverage with no new tool surface.
- References come from the preprint's JATS full text: **31 references extracted for
  the sample preprint**, live.
- Merge priority puts the preprint servers below the indexed services, as requested:
  a PMC record of the same work wins, and a preprint supplies only what the indexed
  services lack.
- The reference parser now resolves the reference list properly. Measured on a real
  medRxiv article (28 references): **all 28 are identifiable** by title or DOI,
  where before 14 were, and titles were effectively absent — the parser gated the
  title on `publication-type == "journal"`, a **string identity comparison**, so it
  matched nothing. A reference's name is now its `article-title`, or the `collab`
  element the publisher uses for reports and web pages ("Investigation of novel
  SARS-CoV-2 variant", "Prime Minister announces Tier 4"), or the source it appeared
  in; elsewhere PMIDs travel as other identifiers, and a non-numeric year ("in press")
  yields no date instead of throwing.

Four pre-existing defects were turned up along the way, all fixed:
`ImmutableCitationLink` accepted a citation index and dropped it; `JatsEntry.getTitle()`
matched `article-title` outside the front matter, so a JATS article's own title was
lost whenever its reference list had titles too; `JatsReference.getTitle()` compared
strings by identity, so no reference had a title; and `JatsReference.getDate()` threw
on a year that was not a number.

- **Search** is a **date window**, because that is what the API offers: it lists what
  was posted between two dates, cursor-paged, and has no keyword search at all. A term
  is therefore applied client-side, requiring **every word** to appear in the title or
  abstract — a phrase-as-substring filter made a two-word query return nothing in a
  window holding 1498 preprints. With no dates the last 30 days are used, and when a
  window holds more than the page budget the log says how much was scanned ("300 of
  1498 records"), so a partial answer cannot look like an empty one.
- **The published version** is read from `/pubs` and exposed as an **id mapping**, so
  `map_ids` answers "was this preprint published?" with the journal DOI. A session
  holding both will therefore treat them as one work: right for a citation graph, and a
  deliberate choice recorded here rather than a side effect.
- Zotero's preprint item type holds its container in **`repository`** rather than
  `publicationTitle`, so the writer now maps it there and the clean-up report looks
  there — without it a published preprint had no repository and its container was
  reported as a gap on every item.

**Also fixed, in the other citation sources** (they were the source of the links with
neither an identifier nor a title): Crossref reference entries often carry only an
`unstructured` citation string, which is now the title, and no longer claim `IdType.MID`
when they have no identifier; OpenCitations PID lists with no `doi:` token keep their
`omid` handle as an identifier, so the link is complete rather than dropped; and Europe
PMC's unmatched reference rows carry `unstructuredInformation`, which is now read.
Measured live: links with neither identifier nor title went from **33 of 80 to 0**, and
the total fell to 64 because they now deduplicate properly.

Still to do: the arXiv client (parked — a different shape of source) and, with it,
Zotero's `archiveID` for an arXiv identifier.

## Open question: is the PMC-first priority right for preprints?

Asked by the developer, and it is worth writing down because the answer differs
between the two things people mean by "priority".

**Citations are not suppressed.** The merge priority applies to *record fields*
(title, date, abstract, publisher, container, type, authors, licences) and never sees
a citation link. Links accumulate, and the deduplication added with this feature only
collapses the *same* source-to-target pair, keeping the richer of the two descriptions
of it. So medRxiv's 31 JATS references were added alongside Europe PMC's, and the live
run returned 49 distinct links; for that preprint Europe PMC contributed **zero**
references anyway (see below), so nothing was suppressed at all.

**Record metadata is suppressed, and that cuts the wrong way for a preprint.** A
preprint server is the source of record for its own preprint and it updates first: a
v2 with a corrected abstract or date appears there, while Europe PMC holds whatever it
captured at indexing time. With the current order a PMC record of the same work wins,
so the correction does not show through in the merged view. For a *published* work the
opposite is true and PMC is the better record, which is why the rule was asked for.

**Decided: option B.** A preprint server now ranks before every other source *for a
`posted-content` record*, and the usual order stands for a published work — pinned by
two tests, one for each direction. Implementing it exposed one more thing: the
preprint servers are only promoted for their own kind of record, so a session that
merges a preprint server's record into a *journal article* still prefers the indexed
services.

**Also fixed (found while verifying):** Europe PMC's client returned no references for
a preprint DOI, even though its API has 31 for the corresponding `PPR` record. Its
`citesReferences` and `referencesCiting` had branches for `PMID` and `PMCID` and
**none for `DOI`** — a DOI reference was ignored entirely — although its `DataSources`
enum already listed `PPR`. A DOI is now resolved to Europe PMC's own source and id
(`MED` for a journal article, `PPR` for a preprint) before its reference list is
asked for, verified live: 31 references where there were none. The same pass fixed
the loop's index counter, which was declared and never incremented, so every
reference had claimed position 0 in the four places it appeared.

## Options for the references question

| # | Option | Effort | Notes |
|---|--------|--------|-------|
| A | No `CitesMapper` for either server; rely on Europe PMC/OpenCitations for citations, and the preprint clients for metadata only | S | Smallest honest scope; preprints not indexed by Europe PMC get no references |
| B | `CitesMapper` for medRxiv from the JATS full text (`jatsxml`), with arXiv still without one | M | Gives references for *any* medRxiv/bioRxiv preprint, indexed or not. Also the concrete purpose the unused `jats` package was written for ([debt-008](/design/implementation/debt/debt-008-jats-package-is-unused.md)) — and the JATS is HighWire-flavoured, so a tolerant XPath over `.//ref-list//ref` is the way |
| C | `CitesMapper` for arXiv by scraping the HTML abstract page or PDF | L | **Rejected**: no reference data in the API, and bulk scraping is against the terms of use |

**Recommendation:** A first (metadata, published-version mapping, and the existing
citation routes), then B as its own step, since it is the only way to get
references for a preprint that Europe PMC does not index and it revives code that
already exists.

## Not in scope

- PDF retrieval and parsing (`PdfFetcher` exists; preprint full-text PDFs are a
  separate concern from metadata).
- OAI-PMH bulk harvesting: arXiv recommends it for sets over ~1000 results, and it
  is a different interface with a different shape.
- Replacing the medRxiv HTML search scraper — left in place, unused.

## References

- [arXiv API User's Manual](/docs/references/arXiv%20API%20User's%20Manual.md) —
  the parameters, paging limits, sort options, Atom fields and error behaviour this
  plan is based on. Kept outside `design/` because it is a verbatim third-party
  document rather than a design artefact, and because its external URLs would be
  read as repo-relative links by the design checker.

## Linked artefacts

- [HAS_PLAN](/design/implementation/plans/plan-006-preprint-clients.md)
- [HAS_TEST](/design/test-scripts/test-005-preprint-tests.md)

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/medrxiv/MedrxivClient.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/medrxiv/MedrxivRecord.java)
