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


An EHCache disk cache keyed by URL + params, one cache per API
(`crossref`, `entrez`, `europepmc`, `unpaywall`, `opencitations`, …) — this is
what makes repeated citation-graph expansion cheap. Logging is verbose at
DEBUG; grep stderr for `Calling url` to see real API traffic.

