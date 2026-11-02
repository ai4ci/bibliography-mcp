---
title: Configuration and secrets
status: final
targetVersion: 0.1.0
tags:
- config
- secrets
HAS_FEATURE:
- feature:feat-001-expose-bibliographic-apis-over-mcp.md
IMPLEMENTED_BY:
- main:java/uk/co/terminological/bibliography/api/BiblioFacade.java
- main:java/uk/co/terminological/bibliography/mcp/McpServer.java
- main:java/uk/co/terminological/bibliography/api/zotero/ZoteroConfig.java
---

# Configuration and secrets

## Process invocation

```
java -jar bibliographic-api-client-<version>.jar [secrets-path] [cache-dir]
```

| Argument | Required | Default |
|----------|----------|---------|
| secrets path | no | `$BIBLIO_SECRETS_PATH`, else `~/.private/apis/secrets.prop` |
| cache directory | no | none (in-memory caching only; `EhcacheManager` warns) |

A leading `~` in either path is expanded against `user.home`.

## Secrets file

A Java properties file. Keys consumed by the underlying clients:

```properties
app-id=<tool identifier sent to the APIs>
developer-email=<contact address for the APIs' polite pools>
crossref-clickthroughtoken=<CrossRef token>
pubmed-apikey=<NCBI API key>
```

Missing keys degrade access (lower rate limits, some endpoints unavailable)
rather than failing at startup.

## Zotero (optional)

The Zotero bridge reads the **local** API of a running Zotero desktop client —
`http://localhost:23119/api`, which serves the user's own library with no rate
limits and no authentication for reads. It is optional: a missing Zotero must not
stop the server, so availability is checked when a Zotero tool is used, not at
startup. Keys in the same properties file:

| key | default | meaning |
|-----|---------|---------|
| `zotero-mode` | `local` | `local`, or `off` to disable the Zotero tools |
| `zotero-base-url` | `http://localhost:23119/api` | where the local API lives |
| `zotero-user-id` | `0` | `0` means the locally logged-in user, which the local API accepts |
| `zotero-allow-writes` | `false` | writes are refused unless this is true |
| `zotero-write-collection` | (none) | the **only** collection writes may touch; a sub-collection created by the bridge is always a child of it |

Local write permission is not a key you can put here: Zotero asks the user at
runtime (`POST /api/local/authorize`), naming the application. The key it returns
is **single-use** unless the user chooses "Always Allow", in which case it can be
reused indefinitely until the user clears the write authorisations in Zotero
(Settings → Advanced). The bridge holds it in memory for the session and never
writes it to disk. A refused request returns `403`, more than five permission
prompts a minute returns `429`, and a dialog left unanswered times out after two
minutes with nothing written.

Because each **request** normally costs one prompt, writes are batched: tags are
applied to up to 50 items in one request, and created items go in batches of 50.
Tagging six items one at a time would otherwise need six prompts and hit the limit.

When the local API preference is off, every Zotero request returns `403` and the
tool message says which setting to change (Zotero Settings → Advanced → "Allow
other applications on this computer to communicate with Zotero").

## OpenAlex credits

OpenAlex is the only client here that meters by **credit** rather than by
request: a lookup by identifier is free, a filtered call costs 1, and anything
involving text search costs 10, against a daily allowance. These keys live in the
same properties file.

| key | default | meaning |
|-----|---------|---------|
| `openalex-apikey` | (none) | OpenAlex token, sent as `Authorization: Bearer`. OpenAlex works without one at 1/10th the budget — see below |
| `openalex-daily-credits` | `10000` | the day's credit ceiling. The real figure is read back from the response headers and believed over this one; the ceiling is what the client counts against until a response has spoken |
| `openalex-search-credit-reserve` | `1000` | credits kept back from searching. A search costs 10 and a fetch or citation call 1, so this is what stops a day of searches from leaving the roles OpenAlex is the only source for unable to make their calls. Below it OpenAlex drops out of the search and the free sources answer alone |

The reserve is what lets OpenAlex be a search provider at all. 10,000 credits is
roughly 1,000 searches a day, which is more than an interactive session uses, but
searching and crawling draw on one allowance — so searches stop once spending one
would leave less than the reserve, while the 1-credit fetch and citation calls
carry on. A skipped search is logged and the free sources still answer; a search
asked for directly through `OpenAlexClient` raises a named refusal instead, so an
exhausted budget never looks like a search that found no papers (issue-013).

Without a key the allowance is a tenth of this, so set `openalex-daily-credits`
to `1000` to match, or searching will be refused by the API rather than by the
gate. Lookups by identifier stay free either way.


An EHCache disk cache keyed by URL + params, one cache per API
(`crossref`, `entrez`, `europepmc`, `openalex`, `opencitations`, …) — this is
what makes repeated citation-graph expansion cheap. Logging is verbose at
DEBUG; grep stderr for `Calling url` to see real API traffic.

