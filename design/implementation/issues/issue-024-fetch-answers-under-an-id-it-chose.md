---
class: issue
id: issue-024-fetch-answers-under-an-id-it-chose.md
title: Fetch answers under an id it chose
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
tags:
- bug
- fetch
- entrez
- model
---
## Issue desc

`BiblioFacade.fetch` returns one work twice when the clients disagree about what
to key an answer by. Asked for a single DOI:

```
facade.fetch([DOI:10.1136/bmj.n579])
  key=DOI:10.1136/bmj.n579   Risk of mortality in patients infected with SARS-CoV-2…
  key=PMID:33687922          Risk of mortality in patients infected with SARS-CoV-2…
```

Per client, for the same request:

| client | keys its answer by |
|---|---|
| Crossref | the DOI it was asked about |
| Europe PMC | the DOI it was asked about |
| OpenAlex | the DOI it was asked about |
| **Entrez** | **the PMID it resolved to** |

`BiblioFacade.fetch` merges per-API results with `out.merge(k, …)`, so a key no
other client uses becomes a second entry rather than a second contribution to the
first. The caller asked about one work and is handed two records, each a partial
view of it, and nothing says they are the same paper.

Consequences beyond the duplicate: the merge policy never sees the Entrez
contribution alongside the others, so a field Entrez would have won is resolved
without it, and the record keyed by DOI is poorer than it should be.

Pre-existing. Found while routing identifier queries to `fetch`
([issue-023](issue-023-identifier-queries-are-text-searched.md)), where it
showed up as a DOI search returning the same paper twice.

## Suggested fix

Key every answer by the reference the caller asked about. Each client already
receives the `RecordReference` it is resolving, so the contract is "answer under
what you were asked", and the identifiers the client discovers belong in
`getOtherIdentifiers()` — where the dedup and the merge already use them.

`EntrezClient.fetch` is the one to change; the other three already do this.
Worth a test per client that fetching by DOI answers under that DOI, since this
is a contract no single client's tests can state.

Until then `BiblioFacade.search`'s identifier path merges whatever `fetch`
returns into one record, because one identifier was asked about and everything
returned describes that one work. That workaround becomes redundant when this is
fixed, and harmless.
