---
class: issue
id: issue-022-shaded-jar-cannot-render-citations.md
title: Shaded jar cannot render citations
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
tags:
- bug
- packaging
- citation-formatting
- mcp
---
## Issue desc

`Record.render(style)` works on the ordinary classpath and **always fails in the
shaded jar**, which is the only way the MCP server is run. It fails silently:
`render` catches the exception and returns `Optional.empty()`, so `recordJson`
simply omits the `citation` field.

The same call, same record, same style:

```
# target/classes + dependencies
raw record render: Optional[[1]R. Challen, "Artificial intelligence, bias and clinical safety," BMJ Quality & Safety.]

# target/bibliographic-api-client-0.0.11-SNAPSHOT.jar
raw record render: Optional.empty
SyntaxError: No language for id regex found. Supported languages are: [js]
Unnamed:84:14 Expected ; but found :
        "art.": "article",
```

The parse errors are a symptom, not the cause: without the `regex` language
GraalJS cannot parse the regular-expression literals in `citeproc.js`, and
reports the wreckage as syntax errors in the object literals that follow.

## Cause

citeproc-java evaluates `citeproc.js` through GraalJS, and GraalJS declares a
dependency on a second Truffle language:

```
language1.id=js
language1.dependentLanguage.0=regex
```

Truffle languages register through **`META-INF/truffle/language`**, and both
`js-19.2.1.jar` and `regex-19.2.1.jar` contain a file at that exact path. The
shade plugin's `ServicesResourceTransformer` merges `META-INF/services/*` only,
so nothing merges this one: the first copy wins and the second is dropped. The
shaded jar contains one `META-INF/truffle/language`, the one from `js`, and the
`regex` language is therefore unregistered at runtime.

Verified by listing the jars:

| jar | has `META-INF/truffle/language` |
|---|---|
| `js-19.2.1.jar` | yes — declares `js`, depending on `regex` |
| `regex-19.2.1.jar` | yes — declares `regex` |
| the shaded jar | one file, `js` only |

Pre-existing and as old as the shaded jar. It is invisible from the test suite,
because tests run on the ordinary classpath where both registrations are separate
files in separate jars, and invisible from `scripts/mcp-smoke-test.sh`, because
that calls `citation_styles` — which only lists style names and never renders.

## Impact

Every citation-rendering path in the deployed server, which is all of them:

- `citation_style` ([issue-019](issue-019-citation-styles-cannot-be-selected.md))
  selects a style successfully and no record ever carries a `citation` field.
- `Record.render` returning `Optional.empty()` is indistinguishable from a style
  that does not apply, so nothing reports the failure.

## Suggested fix

The two registration files cannot simply be concatenated — both number their
entries `language1.*`, so appending them would make one shadow the other. Options,
in order of preference:

1. **Do not shade GraalJS.** Ship citeproc and its JS engine as ordinary
   dependencies alongside the jar (`Class-Path` in the manifest, or a `lib/`
   directory), which is also what GraalVM expects of a Truffle language.
2. **Rewrite the merged file during shading**, with a transformer that renumbers
   the `languageN.` prefixes as it appends. This is a custom transformer — the
   stock `AppendingTransformer` would produce the shadowing described above.
3. **Make the failure loud**, whichever of the above is chosen: `Record.render`
   should log the exception rather than swallowing it. A capability that silently
   does nothing is why this survived as long as it has.

Whichever is chosen, the smoke test should render one citation rather than only
listing styles, since that is the assertion that would have caught this.
