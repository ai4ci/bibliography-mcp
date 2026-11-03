---
title: How to put a PDF into the local Zotero
status: final
targetVersion: 0.2.0
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

## Re-probed 2026-11-03, still read-only

The library is where it was: Zotero 10.0.1, 129 collections, `Zotero-Server-ID`
present, and `api-test` (`TB35FFX6`) still **0 items** with its two
sub-collections. Nothing below wrote anything.

Two things worth adding to the record.

**The `/file` sub-resource is implemented locally, for reads.**

```
GET /api/users/0/items/MBKG4LRG/file
  302, Location: file:///home/vp22681/Zotero%20copy/storage/MBKG4LRG/2608.html
```

So the local API does serve the file surface path B uses, and it redirects into
Zotero's own storage rather than proxying bytes. That is evidence the surface
exists, not proof that its *write* half (upload authorisation, upload, register)
is implemented — which still needs probe 2 below. Note also the data directory
is `~/Zotero copy`, not `~/Zotero`.

**An open-access link is not a fetchable one**, which matters more than it
sounds. The sink is built and works, and the two obvious places to get this
project's own fixture paper both refuse a programmatic client:

| source | result |
|---|---|
| `arxiv.org/pdf/…` | **200**, 6.3 MB, a real pdf |
| `qualitysafety.bmj.com/…full.pdf` | **403** with an html body |
| `ncbi.nlm.nih.gov/pmc/articles/…/pdf` | nothing |

`best_oa_location.pdf_url` points at the publisher for most records, so workflow
2 will be attaching PDFs for a *minority* of a crawl, not for all of it.
`PdfSink` refuses a body that is not a pdf precisely so that a 403 page never
lands in the library looking like a success — but the planning should assume
partial coverage rather than treat a missing PDF as a defect.

## The write probes still need a human

Unchanged from above, and now blocking: `zotero-allow-writes` and
`zotero-write-collection` are **still unset** in the properties file, and the
first write of a session raises a dialog in the Zotero window that times out
after 120 s. Nothing can finish this prototype without someone at the keyboard.

## Probed for real, 2026-11-03 — path B works, and here is the protocol

Run against the live library, everything inside `api-test` (`TB35FFX6`). The
write dialog was approved by the developer at the keyboard.

**Four calls, all local. The upload URL Zotero hands back points at Zotero
itself** — there is no S3 step, which is the part of the Web API protocol that
does not apply here.

```
1. POST /users/0/items
   [{itemType:attachment, parentItem:<key>, linkMode:imported_file,
     title, filename, contentType:application/pdf, md5:null, mtime:null}]
   -> 200 successful.0.key = <attachmentKey>

2. POST /users/0/items/<attachmentKey>/file        If-None-Match: *
   md5=…&filename=…&filesize=…&mtime=…&params=1    (form-encoded)
   -> 200 {"url":"http://127.0.0.1:23119/api/local/uploads/<uploadKey>",
           "uploadKey":"<uploadKey>", "params":{"key":"<md5>"}}

3. POST <that url>                                 Content-Type: application/pdf
   <the bytes>
   -> 201

4. POST /users/0/items/<attachmentKey>/file        If-None-Match: *
   upload=<uploadKey>
   -> 204
```

**`POST`, not `PUT`.** The first attempt used `PUT` on the upload URL, as the
remote Web API does, and got `400 Endpoint does not support method`. That is the
single detail that would have cost an afternoon if the production code had been
written against the published protocol without probing.

### Probe 3: Zotero takes ownership of the bytes

```
GET /users/0/items/<attachmentKey>
  filename: probe.pdf      contentType: application/pdf
  md5: d02cc99fc65b940a0fe4910e85b4d0dc      <- exactly what was uploaded
  enclosure: file:///home/vp22681/Zotero%20copy/storage/2VASBFD9/probe.pdf
  enclosure length: 6364369                  <- exactly the file size
```

So Zotero copied the file into its own storage and recorded the hash itself. The
data directory is `~/Zotero copy`, which **the sandbox cannot see** — the file
was verified through the API rather than on disk, which is the stronger check
anyway: it is Zotero's own record of what it holds.

### Probe 5: a metadata update does not clobber a child

`PATCH` of the parent's title and date returned **204**, the attachment survived
with its md5 intact, and the child count was unchanged. The clean-up report's
premise holds.

It also turned up a requirement: **`PATCH` without `If-Unmodified-Since-Version`
is refused with 428**. `ZoteroClient.send` already takes a version precondition,
so the production path is fine; a probe written without one is not.

### Probe 4 was not run, deliberately

The plan was to try `saveItems`/`saveAttachment` and see where the item lands,
to disqualify path A for good. Path B now works, so the only thing probe 4 could
add is a demonstration — and the way it demonstrates is by creating an item
somewhere, most likely the **root of a 2,060-item library**. That is a bad trade
for documentation of a path already rejected on safety grounds. Left undone, on
purpose, and the recommendation stands on B's own evidence rather than on A's
failure.

### Left behind in api-test

`8NSKXPDW` (parent, "api-test bridge check") and `2VASBFD9` (its attachment,
probe.pdf, 6.3 MB). They are inside the sanctioned test collection and are left
for inspection rather than tidied away.
