#!/usr/bin/env bash
# Downloads the InsightFace ArcFace model (w600k_r50.onnx from the buffalo_l pack) from a pinned URL,
# verifies sha256 of both the zip and the extracted model, and installs it as an app asset.
# Licence: InsightFace pretrained models are for NON-COMMERCIAL RESEARCH purposes only.
set -euo pipefail

URL="https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
ZIP_SHA256="80ffe37d8a5940d59a7384c201a2a38d4741f2f3c51eef46ebb28218a7b0ca2f"
MODEL_IN_ZIP="w600k_r50.onnx"
MODEL_SHA256="4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/arcface_w600k_r50.onnx"
CACHE="${FACESORT_MODEL_CACHE:-$ROOT/.model-cache}"
ZIP="$CACHE/buffalo_l.zip"

sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ -f "$DEST" ] && [ "$(sha "$DEST")" = "$MODEL_SHA256" ]; then
  echo "Model already present and verified: $DEST"; exit 0
fi

mkdir -p "$CACHE" "$(dirname "$DEST")"
if [ ! -f "$ZIP" ] || [ "$(sha "$ZIP")" != "$ZIP_SHA256" ]; then
  echo "Downloading $URL ..."
  rm -f "$ZIP"
  curl -L --fail --retry 3 --retry-delay 5 -o "$ZIP.part" "$URL"
  mv "$ZIP.part" "$ZIP"
fi

GOT="$(sha "$ZIP")"
if [ "$GOT" != "$ZIP_SHA256" ]; then
  echo "ERROR: sha256 mismatch for buffalo_l.zip" >&2
  echo "  expected $ZIP_SHA256" >&2
  echo "  got      $GOT" >&2
  rm -f "$ZIP"; exit 1
fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -o -j -q "$ZIP" "$MODEL_IN_ZIP" -d "$TMP"
GOT="$(sha "$TMP/$MODEL_IN_ZIP")"
if [ "$GOT" != "$MODEL_SHA256" ]; then
  echo "ERROR: sha256 mismatch for $MODEL_IN_ZIP" >&2
  echo "  expected $MODEL_SHA256" >&2
  echo "  got      $GOT" >&2
  exit 1
fi
mv "$TMP/$MODEL_IN_ZIP" "$DEST"
echo "Installed and verified: $DEST ($(wc -c < "$DEST") bytes)"
