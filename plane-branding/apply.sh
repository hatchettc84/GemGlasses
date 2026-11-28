#!/usr/bin/env bash
# Applies the BobAI branding to a clean Plane checkout. Idempotent: safe to run repeatedly.
#
#   ./apply.sh <plane-src-dir>
#
# Steps: copy brand assets over Plane's logo/favicon files, copy overlay files (BobAI logo
# components, AGPL footer, sidebar/help-menu components), make the anchored code insertions,
# then swap the visible product name in titles, manifests and i18n values.
# Requires: bash, node (>= 18). Plane's LICENSE/COPYRIGHT files are never touched.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-}"
if [[ -z "$SRC" || ! -d "$SRC/apps/web" || ! -f "$SRC/turbo.json" ]]; then
  echo "usage: $0 <plane-src-dir>   (a Plane monorepo checkout)" >&2
  exit 2
fi
SRC="$(cd "$SRC" && pwd)"
OUT="$HERE/assets/out"
command -v node >/dev/null || { echo "apply.sh: node is required" >&2; exit 1; }

missing=0
# put <asset> <relative-target>: overwrite a Plane asset; warn if Plane no longer has it.
put() {
  local from="$OUT/$1" to="$SRC/$2"
  if [[ ! -e "$to" ]]; then
    echo "apply.sh: WARNING target missing (Plane layout changed?): $2" >&2
    missing=$((missing + 1))
    mkdir -p "$(dirname "$to")"
  fi
  cp "$from" "$to"
}

echo "== assets"
for app in web admin space; do
  put favicon.ico     "apps/$app/app/assets/favicon/favicon.ico"
  put mark-16.png     "apps/$app/app/assets/favicon/favicon-16x16.png"
  put mark-32.png     "apps/$app/app/assets/favicon/favicon-32x32.png"
  put mark-180.png    "apps/$app/app/assets/favicon/apple-touch-icon.png"
  put mark-192.png    "apps/$app/public/favicon/android-chrome-192x192.png"
  put mark-512.png    "apps/$app/public/favicon/android-chrome-512x512.png"
  put logo-spinner.gif "apps/$app/app/assets/images/logo-spinner-dark.gif"
  put logo-spinner.gif "apps/$app/app/assets/images/logo-spinner-light.gif"
done
put mark-180.png         "apps/web/app/assets/icons/icon-180x180.png"
put mark-512.png         "apps/web/app/assets/icons/icon-512x512.png"
put mark-192.png         "apps/web/public/icons/icon-192x192.png"
put mark-348.png         "apps/web/public/icons/icon-348x348.png"
put mark-512.png         "apps/web/public/icons/icon-512x512.png"
put og-image.png         "apps/web/app/assets/og-image.png"
put gradient-logo.webp   "apps/web/app/assets/auth/gradient-logo.webp"
put gradient-bg-logo.webp "apps/web/app/assets/auth/gradient-bg-logo.webp"
# Plain brand files served by the web app (handy for other BobAI surfaces and smoke tests).
mkdir -p "$SRC/apps/web/public/bobai"
cp "$HERE/assets/src/bobai-mark.svg" "$HERE/assets/src/bobai-lockup-on-dark.svg" \
   "$HERE/assets/src/bobai-lockup-on-light.svg" "$SRC/apps/web/public/bobai/"
cp "$OUT/mark-512.png" "$SRC/apps/web/public/bobai/bobai-mark-512.png"

echo "== overlay files"
( cd "$HERE/overlay" && find . -type f ) | while read -r f; do
  mkdir -p "$SRC/$(dirname "$f")"
  cp "$HERE/overlay/$f" "$SRC/$f"
  echo "   ${f#./}"
done

echo "== code insertions"
node "$HERE/scripts/patch-code.mjs" "$SRC"

echo "== text (titles, manifests, i18n values)"
node "$HERE/scripts/rebrand-text.mjs" "$SRC"

# Plane's Caddy serves pages with no Cache-Control, so browsers may reuse an old index.html for
# days and keep running the previous build (seen after switching to the BobAI images). Pages are
# revalidated on every load; content-hashed assets are cached for good.
echo "== caddy cache headers"
for app in web admin; do
  cf="$SRC/apps/$app/caddy/Caddyfile"
  if [[ ! -f "$cf" ]]; then
    echo "apply.sh: WARNING missing $cf" >&2; missing=$((missing + 1)); continue
  fi
  if ! grep -q "bobai_assets" "$cf"; then
    perl -0pi -e 's|(\n\t# SPA fallback)|\n\t# BobAI: always revalidate pages; hashed assets never change.\n\t\@bobai_assets path /assets/* /god-mode/assets/*\n\theader \@bobai_assets Cache-Control "public, max-age=31536000, immutable"\n\theader \@bobai_pages Cache-Control "no-cache"\n\t\@bobai_pages not path /assets/* /god-mode/assets/*\n$1|' "$cf"
    grep -q "bobai_assets" "$cf" || { echo "apply.sh: WARNING could not patch $cf" >&2; missing=$((missing + 1)); }
  fi
  # Plane's per-address limit of 300 static requests a minute is hit by a single first load of
  # the app (it lazily loads hundreds of chunks), and all users behind one address share it.
  # Hashed assets are now cached for good, so a higher ceiling costs little.
  perl -pi -e 's/^(\s*events) 300$/$1 3000/' "$cf"
  echo "   apps/$app/caddy/Caddyfile"
done

if (( missing > 0 )); then
  echo "apply.sh: done with $missing missing asset target(s) -- check the warnings above" >&2
else
  echo "apply.sh: done"
fi
