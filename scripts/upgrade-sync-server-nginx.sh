#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_dir="$project_dir/sync-server"
receipt_path="${LINGUABRIDGE_DEPLOYMENT_RECEIPT:-$source_dir/deployment.receipt.env}"

if [[ ! -f "$receipt_path" ]]; then
  echo "未找到部署回执：$receipt_path" >&2
  exit 2
fi

set -a
. "$receipt_path"
set +a

ssh_target="${LINGUABRIDGE_SSH_TARGET:-${SSH_TARGET:-}}"
ssh_port="${LINGUABRIDGE_SSH_PORT:-22}"
remote_dir="${LINGUABRIDGE_REMOTE_DIR:-${REMOTE_DIR:-linguabridge-sync}}"
backend_port="${LINGUABRIDGE_BACKEND_PORT:-${BACKEND_PORT:-18787}}"
identity_file="${LINGUABRIDGE_SSH_IDENTITY:-$HOME/.ssh/linguabridge_deploy_ed25519}"

if [[ -z "$ssh_target" || ! -f "$identity_file" ]]; then
  echo "部署回执或专用 SSH 密钥不完整。" >&2
  exit 2
fi

ssh_args=(-p "$ssh_port" -i "$identity_file" -o BatchMode=yes -o ConnectTimeout=12)
scp_args=(-P "$ssh_port" -i "$identity_file" -o BatchMode=yes -o ConnectTimeout=12)
temporary_dir="$(mktemp -d)"
cleanup() {
  if [[ -d "${temporary_dir:-}" ]]; then find "$temporary_dir" -depth -delete; fi
}
trap cleanup EXIT

archive_path="$temporary_dir/linguabridge-sync.tgz"
tar -C "$source_dir" --exclude=.env --exclude=node_modules --exclude=test -czf "$archive_path" .
remote_archive="/tmp/linguabridge-sync-upgrade-${RANDOM}-${RANDOM}.tgz"

echo "[1/4] 检查现有 LinguaBridge 服务"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$remote_dir" "$backend_port" <<'REMOTE_CHECK'
set -euo pipefail
target_dir="$PWD/$1"
backend_port="$2"
for name in linguabridge-memory-postgres linguabridge-memory-relay; do
  owner="$(docker container inspect --format '{{ index .Config.Labels "com.linguabridge.owner" }}' "$name" 2>/dev/null || true)"
  [[ "$owner" == "translation-project" ]] || { echo "$name 不是本项目容器，停止升级。" >&2; exit 1; }
done
[[ -f "$target_dir/.env" ]] || { echo "远程加密环境文件不存在。" >&2; exit 1; }
curl --fail --silent "http://127.0.0.1:$backend_port/healthz" >/dev/null
REMOTE_CHECK

echo "[2/4] 上传并构建候选版本"
scp "${scp_args[@]}" "$archive_path" "$ssh_target:$remote_archive"

echo "[3/4] 原子切换中继（失败自动回滚）"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$remote_dir" "$remote_archive" "$backend_port" <<'REMOTE_UPGRADE'
set -euo pipefail
remote_dir="$1"
remote_archive="$2"
backend_port="$3"
target_dir="$PWD/$remote_dir"
staging_dir="${target_dir}.upgrade.$$"
relay_name="linguabridge-memory-relay"
backup_name="${relay_name}-rollback-$$"
candidate_image="linguabridge-memory-relay:candidate-$$"

rollback() {
  docker rm -f "$relay_name" >/dev/null 2>&1 || true
  if docker container inspect "$backup_name" >/dev/null 2>&1; then
    docker rename "$backup_name" "$relay_name"
    docker start "$relay_name" >/dev/null
  fi
  docker image rm "$candidate_image" >/dev/null 2>&1 || true
}
trap 'rollback; find "$staging_dir" -depth -delete 2>/dev/null || true; unlink "$remote_archive" 2>/dev/null || true' ERR

mkdir -p "$staging_dir"
tar -xzf "$remote_archive" -C "$staging_dir"
install -m 600 "$target_dir/.env" "$staging_dir/.env"
set -a
. "$staging_dir/.env"
set +a
docker build --label com.linguabridge.owner=translation-project -t "$candidate_image" "$staging_dir"

docker stop "$relay_name" >/dev/null
docker rename "$relay_name" "$backup_name"
docker run -d \
  --name "$relay_name" \
  --label com.linguabridge.owner=translation-project \
  --restart unless-stopped \
  --network linguabridge-memory \
  -p "127.0.0.1:$backend_port:8787" \
  -e PGHOST=linguabridge-memory-postgres \
  -e PGPORT=5432 \
  -e PGDATABASE=memory_sync \
  -e PGUSER=memory_sync \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  -e SYNC_REGISTRATION_KEY="$SYNC_REGISTRATION_KEY" \
  -e PORT=8787 \
  "$candidate_image" >/dev/null

for attempt in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1:$backend_port/healthz" >/dev/null; then break; fi
  if [[ "$attempt" == 30 ]]; then
    docker logs --tail=120 "$relay_name" >&2 || true
    false
  fi
  sleep 1
done

docker rm "$backup_name" >/dev/null
docker tag "$candidate_image" linguabridge-memory-relay:local
mkdir -p "$target_dir"
cp -R "$staging_dir/." "$target_dir/"
find "$staging_dir" -depth -delete
unlink "$remote_archive"
trap - ERR
REMOTE_UPGRADE

echo "[4/4] 验证公网 HTTPS；未触碰 Nginx 和其他业务"
curl --fail --silent --show-error "${SYNC_SERVER_URL%/}/healthz"
echo
echo "LinguaBridge 同步中继升级完成。"
