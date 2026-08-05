#!/bin/bash

# Automatic APK signing script for AOSP system apps
# - Auto-detects apksigner and zipalign in tools/
# - Signs with platform.pk8 + platform.x509.pem

if [ $# -ne 1 ]; then
    echo "Usage: ./sign_auto.sh <unsigned-apk>"
    exit 1
fi

APK="$1"
# Get absolute path of input APK
APK_DIR="$(cd "$(dirname "$APK")" &> /dev/null && pwd)"
BASE="$(basename "$APK" .apk)"
OUT_DIR="$APK_DIR/${BASE}_output"

if [ ! -d "$OUT_DIR" ]; then
    mkdir -p "$OUT_DIR"
fi

ALIGNED="$OUT_DIR/${BASE}_aligned.apk"
SIGNED="$OUT_DIR/${BASE}_platform_signed.apk"

echo "==================================================="
echo "Starting APK Signing Process"
echo "==================================================="
echo "Input APK: \"$APK\""
echo "Output Directory: \"$OUT_DIR\""
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
TOOLS_DIR="$SCRIPT_DIR/Tools"

APKSIGNER="$TOOLS_DIR/apksigner"
ZIPALIGN="$TOOLS_DIR/zipalign"
PK8_KEY="$TOOLS_DIR/platform.pk8"
PEM_CERT="$TOOLS_DIR/platform.x509.pem"

# --- Verify tools ---
if [ ! -f "$APKSIGNER" ]; then
    echo "ERROR: apksigner not found in $TOOLS_DIR!"
    exit 1
fi

if [ ! -f "$ZIPALIGN" ]; then
    echo "ERROR: zipalign not found in $TOOLS_DIR!"
    exit 1
fi

if [ ! -f "$PK8_KEY" ]; then
    echo "ERROR: platform.pk8 missing in $SCRIPT_DIR!"
    exit 1
fi

if [ ! -f "$PEM_CERT" ]; then
    echo "ERROR: platform.x509.pem missing in $SCRIPT_DIR!"
    exit 1
fi

echo "[Info] Tools located successfully."
echo ""

# --- Align APK ---
echo "[1/2] Aligning APK..."
export LD_LIBRARY_PATH="$TOOLS_DIR/lib64:$LD_LIBRARY_PATH"
"$ZIPALIGN" -f 4 "$APK" "$ALIGNED"
if [ $? -ne 0 ]; then
    echo "[ERROR] Zipalign failed!"
    exit 1
fi
echo "[Success] APK aligned properly."
echo ""

# --- Sign APK ---
echo "[2/2] Signing APK with platform keys..."
"$APKSIGNER" sign \
  --key "$PK8_KEY" \
  --cert "$PEM_CERT" \
  --out "$SIGNED" \
  "$ALIGNED"

if [ $? -ne 0 ]; then
    echo "[ERROR] Apksigner failed!"
    exit 1
fi
echo "[Success] APK signed securely."
echo ""

echo "==================================================="
echo "DONE! Process completed successfully."
echo "Signed APK: \"$SIGNED\""
echo "==================================================="
