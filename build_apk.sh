#!/usr/bin/env bash
# 七步手工构建 app-debug.apk（不依赖 Gradle，纯 aapt2/javac/d8/zipalign/apksigner）。
# 用法：ANDROID_SDK_ROOT=/path/to/sdk bash build_apk.sh
set -euo pipefail

# 始终基于脚本所在目录（仓库根），确保相对路径稳定
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/sdk}}"
BUILD="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34"
ANDROID_JAR="$PLAT/android.jar"

AAPT2="$BUILD/aapt2"
D8="$BUILD/d8"
ZIPALIGN="$BUILD/zipalign"
APKSIGNER="$BUILD/apksigner"

MIN_SDK=21
TARGET_SDK=34

SRC_DIR="java/com/example/reader"
STAGE="build"
PKG="$STAGE/app-debug.apk"
KEYSTORE="$STAGE/debug.keystore"

mkdir -p "$STAGE"

echo "==> 0. 检查工具链"
for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
  [ -e "$t" ] || { echo "MISSING: $t"; exit 1; }
done

echo "==> 1. aapt2 link (manifest -> base.apk)"
"$AAPT2" link --manifest AndroidManifest.xml \
  -I "$ANDROID_JAR" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  -o "$STAGE/base.apk"

echo "==> 2. javac compile"
rm -rf "$STAGE/classes"
mkdir -p "$STAGE/classes"
javac -d "$STAGE/classes" -classpath "$ANDROID_JAR" "$SRC_DIR/MainActivity.java"

echo "==> 3. d8 -> classes.dex"
"$D8" --release --output "$STAGE/classes.dex" $(find "$STAGE/classes" -name '*.class')

echo "==> 4. assemble apk (base + classes.dex + assets)"
cp "$STAGE/base.apk" "$STAGE/unsigned.apk"
( cd "$STAGE" \
  && rm -rf pack && mkdir -p pack \
  && cp classes.dex pack/ \
  && cp -r ../assets pack/assets \
  && cd pack \
  && zip -q -r ../unsigned.apk classes.dex assets )

echo "==> 5. zipalign"
"$ZIPALIGN" -p 4 "$STAGE/unsigned.apk" "$STAGE/aligned.apk"

echo "==> 6. debug keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "==> 7. apksigner"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$PKG" "$STAGE/aligned.apk"

echo ""
echo "BUILD OK -> $PKG"
ls -la "$PKG"
