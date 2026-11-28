# plane-branding — BobAI build of Plane

BobAI's project-management UI is [Plane](https://github.com/makeplane/plane) (AGPL-3.0)
rebuilt from source with a small, re-appliable branding patch. **Everything BobAI changes in
Plane lives in this directory**; this is the "corresponding source" for our modifications
under AGPL-3.0 §13, so keep it published alongside the images.

Images produced (only the three frontends change; api/worker/live/proxy stay `makeplane/*`):

| image | Plane Dockerfile |
|---|---|
| `bobai/plane-web:v1.5.0-bobai1` | `apps/web/Dockerfile.web` |
| `bobai/plane-admin:v1.5.0-bobai1` | `apps/admin/Dockerfile.admin` |
| `bobai/plane-space:v1.5.0-bobai1` | `apps/space/Dockerfile.space` |

They are drop-in replacements for `makeplane/plane-frontend`, `makeplane/plane-admin` and
`makeplane/plane-space` of the same Plane version.

## Layout

```
apply.sh               apply the patch to a Plane checkout (idempotent)
build.sh               apply.sh + docker build of web, admin, space
make-assets.sh         re-render assets/out/ from assets/src/ (only when the logo changes)
assets/src/            SVG sources: mark, lockup (dark/light surfaces), og-image
assets/out/            generated PNG/ICO/GIF/WEBP used by apply.sh (committed)
overlay/               files copied verbatim into the Plane tree
  packages/blocks/src/icons/brand/plane-logo.tsx    BobAI mark   (replaces PlaneLogo)
  packages/blocks/src/icons/brand/plane-lockup.tsx  BobAI lockup (replaces PlaneLockup)
  apps/web/components/auth-screens/footer.tsx       sign-in footer -> AGPL notice
  apps/web/components/bobai/*                       sidebar link, help-menu items, constants
scripts/patch-code.mjs   anchored insertions (sidebar, help menu, Dockerfile.web, turbo.json)
scripts/rebrand-text.mjs "Plane" -> "BobAI" in titles, manifests and i18n VALUES
```

## Build

```bash
git clone --depth 1 --branch v1.5.0 https://github.com/makeplane/plane.git ../../plane-src
./build.sh ../../plane-src                 # all three images
./build.sh ../../plane-src web             # just one
VITE_BOBAI_ADMIN_URL=https://admin.example.com ./build.sh ../../plane-src web
```

Build-time settings (web image only, baked in by Vite):

* `VITE_BOBAI_ADMIN_URL` – target of the sidebar link "Phones & manuals"
  (opens in a new tab). Default `http://localhost:5173`.
* `VITE_BOBAI_SOURCE_URL` – public URL of this directory. If set, the help-menu entry
  "BobAI's modifications are in plane-branding/" links to it. **Set this before exposing
  BobAI to users outside the company** (AGPL §13 – network users must be offered the source).

Other env for `build.sh`: `BOBAI_TAG` (default `v1.5.0-bobai1`), `DOCKER_BUILD_FLAGS`
(e.g. `--no-cache` or `--platform linux/amd64`). Images are built for the host architecture.

## What the patch does

1. **Assets** – favicons (`.ico`, 16/32 px), apple-touch/PWA icons, android-chrome icons,
   loading-spinner GIFs, og-image and the "instance not ready" logos in web/admin/space are
   overwritten with BobAI versions. The brand SVGs are also served from the web app at
   `/bobai/` (`bobai-mark.svg`, `bobai-lockup-on-dark.svg`, `bobai-lockup-on-light.svg`).
2. **Logo components** – `PlaneLogo` / `PlaneLockup` in `@plane/blocks` are replaced (same
   export names), so every place Plane shows its logo (sign-in, onboarding, invitations,
   workspace create, admin, public space pages, …) shows BobAI.
3. **Text** – page titles, PWA manifest names, `<meta>` tags and every whole-word "Plane" in
   i18n locale values (all languages) and in app string literals become "BobAI". Keys,
   identifiers, package names, URLs, API paths, comments and copyright headers are untouched.
   Names of Plane Software's own products/services are deliberately kept (Plane Pro/One/
   Business/Enterprise/Cloud, Plane AI, Plane documentation, Plane's telemetry and marketing
   consent), as are the billing/licence/integration screens that describe Plane's paid cloud.
4. **Sidebar** – a "Phones & manuals" link to the BobAI admin page.
5. **AGPL notice** – help menu ("?" in the top bar) gets "BobAI is based on Plane (AGPL-3.0)"
   (→ github.com/makeplane/plane) and "BobAI's modifications are in plane-branding/"; the
   sign-in footer (which listed Plane's customers) shows the same notice.
6. **Build plumbing** – `Dockerfile.web` gains the two `VITE_BOBAI_*` build args, and
   `turbo.json` declares them in `globalEnv` (turbo's strict env mode would drop them otherwise).

Plane's `LICENSE.txt`, `COPYRIGHT.txt` and per-file copyright headers are left as they are.

## Upgrading Plane

```bash
cd ../../plane-src && git fetch --depth 1 origin tag vX.Y.Z && git checkout -f vX.Y.Z && git clean -fd
cd - && BOBAI_TAG=vX.Y.Z-bobai1 ./build.sh ../../plane-src
```

Always start from a **clean** checkout (`git checkout -f` + `git clean -fd`), then:

* `apply.sh` stops with `ANCHOR NOT FOUND` / `MISSING FILE` if a code insertion no longer
  fits, and warns `target missing` if an asset moved. Fix the anchor in
  `scripts/patch-code.mjs` or the path in `apply.sh`.
* If upstream changed `PlaneLogo`/`PlaneLockup` props or the auth footer, diff
  `overlay/` against the new upstream files and port the change.
* Check for new visible "Plane" text:
  `grep -rnw Plane apps/web/app apps/web/components apps/admin apps/space --include=*.tsx | grep -v Copyright`
  and adjust `PHRASES` / `PROTECTED` / `EXCLUDE` in `scripts/rebrand-text.mjs`.
* Bump the tag (`-bobai1` → next Plane version) and update the compose file's image tags.

To see exactly what the patch changed: `cd plane-src && git status && git diff`.
