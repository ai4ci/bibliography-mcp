---
title: Crossref query translation is broken
status: closed
sinceVersion: 0.2.0
targetVersion: 0.3.0
tags:
- crossref
- search
- query
- refactor-regression
TESTED_BY:
- test:java/uk/co/terminological/bibliography/client/TestQueryTranslation.java
- test:java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java
---

## Issue desc

`CrossRefClient.search(Query)` threw `NullPointerException` on every call.
Found by `TestQueryTranslation`, confirmed live by `TestQueryTranslationLive`.

`CrossRefClient` was given its `Searcher` implementation during the "Major
refactor and clean up" commit; before that the `Query` path did not exist for
this client, so it had never worked.

| # | Defect | Status |
|---|--------|--------|
| 1 | `QueryBuilder.withQuery` ended `return null;` instead of `return this;` | fixed |
| 2 | `getNativeQuery()` was not read at all | fixed |
| 3 | `Field.AFFIILIATION` is misspelt, emitting `query.affiiliation` | fixed |

Defect 1 was worse than a broken client: `BiblioFacade.multiSearch` has no
per-API error handling, so the NPE propagated out of the loop and **every**
API's results were lost, not just Crossref's. Crossref is also first in
`BiblioFacade.defaultApis().get("search")`. That amplification is recorded
separately as issue-013 and is still open.

### The remaining defect

Crossref does not ignore an unknown field query, it rejects the whole request:

    {"status":"failed","message-type":"validation-failure",
     "message":[{"type":"field-query-not-available","value":"nonsensefield", ...}]}

`CachingApiClient` maps a non-2xx response to `Optional.empty()`, so a search
using `Field.AFFIILIATION` returns no results and no error. Every other
constant in the enum is accepted.

## Corrections to an earlier version of this record

Two claims here were wrong and are withdrawn:

- **`query.title` is valid.** An earlier version of this issue asserted that
  Crossref removed `query.title` in favour of `query.bibliographic`, citing the
  deprecation notice in the `CrossRef/rest-api-doc` GitHub repository. That
  notice is stale. The swagger for `/works` lists `query.title`, the API's own
  validation message lists `title` among the valid field queries, and
  `query.title=photosynthesis` returns 31,080 title matches. The client's
  existing mapping is correct.
- **`query.editor`, `query.chair`, `query.translator` and `query.contributor`
  are also valid**, from the same validation response. The `Field` enum needed
  no pruning.

The lesson, and the reason `crossrefAcceptsEveryFieldQueryTheClientCanEmit`
exists: published documentation for this API has been wrong in both
directions, so the API itself is the only authority worth asserting against.

### The date filter is deliberate, not a defect

An earlier version also listed `from-created-date`/`until-created-date` as a
defect, on the grounds that `Query.getAfter()`/`getUntil()` are a publication
window and `from-pub-date` exists. The developer's decision (2026-10) is that
Crossref's publication dates are too inconsistently populated across members to
filter on, so the deposit date is the more reliable proxy. That is now a
comment in `CrossRefClient.since`/`until` and is pinned by
`crossrefFiltersOnDepositDateByDesign`, so the trade-off is visible rather than
accidental.

## Reproduced by

[TestQueryTranslation.java](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslation.java)
[TestQueryTranslationLive.java](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`crossrefFieldNamesAreSpeltAsCrossrefSpellsThem` offline and
`crossrefAcceptsEveryFieldQueryTheClientCanEmit` live both failed on
`AFFIILIATION -> query.affiiliation` and now pass.

## Resolution

Closed 2026-11-02. All three defects are gone: `withQuery` returns `this`,
`getNativeQuery()` is emitted as a term, and the enum constant is spelt
`AFFILIATION` (`CrossRefClient.java`, `enum Field`), which is what Crossref
accepts. `TestQueryTranslation` is green in the offline tier, including the
spelling test that asserts every `Field` constant against Crossref's own list
of valid field queries.

Defect 3 was the last one open. The amplification it caused through
`multiSearch` remains recorded as issue-013, which is still open.
