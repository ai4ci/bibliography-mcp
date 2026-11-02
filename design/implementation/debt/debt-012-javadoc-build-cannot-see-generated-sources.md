---
title: Javadoc build cannot see generated sources
status: open
sinceVersion: 0.1.0
targetVersion: 0.3.0
tags:
- build
- documentation
- immutables
---

## Debt desc

`mvn javadoc:javadoc` fails on a clean checkout. The record model's interfaces
refer to the `Immutable*` classes the Immutables annotation processor generates
(`RecordReference.asId()` returns `ImmutableRecordReference`,
`RecordFetcher.fetch` is keyed by it, `Record.getIdentifiers()` returns a set of
them), but the javadoc goal does not run the processor and does not have
`target/generated-sources/annotations` on its source path, so every such
reference is `cannot find symbol` and the build stops.

It can be worked around from the command line:

    mvn -o javadoc:javadoc -Dsourcepath="src/main/java:target/generated-sources/annotations"

which requires a prior `mvn compile` to have generated the sources. That is not
discoverable, and it means the Javadoc the code carries cannot actually be read
as Javadoc without knowing the trick.

Noticed while documenting the `record` and `client` packages.

## Suggested resolution

Configure `maven-javadoc-plugin` in the POM so the goal works unqualified —
either by adding the generated sources directory to its source path, or with
`build-helper-maven-plugin` adding it as a project source root so every plugin
sees it. The second is the more usual arrangement and also helps IDEs that are
not driving the processor themselves.

Separately, the project's Javadoc style is prose-only: no `@param` or `@return`
tags anywhere. The default doclint warns on every one of those, which buries
real problems in several hundred lines of noise, so whatever configuration is
added should also set the doclint level deliberately rather than leaving it at
the default.


- [IMPACTS](/pom.xml)
