#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="${SRC_DIR:-src/main/java}"
OUT_DIR="${OUT_DIR:-out/classes}"

JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
JAVA="$JAVA_HOME/bin/java"

# Find Start.java (pick the one closest to SRC_DIR)
START_FILE="$(find "$SRC_DIR" -type f -name 'Start.java' \
  -printf '%d %p\n' | sort -n | head -n 1 | cut -d' ' -f2-)"

if [[ -z "${START_FILE:-}" || ! -f "$START_FILE" ]]; then
  echo "ERROR: Could not find Start.java under $SRC_DIR" >&2
  exit 1
fi

# Extract package name (if any)
PKG_LINE="$(grep -E '^\s*package\s+[^;]+;' "$START_FILE" | head -n 1 || true)"
PKG_NAME=""
if [[ -n "$PKG_LINE" ]]; then
  PKG_NAME="$(echo "$PKG_LINE" | sed -E 's/^\s*package\s+([^;]+);\s*$/\1/')"
fi

MAIN_CLASS="Start"
if [[ -n "$PKG_NAME" ]]; then
  MAIN_CLASS="${PKG_NAME}.Start"
fi

if [[ ! -d "$OUT_DIR" ]]; then
  echo "ERROR: Output directory not found: $OUT_DIR" >&2
  echo "Hint: run ./compile.sh first" >&2
  exit 1
fi

echo "Using Start.java: $START_FILE"
echo "Main class: $MAIN_CLASS"

"$JAVA" \
  --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
  --add-exports=java.base/sun.security.pkcs10=ALL-UNNAMED \
  -cp "$OUT_DIR" \
  "$MAIN_CLASS" \
  "$@"
