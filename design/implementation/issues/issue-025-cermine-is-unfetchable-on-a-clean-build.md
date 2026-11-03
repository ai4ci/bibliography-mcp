---
class: issue
id: issue-025-cermine-is-unfetchable-on-a-clean-build.md
title: Cermine is unfetchable on a clean build
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
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
