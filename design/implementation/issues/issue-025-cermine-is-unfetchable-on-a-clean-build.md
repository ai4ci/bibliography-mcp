---
class: issue
id: issue-025-cermine-is-unfetchable-on-a-clean-build.md
title: Cermine is unfetchable on a clean build
status: closed
sinceVersion: 0.0.6
targetVersion: 0.2.0
tags:
- build
- dependencies
- ci
---
## Issue desc

The build depends on `pl.edu.icm.cermine:cermine-impl:1.13`, which is **not
published to Maven Central**, and the repository that does publish it has an
expired TLS certificate:

```
$ curl -sS -o /dev/null https://maven.icm.edu.pl/artifactory/repo/
curl: (60) SSL certificate OpenSSL verify result: certificate has expired (10)

$ curl -s -o /dev/null -w '%{http_code}' \
    https://repo.maven.apache.org/maven2/pl/edu/icm/cermine/cermine-impl/1.13/cermine-impl-1.13.pom
404
```

So a build on a machine whose `~/.m2` already holds cermine succeeds, and a
**clean build cannot resolve it from anywhere**. This repository builds today
because the artifact was cached before the certificate expired. CI with an empty
or cold local repository will fail on it.

The same certificate costs every *online* build time even when nothing is
missing: `icm` is declared in `pom.xml`'s `<repositories>` ahead of Central, so
Maven attempts it for each artifact and falls back only after the TLS failure. A
`dependency:resolve` after adding four artifacts took minutes rather than
seconds.

Pre-existing, and recorded in passing by
[adr-002](../../../architecture/decision-records/adr-002-hand-rolled-mcp-protocol.md) in 2026-10 ("the remote ICM
repository has an expired certificate"), which treated it as a reason to avoid
new dependencies rather than as a defect in its own right. Found again while
adding the MCP SDK, where it is the slow part of every resolve.

## Why it matters more now

The jar stopped being shaded in 0.1.0 and the dependency tree is about to grow
(MCP SDK, Jetty). Neither makes this worse by itself, but both mean more
artifacts to resolve, and the first clean CI build is the moment this surfaces.

## Options

| # | Option | Cost |
|---|---|---|
| A | **Vendor the three cermine artifacts** into a checked-in `repo/` directory and declare it as a `file://` repository | repository grows by the jars; fully reproducible; no network dependency on a dead host |
| B | **Publish cermine 1.13 to the project's own `m2repo`** (the parent pom already points at one) | one upload; keeps `pom.xml` clean; needs write access to that repository |
| C | **Replace cermine** with another reference extractor (GROBID is the obvious candidate, but it is a service rather than a library) | a real piece of work, and a different quality of extraction |
| D | **Keep `icm` but move it after Central** and accept that clean builds fail | does nothing about the failure; only stops the time cost |

Recommendation: **B if the m2repo is writable, otherwise A.** Both make the
build reproducible; neither changes what the code does. Whichever is chosen,
`icm` should also stop being consulted before Central.

## Not fixed here

Changing `pom.xml`'s repositories or vendoring artifacts is a build-policy
decision for the developer, and the workaround (`mvn -o` against a warm
`~/.m2`) is what this project already does by default.

## Closed — 0.2.0, by retiring cermine

None of the options above was taken. The developer's call: **PDFs are not central
to this project, and this is a problem**, so the dependency goes rather than
being vendored or worked around.

Removed: `pl.edu.icm.cermine:cermine-impl`, `PdfFetcher.extractArticleRefs`,
`BiblioFacade.getReferencesFromPdf`, and the `icm` repository itself. The three
artifacts no public repository could serve all came from that one subtree —
cermine plus `edu.umass.cs.mallet:mallet` and `grmm-deps` — so they went with
it, along with itextpdf and libsvm.

What survives is what PDFs are actually for here: downloading one to a file, for
a Zotero attachment or an indexing directory. A work's reference list is better
asked of an API that already has one, which is what the crawl does.

`cc.mallet` is still on the classpath through `nlp-tools`, so the topic model is
unaffected. 285 tests pass.

### What this does not fix

The **ICM repository is gone, so it can no longer slow or break a build** — that
is the whole of what this issue was about, and it is fixed. But a cold build is
still not self-service:

| artifact | Central | note |
|---|---|---|
| `io.github.terminological:nlp-tools:master-SNAPSHOT` | **404** | GitHub Packages, 401 without credentials — and a `master-SNAPSHOT`, so it is not a reproducible coordinate either |
| `io.github.terminological:exotic-datatypes:1.0.0` | **404** | GitHub Packages, 401 without credentials |
| `io.github.terminological:fluentxml:2.1.0` | 200 | fine |
| `io.github.terminological:m2repo:0.0.6` (parent) | 200 | fine |

That is a different and much milder situation: cermine could be fetched by
**nobody**, where these can be fetched by anyone holding a token for the
developer's own packages — which CI running in that org plausibly does. Worth a
separate record if a build outside it is ever wanted, and worth noting that a
`master-SNAPSHOT` dependency makes any build unrepeatable whoever runs it.

### One wrinkle when the repository went

Maven records in `_remote.repositories` which repository each cached artifact
came from, and refuses it offline once that repository is undeclared. Several
unrelated artifacts had been fetched through `icm`, so the first build after this
change has to be online; it then re-records them against Central and offline
builds work again.
