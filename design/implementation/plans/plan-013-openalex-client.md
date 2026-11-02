---
title: OpenAlex client
status: draft
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

## Open questions

- Which search expression expresses `Query.getAllFields()`:
  `title_and_abstract.search` (semantically right, must confirm it is 10 credits
  and that it exists as a filter), or full-text `search` (what the probe
  accidentally used)? Crossref gets `query.title` etc., and the client's job is
  to translate intent, not to pick the cheapest.
- Does `getOtherIdentifiers()` carry the OpenAlex id, or does `IdType.OPENALEX`
  need to appear in `RecordReference` too? The dedupe in plan-008 keys off
  identifiers, so this decides whether OpenAlex results merge with Crossref ones
  automatically or need mapping first. (They should merge on DOI — verify rather
  than assume.)
- Does OpenAlex belong in `searchApis` at all, at 10 credits a call against
  1,000 searches a day, when three free search sources already exist? Proposal:
  register it for fetch/citations/ids/OA, and make search opt-in per call.
- `related_works` is in the response and is neither up nor down. Ignore it for
  now, but decide before someone wires it into `CitedByMapper` and silently
  corrupts the graph.
