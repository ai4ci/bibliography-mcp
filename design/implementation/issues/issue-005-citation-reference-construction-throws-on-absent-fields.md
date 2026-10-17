---
status: closed
since-version: 0.2.0
target-version: 0.2.0
tags: model, citations, refactor-regression, data-loss
---
<!-- @required: status one of open|closed -->
<!-- @required: target-version specified -->
<!-- @optional: tags containing comma separated list of tags. -->

## Issue desc

Building a `CitationReference` throws `NullPointerException` whenever the
reference has no title or no print detail — which is the normal case for the
main citation provider. Every citation edge from OpenCitations, CrossRef and
the medRxiv/bioRxiv JATS reference lists currently fails.

Introduced by the "Major refactor and clean up" commit, which moved
`CitationReference` from a hand-written anonymous implementation
(`CitationReference.create`, which wraps its arguments in `Optional.ofNullable`)
to the generated `ImmutableCitationReference` builder, without carrying the
null tolerance across.

Two distinct defects in `record/Builder.java`:

1. `citationReference(RecordReference, Optional<String>, Optional<PrintReference>)`
   passes its arguments straight to the Immutables builder, whose generated
   setters dereference them (`title.orElse(null)`). `OpenCitationsCitation`
   calls it as `citationReference(identifier, null, null)` — OpenCitations
   supplies handles and nothing else, so the title and print detail are always
   absent.
2. `citationReference(RecordReference)` builds `Optional.of(title)` and
   `Optional.of(print)` from locals that are explicitly initialised to `null`
   and only assigned when the argument happens to be a `Record` / a
   `PrintReference`. A plain `RecordReference` therefore throws.

The second path is what `CrossRefWork.getCitations()` and
`JatsEntry.getCitations()` use for both endpoints of every link they build.

Reproduction: `mvn -o clean test` — 8 errors across five test classes, all
with `Builder.java:135`, `Builder.java:136` or `Builder.java:167` in the trace.

## Reproduced by

[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/api/opencitations/TestOpenCitationsExtraction.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/api/crossref/TestCrossRefExtraction.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/api/medrxiv/TestMedrxivRecords.java)
[TESTED_BY](/src/test/java/uk/co/terminological/bibliography/TestApiDriftParsing.java)
[TESTED_BY](/src/test/java/uk/co/terminological/citations/TestCitationLinkParsing.java)

## Resolution

Fixed by making absence explicit everywhere rather than tolerating `null` at the
boundary:

- `Builder.citationReference(RecordReference)` wraps `title` and `print` with
  `Optional.ofNullable` instead of `Optional.of`;
- `Builder.citationReference(RecordReference, Optional, Optional)` wraps the
  identifier with `Optional.ofNullable`, so an unresolved endpoint is an absent
  identifier rather than a throw;
- `OpenCitationsCitation.reference` passes `Optional.empty()` for the title and
  print detail it never has, rather than raw `null`;
- the duplicate `CitationReference.create` construction path is withdrawn, so
  `ImmutableCitationReference.builder()` is the single way to build one and the
  two paths cannot diverge again.

`mvn -o clean test`: 131 tests, 0 failures.
