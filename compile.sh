#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="${SRC_DIR:-src/main/java}"
OUT_DIR="${OUT_DIR:-out/classes}"

JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
JAVAC="$JAVA_HOME/bin/javac"

if [[ ! -d "$SRC_DIR" ]]; then
  echo "ERROR: Source directory not found: $SRC_DIR" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

# Collect all Java sources
mapfile -t SOURCES < <(find "$SRC_DIR" -type f -name '*.java' | sort)

if (( ${#SOURCES[@]} == 0 )); then
  echo "ERROR: No .java files found under $SRC_DIR" >&2
  exit 1
fi

"$JAVAC" \
  --source 25 --target 25 \
  -d "$OUT_DIR" \
  --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
  --add-exports=java.base/sun.security.pkcs10=ALL-UNNAMED \
  "${SOURCES[@]}"

echo "Compiled ${#SOURCES[@]} files into: $OUT_DIR"
