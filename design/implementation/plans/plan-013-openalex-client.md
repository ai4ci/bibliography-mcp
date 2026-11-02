---
title: OpenAlex client
status: final
targetVersion: unassigned
tags:
- openalex
- citations
- open-access
HAS_FEATURE:
- feature:feat-011-openalex-as-a-provider.md
DEPENDS_ON:
- plan:plan-008-search-deduplication.md
---
# Plan 013 — OpenAlex client

Implement `OpenAlexClient` covering the five SPI roles, register it, and retire
Unpaywall. Costs and record shape are measured in
[prototype-002](/design/prototypes/prototype-002-openalex-cost-and-record-shape.md);
the reasoning about which clients it replaces is in
[feat-011](/design/features/feat-011-openalex-as-a-provider.md). This plan is the
mechanics.

## Order of work

Deliberately sequenced so that the risky part (deleting a client) happens last
and only after the replacement is proven.

1. **Auth + budget plumbing first**, because everything else depends on the
   shape of the request. `Authorization: Bearer <openalex-apikey>`. **Not** an
   `api_key` query parameter — verified that both authenticate, and the query
   parameter would be folded into every cache key by
   `CachingApiClient.keyFromApiQuery`, which hashes url *and* params
   (`CachingApiClient.java:232`). That would split the cache between
   authenticated and anonymous runs and write a secret into the cache directory.
   This is the one decision here that would be expensive to reverse later.
2. `OpenAlexRecord implements Record` over the JSON, with the inverted-index
   reassembly and the nested container title.
3. `RecordFetcher`: singleton by DOI or OpenAlex id (free), plus the batched
   `filter=doi:A|B|…` path (1 credit for up to `per_page`).
4. `IdMapper` from the `ids` block.
5. `CitesMapper` down from `referenced_works`; `CitedByMapper` up via
   `filter=cites:`. **Use `cites:`** — the probe found
   `filter=referenced_works:W…` returns the *citing* works, identical to
   `cites:` (both count 1955 on the same seed), so the name reads backwards and
   using it would be an inversion bug waiting to happen. Citation edge
   direction is the convention established when issue-007 closed: **source
   cites target**.
6. `Searcher`, after settling `title_and_abstract.search` vs `search=` (see
   risks).
7. OA resolution: `best_oa_location.pdf_url` behind the same interface
   `UnpaywallClient` presented, so callers do not change twice.
8. Register in `BiblioFacade.defaultApis()` / `searchApis`.
9. `IdType.OPENALEX` and `Provenance.OPENALEX`.
10. Delete `UnpaywallClient`, its tests, its `Provenance.UNPAYWALL` constant, and
    its row in `interface-002`, in one commit, once 1–9 are green.

## Work items with teeth

- **Batch size is unmeasured.** OR-filters are the economy of this API, and
  100 DOIs in a query string is a long URL. Probe 10/50/100 ids before picking;
  record the answer here. Start from the number that returns 200, not the number
  that "should" work.
- **Budget as a first-class value.** Parse `X-RateLimit-Credits-Used`,
  `-Remaining`, `-Limit` on every response. Expose the last-seen remaining so the
  crawl can stop on budget rather than on call count. Over-budget requests are
  refused before running and **cost nothing**, so treating a 429/`Retry-After` as
  "stop for the day" is cheap and correct.
- **`TokenBuckets` still applies.** The polite-pool rule (a mailbox in the
  `mailto` parameter) is documented; with a key it is less critical, but the
  project's existing per-API bucket costs nothing and the free tier is per
  account, not per process.
- **Timeout.** Do not inherit the 3 s default that makes Unpaywall fail
  (issue-016 / debt-009). Set what the measurements justify and say so in the
  constructor.
- **Abstracts.** Reassembly is: invert `index → words`, sort by position, join.
  ~15 lines. Test it on a fixture where the ordering matters, because a wrong
  sort produces fluent nonsense rather than an error.
- **Retraction.** `is_retracted` is available and `Record` has nowhere to put it.
  Out of scope to model it; note in the record's Javadoc that it is dropped, so
  the next person does not rediscover this by surprise.

## Tests

Offline, from a recorded fixture (`fixtures/openalex/works-nature.json` — the
probe response is a good seed, it has 35 references and an abstract):

- title, date, container, authors, pages translate.
- abstract reassembles in the right order.
- `ids` yields DOI, PMID, PMCID, OpenAlex as `getOtherIdentifiers()`.
- `referenced_works` yields `CitationLink`s in the **source-cites-target**
  direction, and one inverted assertion pinning that direction — issue-007
  closed with "there is no test asserting edge direction for any client", so
  this is where that debt gets paid.
- a record with no abstract, no DOI, and no date does not throw (the class of bug
  issue-005 was about).

Live, `@Category(LiveTest.class)`:

- fetch by DOI is free: assert `X-RateLimit-Credits-Used: 0`.
- `cites:` costs 1 and returns citing works.
- search costs 10 and the top hit for a distinctive title is that title.
- budget exhaustion behaviour, if it can be provoked cheaply; if not, do not
  burn 10,000 credits to test it — assert only that a refusal is surfaced as a
  distinct, named failure rather than "found nothing"
  ([issue-013](/design/implementation/issues/issue-013-one-failing-api-empties-multisearch.md)).

Contract test entry in
[test-002](/design/test-scripts/test-002-api-behaviour-tests.md) — the endpoint
shape (`/works`, `filter=cites:`, `filter=doi:`) is exactly the kind of thing that
changes silently (issue-002).

## Open questions — answered during implementation

- **Which search expression expresses `Query.getAllFields()`?** `default.search`.
  Measured, it and a bare `search=` parameter return an identical count (674,724
  for "photosynthesis") and the API describes both as full text; it is written as a
  filter rather than as the parameter only because one `filter` parameter has to
  carry every condition. `title_and_abstract.search` is used for
  `Query.getTitleAbstract()`, which is the narrower intent.
- **Does `getOtherIdentifiers()` carry the OpenAlex id?** Yes, and `IdType.OPENALEX`
  already existed (declared unused — debt-011). OpenAlex results therefore merge with
  CrossRef and Europe PMC ones on DOI in the plan-008 dedupe without a mapping round
  trip, which was the thing to verify rather than assume.
- **Does OpenAlex belong in `searchApis`?** It was left out of this plan and **put
  in afterwards, in 0.0.11, behind a credit gate** (see "Search, reconsidered"
  below). The reasoning here — 10 credits a call against ~1,000 searches a day, when
  three free search sources exist — turned out to argue for a reserve rather than
  for exclusion.
- **`related_works`?** Ignored, as decided: it is neither up nor down, and it is not
  wired into `CitedByMapper`.

## Status: done, with follow-ups

Implemented 2026-11-02: `OpenAlexClient` (five roles), `OpenAlexRecord`,
`OpenAlexAuthor`, `OpenAlexListResult`, registered in `BiblioFacade.defaultApis()` and
`BibliographicApis`; `PublicationTypes.fromOpenAlex`; `Provenance.OPENALEX` and
`MergePolicy` updated; Unpaywall retired (issue-016 closed). `mvn -o clean test`: 190
tests, 0 failures. Live tier: 13 tests in `TestOpenAlexClientEndToEnd`, 0 failures.

Things this round did **not** do, in the order they are worth taking:

1. **`pom.xml` is at `0.0.10-SNAPSHOT` — a patch bump, and the milestone question is
   still open.** feat-011 is written against `0.1.0`, whose theme ("MCP server
   usable") is not reached while issue-017 is open. Deciding whether this work ships
   as 0.0.10 or folds into 0.1.0 belongs to the developer (ADR-0001: the version
   carries the milestone).
2. **`interface-002` now documents `openalex-apikey` and `openalex-daily-credits`,
   but only `openalex-apikey` is read.** Nothing enforces a daily credit ceiling;
   `creditsRemaining()` is exposed and the crawl engine (plan-011) is where it should
   be spent. That is the intended shape, not an oversight — but a caller who ignores
   `creditsRemaining()` will discover the budget by exhausting it.
3. **Budget exhaustion is untested.** plan-013 said not to burn 10,000 credits to
   provoke it, and the live test therefore asserts only that the budget is *visible*
   after a call. Whether an over-budget refusal surfaces as a named failure or as
   "found nothing" is still unknown, and issue-013 says the latter is the habit of
   this codebase (`CachingApiClient` maps every non-2xx to empty).
4. **`IdType.OPENALEX` is now used, so debt-011 (a declared-but-unused id type) can
   be closed** — not done here, to keep this change to one client.
5. **`MergePolicy`'s other tables do not rank OpenAlex at all** except `FALLBACK` and
   `PDF`. OpenAlex's title, date and abstract are third-hand (an aggregation of the
   sources this project already queries directly), so leaving them in `FALLBACK`
   behind CrossRef/EuropePMC/Entrez is probably right, but it was not measured.

## Search, reconsidered — 0.0.11

The decision not to register OpenAlex as a search provider was reversed, with a
gate rather than without one. The arithmetic that argued against it is the same:
a search is 10 credits of a 10,000 daily allowance. What changed is reading it as
a budget rather than as a prohibition — 10,000 credits is about 1,000 searches a
day, which is more than an interactive session uses, and the fetch and idMap
roles that OpenAlex is the only source for are free or 1 credit.

So the risk was never the price of searching; it was searching spending the
allowance that the citation crawl needs. A reserve addresses that directly:

| | |
|---|---|
| `openalex-daily-credits` | the ceiling, default 10,000 |
| `openalex-search-credit-reserve` | kept back from searching, default 1,000 |
| gate | a search runs while `available - 10 >= reserve`; a 1-credit call while `available >= 1` |
| source of `available` | `X-RateLimit-Remaining` when a response has reported it, else the ceiling less what this process has spent |

Three consequences worth stating:

- **A skipped search is not a failed one.** `BiblioFacade.dispatchSearch` logs the
  skip and returns nothing from OpenAlex; Crossref, Entrez and Europe PMC still
  answer, and the dedupe ranks what they return as usual.
- **A refusal is named.** `OpenAlexClient.SearchQueryBuilder.execute()` throws a
  `BibliographicApiException` saying how many credits were needed and how many are
  left, which answers follow-up 3 below: an exhausted budget no longer looks like a
  search that found no papers. The facade catches it, because until issue-013 is
  dealt with an exception from one client empties every other client's results too.
- **Cache hits are not charged.** The tally increments in `rateLimit(cost)`, which
  the cache short-circuits, so a repeated search costs nothing and is not counted
  as though it had.

**Content download is still out of scope**, and is now the only expensive thing
OpenAlex offers that this client does not do: a cached PDF is 10 credits and
capped at 100 a day, while `best_oa_location.pdf_url` points at the publisher's
own copy, which `PdfFetcher` takes for nothing.

Follow-ups 2 and 3 below are closed by this. Follow-up 1 is settled too: the
OpenAlex client shipped as 0.0.10 and this gate as 0.0.11.
