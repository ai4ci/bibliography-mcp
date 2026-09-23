---
status: final
target-version: 0.1.0
tags: config, secrets
---
<!-- @required: status one of draft|final|deprecated -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

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

## Cache

An EHCache disk cache keyed by URL + params, one cache per API
(`crossref`, `entrez`, `europepmc`, `unpaywall`, `opencitations`, …) — this is
what makes repeated citation-graph expansion cheap. Logging is verbose at
DEBUG; grep stderr for `Calling url` to see real API traffic.

## Implemented by

- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/McpServer.java)
- [IMPLEMENTED_BY](/src/main/java/uk/co/terminological/bibliography/mcp/BiblioFacade.java)

## Features

- [HAS_FEATURE](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
