---
status: open
since-version: 0.1.0
target-version: 0.3.0
tags: medrxiv, biorxiv, search, api-drift
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

`MedrxivClient.search(Query)` cannot work. bioRxiv now serves a Cloudflare
challenge on `/search/`, so the HTML search interface the client scrapes is
unreachable from any programmatic client.

    $ curl -sD- 'https://www.biorxiv.org/search/abstract_title%3Asars-cov-2'
    HTTP/2 403
    cf-mitigated: challenge
    server: cloudflare
    <title>Just a moment...</title>

The 403 is returned with no user agent, with a Java user agent, and with a
browser user agent, so it is not a header that can be spoofed — it is a
JavaScript challenge that expects a real browser. `CachingApiClient` maps the
non-2xx to `Optional.empty()`, so the failure surfaces as "this search found
nothing" rather than as an error, which is why it looked like a query defect.

The query itself is correct — see issue-011, where the expression the client
builds was verified to return 6,327 correctly windowed results when fetched
through a browser-equivalent client.

Scope of the outage:

| endpoint | status |
|---|---|
| `www.biorxiv.org/search/...` (HTML search) | **403 challenge** |
| `www.biorxiv.org/` (homepage) | 200 |
| `api.biorxiv.org/details/...` (JSON API) | 200 |

So only the scraped search path is affected. Metadata fetch, the
preprint-to-published mapping and the JATS reference lists all go through
`api.biorxiv.org` and are unaffected.

This is the risk recorded generally in issue-002 (third-party endpoint drift)
arriving concretely, and it is the kind of drift that cannot be fixed by
adjusting a request.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`medrxivHonoursAPublicationDateWindow` fails with "medrxiv returned nothing".
It is deliberately left failing: the preprint servers are a configured search
source and silently returning nothing is the defect.

## Suggested fix: search preprints through Europe PMC

The block does not need working around. Europe PMC indexes medRxiv and bioRxiv
**essentially completely**, and this project already has a Europe PMC client
with term search, date windows and relevance ranking.

Measured 2026-10-17, Europe PMC `SRC:PPR` hit counts against each server's own
count of distinct preprints (`count_new_papers` from
`api.biorxiv.org/details/...`, which excludes the revision rows inflating its
`total`):

| window | server | Europe PMC | server's own new papers | coverage |
|---|---|---:|---:|---|
| 2024 H1 | medRxiv | 6,170 | 6,175 | 99.9% |
| 2024 H1 | bioRxiv | 20,871 | 20,875 | 100.0% |
| 2026 H1 | medRxiv | 7,983 | 8,067 | 99.0% |
| 2026 H1 | bioRxiv | 26,555 | 26,549 | 100.0% |

Current-year coverage is as good as historical, so there is no meaningful
indexing lag. Records come back as full core results with abstracts and author
lists, and `bookOrReportDetails.publisher` names the server, so medRxiv and
bioRxiv remain distinguishable.

This supersedes the "coverage is partial" conclusion in roadmap idea 6, which
was drawn from a three-DOI sample.

### It already works with no code change

`EuropePMCClient.QueryBuilder.withQuery` passes `getNativeQuery()` straight into
the term list, so a preprint search is expressible today:

    ImmutableQuery.builder()
        .nativeQuery("SRC:PPR")
        .titleAbstract("sars-cov-2")
        .after(...).until(...)
        .build()

which builds `SRC:PPR AND TITLE_ABS:(sars-cov-2) AND (FIRST_PDATE:[...])` —
verified live, 9,631 hits, full records.

### Recommended shape

Make it a first-class capability rather than something a caller has to know the
syntax for: a `preprintsOnly()` toggle on `EuropePMCClient.QueryBuilder`, and a
`"preprint"` entry in `BiblioFacade.defaultApis().get("search")` that routes to
Europe PMC with that toggle set, replacing the `"medrxiv"` search source.

`MedrxivClient` keeps the three jobs only it can do, all of which go through
`api.biorxiv.org` and are unaffected by the block: server-native metadata
(version, license, category, funder), the JATS full text and its reference
list, and the preprint-to-published-article mapping. Only `search(Query)` and
its `SearchQueryBuilder` become dead and should be removed rather than left to
return nothing.

### Rejected alternatives

| option | why not |
|---|---|
| JSON window + client-side term matching | `api.biorxiv.org/details/{server}/{from}/{to}/{cursor}` has no term search, pages 30 at a time, and returns revision rows as well as new papers; a year window would be hundreds of requests to reproduce what one Europe PMC call does, without relevance ranking |
| A browser-capable fetcher (headless browser, challenge solver) | heavy dependency, fragile, and the challenge is the operator declining automated access to that path — they publish a JSON API instead. Not appropriate |
| Crossref `type:posted-content` | works for a term plus a posted-date window, but could not be restricted to the preprint servers: `filter=prefix:10.1101` returns 0 because openRxiv's owner prefix differs from the DOI prefix, so results mix in Research Square, Authorea and others |

Separately, and regardless of which route is taken: `BiblioFacade` should stop
reporting a blocked source as an empty result — see issue-013. A 403 became
"no preprints matched", which is how this stayed invisible.
