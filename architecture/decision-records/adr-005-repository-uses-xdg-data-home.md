---
title: Repository and cache live under the XDG base directories
status: draft
targetVersion: unassigned
tags:
- persistence
- configuration
- portability
---

# Put the repository under `$XDG_DATA_HOME`, not in a per-call path

> Status `draft` because the loom `DocStatus` set has no `proposed`: this is a
> decision awaiting the developer's yes, not an accepted record. The model
> cannot express "proposed" for a document — only issues and debt have an
> open/closed axis.

## Context

plan-010 needs a default location for the on-disk repository. The alternatives
were an explicit path on every `save`, an implicit directory, or reusing the
existing `cache-directory` property.

Facts on the developer's machine, measured 2026-11-02:

- `XDG_DATA_HOME` is **unset**; `~/.local/share` exists and already holds
  `billion-context`, `claude`, `keyrings`, `fonts`, `applications` — so the
  convention is in use here by other tools even though the variable is not
  exported.
- `cache-directory` is set in `~/.private/apis/secrets.prop` and resolves under
  `/tmp` (`/tmp/ehcache` exists, 504K). `/tmp` is the wrong place for the
  repository: it is wiped on reboot, and the whole point of plan-010 is a
  repository that outlives the process and ideally the machine session.

The two properties are answering different questions and must not be conflated:
the HTTP cache is rebuildable, the repository is not.

## Decision

One implicit root, resolved the XDG way, with a single override:

| thing | default | override |
|---|---|---|
| repository | `${XDG_DATA_HOME:-$HOME/.local/share}/bibliographic-api-client/repositories/` | `repository-directory` |
| PDFs | `${XDG_DATA_HOME:-$HOME/.local/share}/bibliographic-api-client/pdfs/` | same override, as a sibling |
| HTTP cache | unchanged: `cache-directory`, currently under `/tmp` | — |

`repository-directory` names the *parent*; each repository is a subdirectory of
it holding the store file and its `pdfs/`. That keeps `prag` pointed at a plain
directory tree and keeps a saved repository portable by copying one directory.

## Rationale

- **The developer's question was "one repository directory?" and the answer that
  follows from that is one parent, several repositories.** Named repositories map
  onto "create a repository from these search results" — the agent names it, and
  `repository(action=load)` takes a name rather than a path. Explicit-path-per-save
  was rejected: it pushes a filesystem decision into every tool call, and an agent
  given a free path will invent inconsistent ones.
- **`~/.local/share` is the right home because the repository is user data, not
  configuration and not a cache.** `~/.config` would be wrong (it is not
  configuration) and a cache directory would be destroyed by anything that
  legitimately cleans caches.
- **Following the spec rather than hardcoding `~/.local/share` costs one line**
  and means `XDG_DATA_HOME` is honoured where it *is* set — which matters for
  sandboxes and for anyone who moves their home directory.

## Consequences

- `interface-002` gains a `repository-directory` row, and needs to say what
  happens when the directory does not exist yet (create it, and report the path
  it created — silent creation somewhere surprising is how people lose work).
- `save_repository` / `load_repository` arguments become a repository *name*,
  with a path still accepted for compatibility and for the `.ser` migration case
  in plan-010, where the input is an existing file someone already has.
- A repository is now a directory, so `debt-001`'s migration should move the old
  `.ser` file aside rather than deleting it.
- Nothing in this ADR requires the repository and the HTTP cache to share a
  parent, and they should not: moving `cache-directory` into
  `$XDG_CACHE_HOME` is a separate, smaller decision (it is `/tmp` today, which is
  defensible for a cache).

## Open questions

- Does a repository directory also hold the crawl job state, or does job state
  belong with the store and therefore inside it? plan-011 assumes the latter; this
  ADR does not contradict that, but the layout should be drawn once.
- Should `BibliographicApis` create the directory eagerly at startup, or lazily on
  first save? Lazy is less surprising; eager gives a better error message when the
  home directory is read-only.
