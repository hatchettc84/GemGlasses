# Plane-native merge: Plane becomes the BobAI office app

Approved by the user on 2026-11-27 ("Plane-native", then "Yes, go ahead").

Plane (self-hosted Community v1.5.0) becomes the office UI. It is renamed **BobAI**. The separate
BobAI console shrinks to a small admin page for what Plane has no place for (phone pairing, manual
approval). Three parts, built and verified in order.

## Part 1 — Notes and drafts in Plane
- A sticky also carries `jobId` (the job's id as the phone has it, e.g. `plane:<work-item uuid>`; null if none).
- On every sticky upload (`PUT /api/device/stickies/:id`, which also accepts optional `jobId` ≤ 100 chars),
  the Worker stores the row as today, then mirrors it to Plane when Plane mode is on:
  - Job is a Plane work item → a **comment** on that work item.
  - No job (or not a Plane job) → a **work item** in the Plane project whose identifier is `NOTES`
    ("Field Notes"; found by identifier, no extra config). If it does not exist, the Worker creates it once
    through the Plane API (name "Field Notes", identifier `NOTES`).
  - Body (HTML, all user text escaped): heading `📝 Note from <technician>` or `✉️ Draft from <technician>`,
    then the title in bold, then the text with line breaks, then `Saved <time> from BobAI` and `✅ Done` when done.
    Work items in Field Notes use the sticky title as their name.
  - Update → edit the same comment / work item. Delete → delete it in Plane.
- Plane ids and a sync state are stored on the sticky row. If Plane is slow or down (it freezes for
  stretches), the sticky is still saved and answered `200`; the Plane mirror is retried later
  (on that technician's next sticky upload or jobs fetch, in the background). A deleted sticky whose
  Plane copy could not be removed yet is kept as a tombstone until the delete succeeds.
- Never log titles or text; never log the Plane key. Comments are posted by the Plane API key's user;
  the technician's name is in the body.

## Part 2 — Plane renamed BobAI
- Build our own `web`, `admin` and `space` images from Plane v1.5.0 source plus a small patch kept in
  this repo: BobAI logo, favicon, page titles, visible "Plane" → "BobAI", and a sidebar link
  "Phones & manuals" to the BobAI admin page. Compose uses these images; instance name set to BobAI.
- AGPL-3.0: keep Plane's license notices, keep "Based on Plane (AGPL-3.0)" with a link to our source on
  the About/help surface, and publish the patch.

## Part 3 — BobAI admin page
- The console keeps People (roles, phone pairing) and Manuals (upload, review, approve, revoke).
  Jobs and Notes pages are removed (they live in Plane). Restyled as "BobAI Admin" with a link back to BobAI (Plane).
