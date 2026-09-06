#!/bin/sh
set -eu

manifest_url="https://github.com/xuchangzhen/translation/releases/latest/download/latest-android.json"
target_dir="/srv/linguabridge-memory/android"
temporary_dir="$(mktemp -d)"
cleanup() {
  find "$temporary_dir" -depth -delete
}
trap cleanup EXIT INT TERM

manifest_file="$temporary_dir/latest.json"
if ! curl --fail --silent --show-error --location \
  --max-time 30 \
  --output "$manifest_file" \
  "$manifest_url"; then
  echo "尚无可镜像的 Android 正式发布；保留服务器当前版本。"
  exit 0
fi

python3 - "$manifest_file" "$temporary_dir/metadata" <<'PY'
import json
import pathlib
import sys
import urllib.parse

manifest_path = pathlib.Path(sys.argv[1])
output_path = pathlib.Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 1 or manifest.get("packageName") != "com.linguabridge.memory":
    raise SystemExit("Android OTA 清单格式无效")
source = urllib.parse.urlparse(str(manifest.get("sourceUrl", "")))
expected_prefix = "/xuchangzhen/translation/releases/download/"
if source.scheme != "https" or source.netloc != "github.com" or not source.path.startswith(expected_prefix):
    raise SystemExit("Android OTA 上游地址无效")
sha256 = str(manifest.get("sha256", ""))
size = manifest.get("size")
if len(sha256) != 64 or any(c not in "0123456789abcdef" for c in sha256):
    raise SystemExit("Android OTA 哈希无效")
if not isinstance(size, int) or size < 1 or size > 160 * 1024 * 1024:
    raise SystemExit("Android OTA 文件大小无效")
name = pathlib.PurePosixPath(source.path).name
if not name.startswith("linguabridge-memory-") or not name.endswith(".apk"):
    raise SystemExit("Android OTA 文件名无效")
output_path.write_text(f"{source.geturl()}\n{name}\n{sha256}\n{size}\n", encoding="utf-8")
PY

source_url="$(sed -n '1p' "$temporary_dir/metadata")"
apk_name="$(sed -n '2p' "$temporary_dir/metadata")"
expected_sha256="$(sed -n '3p' "$temporary_dir/metadata")"
expected_size="$(sed -n '4p' "$temporary_dir/metadata")"
apk_file="$temporary_dir/$apk_name"
curl --fail --silent --show-error --location \
  --max-time 180 \
  --output "$apk_file" \
  "$source_url"

actual_sha256="$(sha256sum "$apk_file" | awk '{print $1}')"
actual_size="$(wc -c < "$apk_file" | tr -d ' ')"
if [ "$actual_sha256" != "$expected_sha256" ] || [ "$actual_size" != "$expected_size" ]; then
  echo "Android OTA 上游文件校验失败" >&2
  exit 1
fi

mkdir -p "$target_dir"
install -m 644 "$apk_file" "$target_dir/$apk_name"
install -m 644 "$manifest_file" "$target_dir/latest.json"
