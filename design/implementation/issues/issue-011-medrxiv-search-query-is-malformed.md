---
title: Medrxiv search query is malformed
status: closed
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- medrxiv
- biorxiv
- search
- query
TESTED_BY:
- test:java/uk/co/terminological/bibliography/client/TestQueryTranslation.java
- test:java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java
---

## Resolution

Fixed 2026-10-17 (developer): `forEach(this::author)` replaces the terminal-less
`map`, and the bare-value branch for `getAllFields()` is removed, so every term
is emitted once as a `field:value` pair.

Verified directly against the server. The expression the client now builds,

    format-result:standard jcode:medrxiv||biorxiv sort:relevance-rank
    abstract_title:sars-cov-2 abstract_title_flags:match-all numresults:25
    limit_from:2021-01-01 limit_to:2021-12-31

returns "6,327 Results for abstract or title \"sars-cov-2\" (match all words)
and posted between \"01 Jan, 2021 and 31 Dec, 2021\"" -- correct terms, correct
window, correct page size.

**The live search still returns nothing, for an unrelated reason.** The query is
right; the request never gets through. bioRxiv now serves a Cloudflare challenge
on `/search/`, so no programmatic client can read it at all. That is split out
as issue-015, which is where `medrxivHonoursAPublicationDateWindow` now points.

The open question in this record -- what `getAllFields()` should mean for a
preprint server -- is unchanged and still worth settling.



`MedrxivClient.search(Query)` returns nothing for a query that should match
thousands of preprints (`sars-cov-2`, title/abstract, 2021). Confirmed live by
`medrxivHonoursAPublicationDateWindow`.

The query this client builds is not a documented API but a path-style
expression for the preprint servers' HTML search interface, where every term
has to be a `field:value` pair. Three defects in
`SearchQueryBuilder.withQuery`:

**Authors are silently dropped.**

    qry.getAuthors()
        .stream()
        .map(this::author);

`map` is lazy and there is no terminal operation, so `author()` is never called
and no author term is ever added. (The other fields in the same method use
`forEach` or `ifPresent`.)

**The all-fields term is emitted twice, once malformed.**

    qry.getAllFields().stream().map(this::asValue).forEach(this.terms::add);
    ...
    qry.getAllFields().ifPresent(this::fullText);

The first adds the bare value with no field prefix — producing a path segment
like `sars-cov-2` sitting between `sort:relevance-rank` and
`text_abstract_title:sars-cov-2` — and the second adds it properly. So the term
appears twice and one copy is a malformed segment. A built query reads:

    format-result:standard jcode:medrxiv||biorxiv sort:relevance-rank
    sars-cov-2 text_abstract_title:sars-cov-2 text_abstract_title_flags:match-all

The bare segment is the likely reason the live search returns an empty result
set rather than an error.

**`getFullText()` and `getAllFields()` both map to `text_abstract_title`**, so a
caller distinguishing the two gets the same search either way with the terms
ANDed (`match-all`), making the query stricter than asked.

## Reproduced by

[TestQueryTranslation.java](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslation.java)
[TestQueryTranslationLive.java](/src/test/java/uk/co/terminological/bibliography/client/TestQueryTranslationLive.java)

`medrxivIncludesTheAuthorsItWasGiven`, `medrxivUsesEachTermOnce` and
`medrxivEveryTermIsAFieldValuePair` offline;
`medrxivHonoursAPublicationDateWindow` live.


1. `forEach(this::author)` rather than `map`.
2. Remove the bare-value branch and keep the `fullText` mapping for
   `getAllFields()`, so each term is emitted once as a `field:value` pair.
3. Decide what `getAllFields()` should mean here and document it: the servers
   expose `abstract_title` and `text_abstract_title`, so the natural reading is
   `getTitleAbstract()` → `abstract_title` (already correct) and both
   `getAllFields()` and `getFullText()` → `text_abstract_title`. If that is the
   intent it is fine, but it should be stated rather than inferred from a
   duplicated call.

The date window (`limit_from`/`limit_to`) and `numresults` are correct and are
now covered.

Worth noting for later: because this is a scraped HTML interface rather than an
API, a malformed query returns a normal-looking page with no results. There is
no error to detect, so only an assertion on the number of records returned
catches it — which is what the live test does.
