---
class: issue
id: issue-024-fetch-answers-under-an-id-it-chose.md
title: Fetch answers under an id it chose
status: closed
sinceVersion: 0.0.6
targetVersion: 0.2.0
tags:
- bug
- fetch
- entrez
- model
TESTED_BY:
- test:java/uk/co/terminological/bibliography/api/TestFetcherContractLive.java
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

## Closed — 0.2.0

Fixed where the plan said: on the contract and in the one client that broke it,
not in the facade.

**`RecordFetcher.fetch` now says what the key means** — the reference the caller
passed, exactly as passed — and why, since an implementation that answers under
an identifier of its own looks correct in isolation and only breaks when merged
with another's. Identifiers discovered along the way belong in
`getOtherIdentifiers()`, where the dedup and the merge already look.

**`EntrezClient.fetch` matches its answers back to the references it was asked
about** rather than keying them by the PMID it resolved to. The PMID is not lost:
it is the record's own identifier and still reaches callers through the record
and the id mappings. The match tolerates a PMC id with or without its prefix, and
compares DOIs without case, because sources disagree about both.

Measured before and after, asking every client for the same DOI:

| client | before | after |
|---|---|---|
| crossref | the DOI | the DOI |
| europepmc | the DOI | the DOI |
| openalex | the DOI | the DOI |
| medrxiv | knows nothing of it | knows nothing of it |
| **entrez** | **PMID 41888721** | **the DOI** |
| **the facade** | **two records** | **one record** |

`TestFetcherContractLive` asks all five the same question, because no single
client's tests can state a contract about how clients agree. It also pins that
the PMID Entrez resolved to still reaches the caller.

The workaround in `BiblioFacade.search`'s identifier path stays, with its comment
rewritten: it now keeps the one-identifier-one-record guarantee at the caller's
edge rather than resting it on five clients continuing to behave.
