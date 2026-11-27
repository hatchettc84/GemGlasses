#!/usr/bin/env bash
# Applies the BobAI branding to a Plane checkout and builds the web, admin and space images
# with Plane's own Dockerfiles.
#
#   ./build.sh [plane-src-dir] [app ...]
#
#   plane-src-dir  default: ../../plane-src (relative to this script)
#   app            any of: web admin space (default: all three, built one after another)
#
# Environment:
#   BOBAI_TAG             image tag              (default v1.5.0-bobai1)
#   VITE_BOBAI_ADMIN_URL  "Phones & manuals" URL  (default http://localhost:5173)
#   VITE_BOBAI_SOURCE_URL public URL of BobAI's plane-branding/ source (default: none)
#   DOCKER_BUILD_FLAGS    extra flags for docker build (e.g. --no-cache, --platform linux/amd64)
#
# Build args: Plane's CI (.github/workflows/build-branch.yml) builds these images with the
# Dockerfile defaults and no --build-arg, and the official self-host images are configured
# at runtime through the proxy, so we do the same; the only extra args are the BobAI ones.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$HERE/../../plane-src}"
shift || true
APPS=("$@")
(( ${#APPS[@]} )) || APPS=(web admin space)
SRC="$(cd "$SRC" && pwd)"
TAG="${BOBAI_TAG:-v1.5.0-bobai1}"
ADMIN_URL="${VITE_BOBAI_ADMIN_URL:-http://localhost:5173}"
SOURCE_URL="${VITE_BOBAI_SOURCE_URL:-}"
# shellcheck disable=SC2206
EXTRA=(${DOCKER_BUILD_FLAGS:-})

"$HERE/apply.sh" "$SRC"

export DOCKER_BUILDKIT=1
for app in "${APPS[@]}"; do
  case "$app" in
    web)   file=apps/web/Dockerfile.web
           args=(--build-arg "VITE_BOBAI_ADMIN_URL=$ADMIN_URL" --build-arg "VITE_BOBAI_SOURCE_URL=$SOURCE_URL") ;;
    admin) file=apps/admin/Dockerfile.admin; args=() ;;
    space) file=apps/space/Dockerfile.space; args=() ;;
    *) echo "build.sh: unknown app '$app' (web|admin|space)" >&2; exit 2 ;;
  esac
  image="bobai/plane-$app:$TAG"
  echo "== building $image"
  start=$(date +%s)
  docker build ${EXTRA[@]+"${EXTRA[@]}"} ${args[@]+"${args[@]}"} \
    --label "org.opencontainers.image.title=BobAI $app (based on Plane)" \
    --label "org.opencontainers.image.licenses=AGPL-3.0-only" \
    --label "ai.bobai.upstream=https://github.com/makeplane/plane (v1.5.0)" \
    -f "$SRC/$file" -t "$image" "$SRC"
  echo "== built $image in $(( $(date +%s) - start ))s"
done
docker image ls --filter "reference=bobai/plane-*:$TAG"
