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

- `search=` as a plain parameter resolved to `fulltext.search` (543 hits for
  "CRISPR kelp"). Full-text search is a different thing from title/abstract
  search and costs the same 10 credits. `title_and_abstract.search` exists as a
  filter and is the one that matches what `Query.getAllFields()` means here.
  Verify which is right before wiring it.
- Batch OR filters with 100 ids will produce a long URL. **Not probed** —
  needs a real 100-id request before plan-013 commits to a batch size.
- Costs and the free budget are commercial terms and can change without notice;
  this whole document is a snapshot. Keep the header-parsing so the client
  notices.
