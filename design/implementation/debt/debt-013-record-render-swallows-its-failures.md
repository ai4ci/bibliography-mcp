---
class: debt
id: debt-013-record-render-swallows-its-failures.md
title: Record render swallows its failures
status: closed
sinceVersion: 0.0.6
targetVersion: 0.2.0
tags:
- error-handling
- citation-formatting
---
## Debt desc

`Record.render(String style)` catches every exception and returns
`Optional.empty()`, logging nothing:

```java
try {
    return Optional.of(CiteProcProvider.convert(style, Format.text, this).getEntries()[0]);
} catch (Exception e) {
    return Optional.empty();
}
```

An empty answer therefore means any of: this record cannot be rendered in this
style, the style failed to load, or the javascript engine the renderer runs on is
broken. The caller cannot tell, and neither can a log reader, because there is no
log line.

This is not hypothetical. It is how
[issue-022](../issues/issue-022-shaded-jar-cannot-render-citations.md) stayed hidden:
the citation engine was comprehensively broken in every built jar, and the only
symptom anywhere was a JSON field quietly missing from tool output.

## Suggested fix

Log the exception at WARN with the style name and the record's identifier, and
keep returning `Optional.empty()` — the exception-free contract is deliberate and
the rest of the codebase depends on it (`recordJson` treats an empty render as
"no citation field"). The debt is the silence, not the `Optional`.

Worth doing at the same time: `CiteProcProvider.streamReferences` has a bare
`catch (Exception e) { // TODO: log this? }` with the same problem.

## Closed — 0.2.0

`Record.render` logs at WARN with the style and the record it failed on, and
still returns `Optional.empty()`. The exception-free contract was never the
problem — callers depend on it, and `recordJson` treats an empty render as "no
citation field" — the silence was.

`CiteProcProvider.streamReferences`'s `catch (Exception e) { // TODO: log this? }`
is answered at the same time, with the style and the number of items it was
building for.

Pinned by `TestRenderFailsLoudly`, which captures what the code logs rather than
inspecting a console: an unloadable style warns and names both the style and the
record, a successful render stays quiet, and `render(null)` — the plain-text
form — is not a failure at all.

Worth recording why this mattered more than its size suggests: this silence is
exactly how [issue-022](../issues/issue-022-shaded-jar-cannot-render-citations.md)
hid a citation engine that was broken in **every built jar**, behind a JSON field
that was merely absent. A capability that fails quietly is indistinguishable from
one nobody asked for.
