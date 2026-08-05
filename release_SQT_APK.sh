#!/bin/bash

# Exit on any error
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
SIGNER_DIR="$PROJECT_DIR/SQT-APK-Signer"

cd "$PROJECT_DIR"

# Parse arguments or prompt user interactively
COPY_DEST=""
if [ -n "$1" ]; then
    GRADLE_TASK="$1"
    if [ -n "$2" ]; then
        COPY_DEST="$2"
    fi
else
    echo "=========================================================="
    echo " 📋 Please select a build type to generate and sign:"
    echo "=========================================================="
    options=(
        "assembleRelease"
        "assembleDebug"
        "Quit"
    )
    select opt in "${options[@]}"; do
        case $opt in
            "assemble"*)
                GRADLE_TASK=":composeApp:$opt"
                echo ""
                break
                ;;
            "Quit")
                echo "Pipeline aborted."
                exit 0
                ;;
            *) echo "❌ Invalid option $REPLY, please try again." ;;
        esac
    done
    
    echo ""
    read -p "Do you want to copy the release directory to a destination? (y/n): " COPY_ANS
    if [[ "$COPY_ANS" == "y" || "$COPY_ANS" == "Y" ]]; then
        read -p "Please enter the destination path: " COPY_DEST
    fi
fi

echo "=========================================================="
echo "    🚀 SQT PIPELINE: BUILD & SIGNING"
echo "=========================================================="
echo "Task     : $GRADLE_TASK"
echo "Start    : $(date +'%T')"
echo ""

GRADLE_FILE="composeApp/build.gradle.kts"
if [ -f "$GRADLE_FILE" ]; then
    VERSION_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -n 1)
    VERSION_NAME=$(grep -E '^[[:space:]]*versionName[[:space:]]*=[[:space:]]*".*"' "$GRADLE_FILE" | awk -F '"' '{print $2}' | head -n 1)
    
    echo "=========================================================="
    echo " ℹ️ Current Version Info"
    echo "=========================================================="
    echo " Version Name: $VERSION_NAME"
    echo " Version Code: $VERSION_CODE"
    echo "=========================================================="
    
    read -p "Do you want to change the version name and code? (y/N): " CHANGE_VER
    if [[ "$CHANGE_VER" == "y" || "$CHANGE_VER" == "Y" ]]; then
        read -p "Enter new Version Name (e.g. 1.0.1): " NEW_VNAME
        read -p "Enter new Version Code (e.g. 2): " NEW_VCODE
        
        if [ -n "$NEW_VNAME" ] && [ -n "$NEW_VCODE" ]; then
            sed -i "s/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]\+/        versionCode = $NEW_VCODE/g" "$GRADLE_FILE"
            sed -i "s/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*\".*\"/        versionName = \"$NEW_VNAME\"/g" "$GRADLE_FILE"
            VERSION_NAME="$NEW_VNAME"
            echo "✅ Version updated to $NEW_VNAME ($NEW_VCODE)."
            echo ""
        else
            echo "❌ Invalid input. Proceeding with original version."
            echo ""
        fi
    fi
fi

# ---------------------------------------------------------
# STEP 1: CLEAN AND BUILD
# ---------------------------------------------------------
echo "[1/2] 🔨 STEP 1: Cleaning and Building Project..."
echo "      (Estimated time: 1-3 minutes depending on gradle cache)"
START_BUILD=$SECONDS

# Execute gradle build
./gradlew clean $GRADLE_TASK

BUILD_DURATION=$(( SECONDS - START_BUILD ))
echo "      ✅ Build completed in ${BUILD_DURATION} seconds."
echo ""

# ---------------------------------------------------------
# FIND THE GENERATED APK
# ---------------------------------------------------------
echo "      🔍 Locating newly generated APK..."
# Find the most recently modified APK in the output directory, excluding previously signed/aligned ones
APK_PATH=$(find composeApp/build/outputs/apk/ -name "*.apk" -not -name "*_aligned.apk" -not -name "*_signed.apk" -type f -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)

if [ -z "$APK_PATH" ]; then
    echo "      ❌ ERROR: No APK found after build! Pipeline aborted."
    exit 1
fi

APK_ABS_PATH="$PROJECT_DIR/$APK_PATH"
echo "      🎯 Found APK: $APK_ABS_PATH"
echo ""

# ---------------------------------------------------------
# STEP 2: SIGN WITH AOSP PLATFORM KEYS
# ---------------------------------------------------------
echo "[2/2] 🔐 STEP 2: Signing APK with SQT-APK-Signer..."
echo "      (Estimated time: ~5-15 seconds)"
START_SIGN=$SECONDS

if [ ! -f "$SIGNER_DIR/sign_auto.sh" ]; then
    echo "      ❌ ERROR: sign_auto.sh not found in $SIGNER_DIR!"
    exit 1
fi

# We must ensure sign_auto.sh is executable
chmod +x "$SIGNER_DIR/sign_auto.sh"
# Execute the standalone signer tool on the absolute path of the new APK
cd "$SIGNER_DIR"
./sign_auto.sh "$APK_ABS_PATH"

SIGN_DURATION=$(( SECONDS - START_SIGN ))
echo "      ✅ Signing process finished in ${SIGN_DURATION} seconds."
echo ""

TOTAL_TIME=$(( SECONDS - START_BUILD ))
echo "=========================================================="
echo " 🎉 PIPELINE COMPLETED SUCCESSFULLY!"
echo " ⏱️  Total Pipeline Time: ${TOTAL_TIME} seconds."
echo "=========================================================="

if [ -n "$COPY_DEST" ]; then
    APK_DIR="$(cd "$(dirname "$APK_ABS_PATH")" &> /dev/null && pwd)"
    BASE="$(basename "$APK_ABS_PATH" .apk)"
    OUT_DIR="$APK_DIR"
    FINAL_DEST="$COPY_DEST/$VERSION_NAME"
    
    echo ""
    echo "Copying release directory to $FINAL_DEST..."
    mkdir -p "$FINAL_DEST"
    if cp -r "$OUT_DIR"/* "$FINAL_DEST/"; then
        echo "✅ Successfully copied to $FINAL_DEST"
    else
        echo "❌ ERROR: Failed to copy to $FINAL_DEST"
    fi
fi
