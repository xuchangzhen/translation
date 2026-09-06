#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
ssh_target="${LINGUABRIDGE_SSH_TARGET:-}"
ssh_port="${LINGUABRIDGE_SSH_PORT:-22}"
identity_file="${LINGUABRIDGE_SSH_IDENTITY:-}"
manifest_path="${LINGUABRIDGE_ANDROID_MANIFEST:-$project_dir/release/latest-android.json}"

if [[ -z "$ssh_target" || -z "$identity_file" ]]; then
  echo "需要 LINGUABRIDGE_SSH_TARGET 和 LINGUABRIDGE_SSH_IDENTITY。" >&2
  exit 2
fi
if [[ ! -f "$manifest_path" ]]; then
  echo "未找到 Android OTA 清单：$manifest_path" >&2
  exit 2
fi

apk_name="$(node -e 'const fs=require("fs"); const path=require("path"); const m=JSON.parse(fs.readFileSync(process.argv[1])); const u=new URL(m.url); process.stdout.write(path.basename(u.pathname))' "$manifest_path")"
apk_path="$project_dir/release/$apk_name"
node -e '
  const crypto=require("crypto"), fs=require("fs"), path=require("path");
  const manifest=JSON.parse(fs.readFileSync(process.argv[1]));
  const apk=fs.readFileSync(process.argv[2]);
  const actual=crypto.createHash("sha256").update(apk).digest("hex");
  if (actual !== manifest.sha256 || apk.length !== manifest.size) throw new Error("APK 与 OTA 清单不匹配");
  if (path.basename(new URL(manifest.url).pathname) !== path.basename(process.argv[2])) throw new Error("APK 文件名不匹配");
' "$manifest_path" "$apk_path"

scp_args=(-P "$ssh_port" -i "$identity_file" -o BatchMode=yes -o ConnectTimeout=12)
ssh_args=(-p "$ssh_port" -i "$identity_file" -o BatchMode=yes -o ConnectTimeout=12)
remote_apk="/tmp/${apk_name}.incoming"
remote_manifest="/tmp/latest-android.json.incoming"
scp "${scp_args[@]}" "$apk_path" "$ssh_target:$remote_apk"
scp "${scp_args[@]}" "$manifest_path" "$ssh_target:$remote_manifest"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$remote_apk" "$remote_manifest" "$apk_name" <<'REMOTE_PUBLISH'
set -euo pipefail
remote_apk="$1"
remote_manifest="$2"
apk_name="$3"
target_dir="/srv/linguabridge-memory/android"
mkdir -p "$target_dir"
install -m 644 "$remote_apk" "$target_dir/$apk_name"
install -m 644 "$remote_manifest" "$target_dir/latest.json"
unlink "$remote_apk"
unlink "$remote_manifest"
REMOTE_PUBLISH

echo "Android 更新已发布：$(node -e 'const fs=require("fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1])).url)' "$manifest_path")"
