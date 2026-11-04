---
title: Javadoc build cannot see generated sources
status: closed
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

## Closed — 0.2.0

`maven-javadoc-plugin` is configured in the POM, so

    mvn -o compile javadoc:javadoc

builds the whole API documentation into `target/reports/apidocs` with no
command-line incantation. Verified from `mvn -o clean`.

Of the record's two suggestions this takes the first — an explicit `sourcepath`
on the javadoc plugin — rather than `build-helper-maven-plugin`, and for a
reason the record could not have known: `build-helper` adds the source root in
the `generate-sources` phase, and `mvn javadoc:javadoc` runs no phases at all.
It would have helped an IDE and not the goal. The sourcepath is read by the
goal itself however it is invoked.

`doclint` is set to `none`, deliberately and for the reason the record gives:
this project's javadoc is prose, with no `@param` or `@return` tags anywhere, so
the default doclint produces several hundred warnings that bury anything real.
`quiet` is on for the same reason.

What is **not** fixed, because nothing can fix it: the goal still needs a prior
`compile`. Javadoc does not run annotation processors, so the `Immutable*`
classes have to exist on disk before it can resolve a reference to one. That is
now said in a comment beside the plugin rather than discovered.

The goal is not bound to a phase. Binding it to `package` would rebuild the
whole javadoc on every build of the jar, including every smoke test run, to
produce something nobody asked for.
