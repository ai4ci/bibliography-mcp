---
title: OpenAlex cost model and record shape
status: final
targetVersion: unassigned
tags:
- openalex
- api-drift
- citations
- cost
HAS_FEATURE:
- feature:feat-011-openalex-as-a-provider.md
---

# Prototype 002 — what OpenAlex actually costs, and what it returns

Probed against the live API on 2026-11-02 with the developer's own key. The
point is to establish the crawl's economics **before** plan-011 fixes a default
`depth` and `maxRecords`, because the numbers change what a sane default is by
two orders of magnitude. Prices published at
[help.openalex.org/access/example-costs](https://help.openalex.org/access/example-costs/)
agree with every measurement below.

## Method

`curl` against `https://api.openalex.org`, key from
`~/.private/apis/secrets.prop` (`openalex-apikey`), reading the
`X-RateLimit-Credits-Used` / `-Remaining` / `-Limit` response headers rather
than trusting the docs. Daily budget on this key measured at
`X-RateLimit-Limit: 10000` credits ($1/day at $0.0001/credit), which matches the
`openalex-daily-credits=10000` the developer recorded.

## Measured costs

| operation | request | credits | notes |
|---|---|---|---|
| Singleton by DOI | `GET /works/doi:10.1038/nature12373` | **0** | `X-RateLimit-Cost-USD: 0`. **Unlimited** — the table says unlimited singletons for the whole daily budget |
| Singleton **without** a key | same, no auth | 0, but `X-RateLimit-Limit: 1000` ($0.10/day) | anonymous still gets free singletons; the key only matters for billable calls |
| List + filter | `?filter=doi:A\|B` | **1** | the `|` is OR, so **one credit fetches up to `per_page` records** |
| Citing works | `?filter=cites:W2159974629` | **1** | 1,955 citing works for that seed |
| `filter=referenced_works:W…` | same query | 1 | **Identical result to `cites:`** (both returned count 1955, and `meta.x_query.oqo` resolved both to `referenced_works`). `cites:` is the one to use |
| Keyword search | `?search=CRISPR kelp` | **10** | note: `search=` as a *parameter* resolved to `fulltext.search`, not `title.search` — see risks |
| Search + rerank | (documented, not probed) | 20 | |
| Content download | (not probed) | 10 per PDF | **do not use** — see decision below |

Header names worth parsing, all present on every response:
`X-RateLimit-Credits-Used`, `-Remaining`, `-Limit`, `-Cost-USD`,
`-Cost-Required-USD`, `-Reset` (seconds), `Retry-After`. `meta.cost_usd` is in
the body too. A request over budget is refused **before running, and refusals
are free** — so budget exhaustion is a cheap signal, not a disaster.

## What this means for the crawl

The economic fact that changes the design: **a work's reference list ships
inside the free singleton.** W2159974629 came back with 35 `referenced_works`
entries, `cited_by_count: 1958`, `ids`, `best_oa_location.pdf_url`, and
`abstract_inverted_index` — for zero credits.

| crawl direction | how | credits per seed | credits per 1,000 records |
|---|---|---|---|
| **DOWN** (references) | free singleton carries `referenced_works`; materialise them with `filter=openalex:W1\|…\|W100` | **0** | ~10 |
| **UP** (citations) | `filter=cites:W…` + batch materialise | 1 | ~10 + 10 per seed |
| search to seed a repository | `search=` | 10 per query | — |

Against a 10,000 credit/day budget: ~1,000 searches, or ~10,000 filter calls, or
unlimited singletons. A depth-2 `DOWN` crawl from 25 seeds is tens of credits.
The same crawl through OpenCitations today is rate-limited by request count, not
cost, and costs nothing in money but hours in wall clock.

**Decision implied:** the crawl's cost ceiling should be denominated in
OpenAlex credits read from the response headers, not in a fixed call count, and
`maxRecords` can be far larger than any current default. Recorded as a change to
plan-011, which currently proposes a call ceiling.

**Decision not to take:** OpenAlex content download ($10/PDF) is *not* how we get
PDFs. `best_oa_location.pdf_url` points at the publisher's own PDF
(measured: `https://www.nature.com/articles/nature12373.pdf`), which
`PdfFetcher` can fetch directly at no OpenAlex cost. The content API buys a
cached copy we do not need.

## Record shape (measured on W2159974629)

Top-level fields relevant to `Record`: `display_name`, `publication_date`
(a real ISO date — issue-014's problem does not exist here), `publication_year`,
`biblio` (volume, issue, first_page…), `doi`, `ids`, `authorships`,
`primary_location`, `locations`, `best_oa_location`, `open_access`, `type`,
`language`, `is_retracted`, `referenced_works`, `cited_by_count`,
`abstract_inverted_index`, `topics`, `primary_topic`, `keywords`, `mesh`,
`counts_by_year`, `fwci`, `citation_normalized_percentile`.

- `ids` = `{openalex, doi, mag, pmid, pmcid}` — a **built-in id mapper**,
  broader than the PMC IDConverter currently used, and free.
- `abstract_inverted_index` needs reassembly into text before it can go into
  `Record.getAbstract()`. Mechanical, but not free to ignore: the naive read
  yields a `Map<Integer,List<String>>`.
- `type` is OpenAlex's own vocabulary (`article`, `preprint`, `book-chapter`, …)
  and goes into `Provenance`'s `nativeType` like every other client's, per
  feat-006.
- `primary_location.source.display_name` is the container title; journal
  metadata is nested, exactly like Europe PMC core (issue-012's trap — check the
  nesting rather than assuming flat).

## Risks spotted while probing

- Costs and the free budget are commercial terms and can change without notice;
  this whole document is a snapshot. Keep the header-parsing so the client
  notices.

## Answers to the questions plan-013 was waiting on

Measured 2026-11-02, same key and host as above.

**Batch size is 100 values, and that is a hard limit, not a URL-length one.**
`filter=openalex:W…|…` with 100 ids → `200`, 1 credit; with 101 ids → `400`
`"Maximum number of values exceeded for openalex. Decrease values to 100 or
below"`. Shortening the ids does not help: 150 six-character ids fail the same
way at 1349 characters, so the count is checked before the URL. `fetch` must
chunk at 100 or the first hundred-and-first identifier turns the whole call into
a 400 — which `CachingApiClient` reports as "nothing", not as an error
(issue-013).

**`search=` is not the right parameter, and `title_and_abstract.search` is.**
`search=…` routes to `fulltext.search` (543 hits for "CRISPR kelp"), which is a
different question from the one `Query.getAllFields()` asks and costs the same 10
credits. The valid field list returned by an intentional `filter=bogus:1` lists
`title_and_abstract.search`, `title.search`, `abstract.search`,
`default.search` and `raw_author_name.search` as filters, so the structured
fields of `Query` all have a native home here — including `getJournal()`, which
has to be `primary_location.source.issn`.

**`journal.issn` is not a field.** It answers `400` naming the valid fields;
the journal constraint is `primary_location.source.issn` (verified: 58 hits for
`title_and_abstract.search:thermometry,primary_location.source.issn:0028-0836`,
all in Nature). A journal *name* has to be resolved to an ISSN through
`/sources?search=`, which costs 10 credits — so the client resolves it and the
cost is documented rather than hidden.

**DOI lookup tolerates the two things that bite elsewhere.** `doi:` in a filter
and in the `/works/doi:` path segment both accept upper-case DOIs containing
parentheses (`10.1016/S0140-6736(20)30183-5` → 200 both ways), so the CrossRef
`%2F` encoding trap (issue-016) does not repeat here. Percent-encoding the
parentheses is also accepted, so either spelling is safe.

**The preprint from issue-016 resolves here.** `GET /works/doi:10.64898/2025.12.12.25342129`
→ `200`, and the record carries the medRxiv PDF URL and a 127-word abstract.
Unpaywall returns nothing for that DOI via this client and 200 via curl; OpenAlex
answers it outright, which is the concrete evidence for retiring Unpaywall rather
than debugging it.
