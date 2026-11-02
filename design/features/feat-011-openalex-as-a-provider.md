---
title: OpenAlex as a provider
status: final
targetVersion: 0.1.0
tags:
- openalex
- citations
- open-access
- api-drift
HAS_PLAN:
- plan:plan-013-openalex-client.md
HAS_PROTOTYPE:
- prototype:prototype-002-openalex-cost-and-record-shape.md
HAS_TEST:
- testscript:test-002-api-behaviour-tests.md
HAS_INTERFACE:
- interface:interface-002-configuration-and-secrets.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/openalex/OpenAlexClient.java
- main:java/uk/co/terminological/bibliography/api/openalex/OpenAlexRecord.java
- main:java/uk/co/terminological/bibliography/api/openalex/OpenAlexListResult.java
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
- main:java/uk/co/terminological/bibliography/record/MergePolicy.java
---

## Why

OpenAlex does, in one API and one id space, what currently costs this project
four clients: search, record fetch, forward and backward citation traversal, and
open-access discovery. It is also the retirement path for Unpaywall, which is
slow enough to fail against our own timeout
([issue-016](/design/implementation/issues/issue-016-unpaywall-preprint-doi-not-resolved.md)),
answers one question, and has no citation data at all.

The cost model is the part that changes designs elsewhere, so it is measured
rather than assumed: see
[prototype-002](/design/prototypes/prototype-002-openalex-cost-and-record-shape.md).
Headline: **a singleton by DOI is free**, and it already contains the work's
`referenced_works`, its `cited_by_count`, and its id cross-references. A
downward citation crawl therefore costs nothing per node in OpenAlex credits,
while an upward one costs 1 credit per seed. The daily free budget on the
developer's key is 10,000 credits ≈ 1,000 searches.

That single fact is why this is written up before the crawl engine is built
rather than after: plan-011 proposed a *call-count* ceiling, and against a
free-singleton source the useful ceiling is credits read back from the response
headers.

## What it maps onto

The `client/` SPI is already the right shape; this is a client like the others,
not a new abstraction.

| SPI | OpenAlex | measured |
|---|---|---|
| `Searcher` | `GET /works?filter=title_and_abstract.search:…` | 10 credits/call — registered in 0.0.11 behind a credit reserve, see [plan-013](/design/implementation/plans/plan-013-openalex-client.md) |
| `RecordFetcher` | `GET /works/doi:…`, `/works/W…` | **free**, batchable via `filter=doi:A\|B` at 1 credit for up to `per_page` records |
| `CitesMapper` (down) | `referenced_works` inside the record | **free** |
| `CitedByMapper` (up) | `GET /works?filter=cites:W…` | 1 credit/call |
| `IdMapper` | the `ids` block: `openalex`, `doi`, `mag`, `pmid`, `pmcid` | free, and broader than the PMC IDConverter in use |
| OA discovery | `open_access`, `best_oa_location.pdf_url` | free |

Two roles it takes over and one it does not:

- **Unpaywall → retire.** `UnpaywallClient implements RecordFetcher` only; its
  `getUnpaywallByDoi` feeds the PDF-URL resolution the Zotero clean-up path uses.
  `best_oa_location.pdf_url` is the same information, on a free call. Retire by
  registering OpenAlex in `BiblioFacade` for OA resolution and deleting the
  client once the clean-up report and tests pass without it — that order, so a
  coverage regression shows up as a test failure and not as a silent loss of
  PDFs.
- **OpenCitations → keep.** OpenAlex covers fewer citation edges for humanities
  and social-science literature than OpenCitations does, and this project's
  corpus is policy and social science. Not a candidate for replacement; both
  register, `MergePolicy` and the dedupe handle the overlap.
- **Content download → do not use.** OpenAlex will serve a cached PDF for 10
  credits (100/day). `best_oa_location.pdf_url` points at the publisher's own
  PDF, which `PdfFetcher` fetches directly at no cost. Buying a cached copy we do
  not need is a way to spend the entire daily budget on 100 PDFs.

## Model changes, and they are small

- `record/IdType` gains `OPENALEX`. Everything else already flows through
  `Record.getOtherIdentifiers()`.
- `abstract_inverted_index` must be reassembled into text before it reaches
  `Record.getAbstract()`. Mechanical; the trap is that reading the field naively
  gives a `Map<Integer,List<String>>` and the compiler will not tell you.
- Container title is nested (`primary_location.source.display_name`), like Europe
  PMC core. See issue-012 for what assuming flat costs.
- `publication_date` is a real ISO date. OpenAlex does not have issue-014's
  problem, which is a useful baseline to have in the mix.

## Budget handling

The API returns its own accounting on every response:
`X-RateLimit-Credits-Used`, `-Remaining`, `-Limit`, and `Retry-After`. Requests
over budget are refused **before running, and refusals are free**.

So the client should:

1. Send the key as `Authorization: Bearer …`, **not** as an `api_key` query
   parameter. Verified that both authenticate. The reason is our own caching:
   `CachingApiClient.keyFromApiQuery` hashes the URL *and* the parameter map, so
   a key in the query string becomes part of every cache key, splits the cache
   between authenticated and anonymous runs, and writes a secret into the cache
   directory.
2. Read `X-RateLimit-Remaining` and stop rather than retry when it hits zero.
3. Surface credits-used in the crawl job's `callsPerApi`, so workflow 4 reports
   "spent 340 of 10,000 credits" rather than "made 4,000 requests".

`openalex-apikey` and `openalex-daily-credits` already exist in
`~/.private/apis/secrets.prop`; both need a row in
[interface-002](/design/external-interfaces/interface-002-configuration-and-secrets.md).
Without a key the budget is 1/10th, but singletons stay free — worth stating in
the docs, because it means the free-singleton crawl still works unauthenticated.

## Non-goals

- Replacing Crossref or OpenCitations.
- OpenAlex authors, institutions, sources, topics or funders as first-class
  entities. `topics`/`primary_topic` may eventually replace the LDA in
  `analyse`, and `prag` may want the same fields; neither is a reason to model
  them now.
- Auto-creation of an OpenAlex account or key.
- Reconstructing the OA *location* history. `best_oa_location` is enough.

## Success criteria

- OpenAlex registered in `BiblioFacade` as `Searcher`, `RecordFetcher`,
  `CitesMapper`, `CitedByMapper`, `IdMapper`, and dedupes against the existing
  sources rather than duplicating them in `search` output.
- Offline contract tests pin the record translation from a recorded fixture, and
  a live test pins the cost headers, both under
  [test-002](/design/test-scripts/test-002-api-behaviour-tests.md).
- `UnpaywallClient` deleted, with the Zotero clean-up report and PDF resolution
  still passing.
- A depth-1 downward crawl of 25 seeds costs under 100 credits and the job
  reports what it spent.

## Risks

- **Commercial terms.** Prices and the free budget are a business decision, not
  a physical law; they changed shape as recently as October 2026. Mitigation is
  header-driven budgeting, so the client notices before the crawl does.
- **Search semantics.** `search=` as a parameter resolved to
  `fulltext.search` in the probe, which is not what `Query.getAllFields()` means
  elsewhere in this project. Getting this wrong makes search quietly worse, not
  broken.
- **Batch URL length.** OR-filters are the economy of this API, and 100 ids in a
  URL is long. Not probed; plan-013 must.
