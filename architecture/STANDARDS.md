# Standards

Conventions observed in this codebase. Some are enforced by tooling, most rely
on review.

* **Indentation**: tabs (the Eclipse formatter settings in `.settings/` reflect
  this).
* **Java version**: 11 — `var` is used freely, records/sealed types are not.
* **Null safety in client APIs**: methods return `Optional` rather than
  throwing; bulk operations rely on Java 8 streams and incomplete results are
  filtered rather than reported. Prefer extending that pattern to new clients.
* **Fluent construction**: query builders (`OpenCitationsClient.QueryBuilder`)
  and configurators (`BiblioFacade.with…`) return `this`.
* **Logging**: SLF4J `Logger` per class; parameterised messages
  (`log.info("found {} hits", n)`), never string concatenation.
* **No dead code**: remove methods that a change made unnecessary (the
  framework's rule — do not delete code that was already dead unless asked).
* **Design links**: production code links to the design artefacts it
  implements, using a comment whose alt text is the relationship in upper
  snake case, and anchored methods are marked with a third-level heading
  comment (`### methodName`). The exact syntax is in the
  [design conventions](/design/roadmap.md#conventions).
* **Commits**: lowercase imperative subject, no type prefix, no trailing
  period.
* **Comments**: explain why, and mark deliberate simplifications with a
  `ponytail:` comment naming the ceiling and the upgrade path.
* **Secrets**: never committed; passed at runtime
  ([interface-002](/design/external-interfaces/interface-002-configuration-and-secrets.md)).
