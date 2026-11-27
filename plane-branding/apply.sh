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

if (( missing > 0 )); then
  echo "apply.sh: done with $missing missing asset target(s) -- check the warnings above" >&2
else
  echo "apply.sh: done"
fi
