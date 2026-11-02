---
class: debt
id: debt-013-record-render-swallows-its-failures.md
title: Record render swallows its failures
status: open
sinceVersion: 0.0.6
targetVersion: unassigned
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
