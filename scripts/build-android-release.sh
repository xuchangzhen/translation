#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
android_dir="$project_dir/android-memory"
keystore_path="${LINGUABRIDGE_ANDROID_KEYSTORE:-$HOME/Library/Application Support/LinguaBridge/signing/android-memory-release.p12}"
key_alias="${LINGUABRIDGE_ANDROID_KEY_ALIAS:-linguabridge-memory}"
keychain_service="com.linguabridge.android-signing"
keychain_account="release"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

if [[ ! -f "$keystore_path" ]]; then
  echo "未找到 Android 发布签名库：$keystore_path" >&2
  exit 1
fi
if ! command -v security >/dev/null; then
  echo "当前环境无法读取 macOS 钥匙串中的 Android 签名密码。" >&2
  exit 1
fi
signing_password="$(security find-generic-password -s "$keychain_service" -a "$keychain_account" -w)"

export ANDROID_HOME="$android_sdk"
export LINGUABRIDGE_ANDROID_KEYSTORE="$keystore_path"
export LINGUABRIDGE_ANDROID_STORE_PASSWORD="$signing_password"
export LINGUABRIDGE_ANDROID_KEY_ALIAS="$key_alias"
export LINGUABRIDGE_ANDROID_KEY_PASSWORD="$signing_password"

"$android_dir/gradlew" -p "$android_dir" testDebugUnitTest lintDebug assembleRelease

metadata="$android_dir/app/build/outputs/apk/release/output-metadata.json"
built_apk="$android_dir/app/build/outputs/apk/release/app-release.apk"
version_name="$(node -e 'const m=require(process.argv[1]); process.stdout.write(String(m.elements[0].versionName))' "$metadata")"
release_dir="$project_dir/release"
release_apk="$release_dir/linguabridge-memory-$version_name.apk"
mkdir -p "$release_dir"
cp "$built_apk" "$release_apk"
LINGUABRIDGE_ANDROID_BASE_URL="${LINGUABRIDGE_ANDROID_BASE_URL:-https://memory.xuchangzhen968.top}" \
  node "$script_dir/create-android-update-manifest.mjs" \
  "$release_apk" "$metadata" "$release_dir/latest-android.json"

echo "Android 发布包：$release_apk"
echo "Android OTA 清单：$release_dir/latest-android.json"
