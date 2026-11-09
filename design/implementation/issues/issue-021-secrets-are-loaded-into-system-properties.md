---
class: issue
id: issue-021-secrets-are-loaded-into-system-properties.md
title: Secrets are loaded into system properties
status: closed
sinceVersion: 0.0.6
targetVersion: 0.2.0
tags:
- bug
- security
- configuration
---
## Issue desc

`BibliographicApis.create(Path)` reads the secrets file into the JVM's own
global properties:

```java
Properties prop = System.getProperties();
prop.load(Files.newInputStream(filePath));
```

`System.getProperties()` returns the live system property table, not a copy, so
`load` writes every key in the file into it. Both `create(Path)` overloads do
this. Two consequences:

1. **Every API key in the file becomes a JVM-wide system property** for the life
   of the process — readable by any library in it, and printed by anything that
   dumps system properties into a log or a crash report.
2. **Values persist and bleed between calls.** A second `create` with a sparser
   file still sees the first file's keys, because they are already in the table;
   `prop.getProperty` cannot tell a key this file set from one a previous file
   set, or from one the JVM was started with. A caller that expects the defaults
   gets whatever was loaded earlier.

Pre-existing, and the file is the only configuration route there is
([interface-002](../../external-interfaces/interface-002-configuration-and-secrets.md)). Found
while plumbing the OpenAlex credit budget through the same method, where the
second consequence showed up as configuration carrying between tests.

## Suggested fix

Load into a fresh `Properties`, which is what the code reads as if it were doing:

```java
Properties prop = new Properties();
try (var in = Files.newInputStream(filePath)) { prop.load(in); }
```

If falling back to system properties is wanted, it can be explicit and
non-polluting: `new Properties(System.getProperties())` gives the file priority
and the system table as defaults, without writing anything back. The input stream
should be closed either way — the current code leaks it.

Worth a test that two `create` calls with different files do not see each other's
keys, which is the behaviour anyone would assume.

## Resolution — 0.2.0

Fixed as suggested. `BibliographicApis.read(Path)` loads into a fresh
`Properties` inside a try-with-resources, and both `create(Path)` overloads go
through it; nothing is written back to the system table and the stream is closed.
The fallback-to-system-properties option was not taken — the file is the whole
configuration, and a default arriving from the JVM's command line would be the
same invisible coupling in a smaller form.

Proved by `TestConfigurationIsolation` (offline): a key in the file does not
appear in `System.getProperty`, and a second `create` with a sparser file sees
the OpenAlex credit defaults rather than the first file's values. Whole offline
suite: 344 tests, 0 failures.
