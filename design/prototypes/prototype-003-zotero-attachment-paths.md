---
title: How to put a PDF into the local Zotero
status: draft
targetVersion: unassigned
tags:
- zotero
- pdf
- attachments
HAS_FEATURE:
- feature:feat-010-crawl-engine-with-zotero-mirror.md
---

# Prototype 003 — the two ways to attach a PDF, and which one is safe

Plan-012 assumed `zotero_sync` would need either one `/import` call or a
three-call attachment dance, and that finding out would decide how much of the
plan survives. Probed 2026-11-02 against the developer's **live** local API.
Read-only probes only — nothing here created, changed or deleted an item, and
that constraint is the reason this document is still `draft`.

## What the local API actually is

Measured from the sandbox (port 23119 is forwarded from the host loopback, so
this reaches the real library):

| fact | value |
|---|---|
| app | `X-Zotero-Version: 10.0.1` |
| connector API | `X-Zotero-Connector-API-Version: 3` |
| write identity | `Zotero-Server-ID: <present>` — a write without it is refused with **428** |
| write authorisation | `POST /api/local/authorize` raises a **dialog in the Zotero UI** and returns a single-use key unless the user picks "Always Allow" (`ZoteroClient.java:609`) |
| numeric library id | `3444309` (`user 0` is accepted as a *request* path segment only) |
| library | 129 collections, `Last-Modified-Version: 176` |
| sanctioned test target | `api-test` = key `TB35FFX6`, currently **0 items**, children `api-test-bridge-check` (`I5P5XZ47`), `api-test-bridge-check2` (`VVTFV52H`) |
| existing attachments | real, with `links.enclosure.href` = `file:///home/…/Zotero/storage/<key>/<filename>.pdf` |

So `ZoteroClient`'s existing write path (`POST /api/users/0/items` with
`Zotero-Server-ID` and, from Zotero 10, a local API key —
`ZoteroClient.java:465`) is the documented local write surface, and it works.
What it lacks is any way to send bytes.

## Two candidate paths for a PDF

### A. Connector protocol (two calls, Zotero does the storing)

`POST /connector/import` **exists**: empty POST → 400, `GET` → 400, while an
unknown connector path → 404. That 400-vs-404 difference is the only honest
evidence available without writing, and it is thin.

The connector flow, per the connector protocol and corroborated by third-party
clients: `POST /connector/saveItems` with a `sessionID` and JSON items, then
`POST /connector/saveAttachment` with the raw PDF as the body,
`Content-Type: application/pdf` and an `X-Metadata` JSON header naming the
`sessionID` and `parentItemID`. Zotero imports the file into its own storage and
writes the attachment item itself — no md5, no upload authorisation, no register
step. Two calls for metadata **and** the file.

**This is why it is tempting, and also why it is dangerous.** The connector
protocol has no concept of a target collection, and our safety property —
`checkWriteTarget` / `isDescendantOf` confining every write to
`zotero-write-collection` (`ZoteroClient.java:195`) — is enforced by *our* code on
the paths *we* build. A connector endpoint that takes a `sessionID` instead of a
collection would land items wherever Zotero puts them, which is usually the root
library. On the developer's live 2,060-item library, "wherever Zotero puts them"
is the failure mode the guard exists to prevent.

### B. Local Web-API surface (three calls, we do the storing)

The local API documents support for file uploads on the same surface we already
use for `createItems`/`updateItems`/`addTags`. The Web API protocol is: create
the child attachment item (`itemType: attachment`, `linkMode: imported_file`,
`parentItem`, `contentType`, `filename`, `md5`, `mtime`), get upload
authorisation, upload, then register the upload with the md5. Four calls by the
book; the local API may collapse the authorisation step — **unverified**.

Slower and more code, but every call goes to a path we construct, so
`checkWriteTarget` stays meaningful and the confinement guarantee survives.

## Recommendation

**B, unless a prototype proves A can be confined.** The extra two calls are
cheap — `zotero_publish` already batches with `leftForANextCall`, and this is a
per-PDF cost on a workflow a human starts deliberately. The thing not to trade
away is that nothing can write outside the sanctioned collection without our
knowledge. Plan-012's "if `/import` works, a third of the plan disappears" was
optimistic: the file still has to be fetched and named either way, so what
disappears is the md5/register code and not the plan.

## What must be probed before plan-012 item C is written up as done

All of these write, all target `api-test` (`TB35FFX6`), and each needs a dialog
approval — see the next section. Until they are done this document stays
`draft`:

1. Set `zotero-allow-writes=true` and `zotero-write-collection=<test collection>`
   in the properties file. **Currently no `zotero-*` keys are set at all**, and
   `zotero-allow-writes` defaults to `false` (`ZoteroConfig.java:47`), so writes
   are refused today by configuration. That default is the safety net; do not
   make it easier to turn on globally.
2. Via path B, attach a small PDF to an item **inside the test collection**.
   Confirm: does the local API accept `POST …/items` with an attachment child,
   and does it need the upload-authorisation step at all?
3. Confirm the resulting attachment shows an `enclosure` link and a real
   `storage/<key>/` path, i.e. that Zotero took ownership of the bytes rather
   than storing a dangling reference.
4. Then, and only then, try `saveItems`/`saveAttachment` on the same test
   collection, and observe where the item actually lands. If it lands in the
   root library, path A is disqualified for good and this document becomes
   `final` with B as the answer.
5. Check `updateItems` does not clobber an existing attachment when improving
   metadata on a parent item — the clean-up report's whole premise is that
   metadata updates are safe, and that has never been tested against a parent
   with children.

## Why this was not finished in one session

Every write needs a human to approve a dialog in the Zotero window
(`/local/authorize`, `ZoteroClient.java:609`), so an agent cannot probe the write
paths without asking — and the client then waits up to 120 s for that approval.
That is a *good* property: nothing touches the library while nobody is looking.
But plan-012 should state that `zotero_sync` has an interactive step on the first
write of a session, rather than letting the user meet it as an unexplained pause.

## Do not do

- Do not run any of the above against the root library to "see what happens".
- Do not turn on `zotero-allow-writes` without also setting
  `zotero-write-collection`; the code refuses that combination, and the refusal
  is load-bearing.
- Do not treat the 400 from `/connector/import` as proof the endpoint works.
