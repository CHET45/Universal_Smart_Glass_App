#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/path/to/custom-llama-runtime.aar"
  exit 1
fi

SOURCE_AAR="$1"
if [[ ! -f "$SOURCE_AAR" ]]; then
  echo "AAR file not found: $SOURCE_AAR"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEST_DIR="$REPO_ROOT/app/libs/local-llama"
DEST_AAR="$DEST_DIR/custom-llama-runtime.aar"

mkdir -p "$DEST_DIR"
cp "$SOURCE_AAR" "$DEST_AAR"

echo "Copied custom runtime AAR to: $DEST_AAR"
echo
echo "Build with local runtime enabled:"
echo "JAVA_HOME=\"/opt/android-studio/jbr\" ./gradlew :app:assembleDebug -PlocalLlamaRuntimeAarPath=app/libs/local-llama/custom-llama-runtime.aar"
