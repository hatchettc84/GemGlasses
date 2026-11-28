#!/usr/bin/env bash
# Regenerates the raster BobAI brand assets (PNG favicons, .ico, spinner GIFs,
# webp logos, og-image) in assets/out/ from the SVG sources in assets/src/.
#
# Only needed when the SVG sources change: assets/out/ is kept in the repo, so
# apply.sh / build.sh never need these tools. Uses a throwaway official
# alpine container (librsvg + ImageMagick + Inter font), so nothing is
# installed on the host.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

docker run --rm -v "$HERE/assets:/assets" alpine:3.20 sh -euc '
apk add --no-cache rsvg-convert imagemagick imagemagick-webp font-inter >/dev/null
cd /assets
mkdir -p out
SRC=src/bobai-mark.svg
for s in 16 32 48 180 192 256 348 384 512; do
  rsvg-convert -w $s -h $s "$SRC" -o out/mark-$s.png
done
magick out/mark-16.png out/mark-32.png out/mark-48.png out/favicon.ico
rsvg-convert -w 1200 -h 630 src/og-image.svg -o out/og-image.png
rsvg-convert -w 720 src/bobai-lockup-on-dark.svg -o out/bobai-lockup-on-dark.png
rsvg-convert -w 720 src/bobai-lockup-on-light.svg -o out/bobai-lockup-on-light.png

# "Gradient" logos used on the instance-not-ready screen (webp, transparent).
rsvg-convert -w 192 -h 192 "$SRC" -o /tmp/m192.png
magick -size 320x192 xc:none /tmp/m192.png -gravity center -composite -quality 92 out/gradient-logo.webp
rsvg-convert -w 448 -h 448 "$SRC" -o /tmp/m448.png
magick -size 768x448 xc:none /tmp/m448.png -gravity center -composite -quality 92 out/gradient-bg-logo.webp

# Loading spinner: the mark gently pulsing (scale 78%..100%), 16 frames.
rm -rf /tmp/frames && mkdir -p /tmp/frames
i=0
for p in 78 80 84 89 94 98 100 100 98 94 89 84 80 78 78 78; do
  px=$(( 120 * p / 100 ))
  rsvg-convert -w $px -h $px "$SRC" -o /tmp/f.png
  magick -size 140x140 xc:none /tmp/f.png -gravity center -composite /tmp/frames/$(printf %02d $i).png
  i=$((i+1))
done
magick -delay 6 -loop 0 -dispose background /tmp/frames/*.png out/logo-spinner.gif
ls -la out
'
