#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_dir="$project_dir/sync-server"

ssh_target="${LINGUABRIDGE_SSH_TARGET:-}"
ssh_port="${LINGUABRIDGE_SSH_PORT:-22}"
sync_domain="${LINGUABRIDGE_SYNC_DOMAIN:-}"
remote_dir="${LINGUABRIDGE_REMOTE_DIR:-linguabridge-sync}"
identity_file="${LINGUABRIDGE_SSH_IDENTITY:-}"
postgres_password="${LINGUABRIDGE_POSTGRES_PASSWORD:-$(openssl rand -hex 32)}"

if [[ -z "$ssh_target" || -z "$sync_domain" ]]; then
  echo "需要 LINGUABRIDGE_SSH_TARGET 和 LINGUABRIDGE_SYNC_DOMAIN。" >&2
  exit 2
fi
if [[ ! "$ssh_port" =~ ^[0-9]{1,5}$ ]]; then
  echo "SSH 端口无效。" >&2
  exit 2
fi
if [[ ! "$sync_domain" =~ ^[A-Za-z0-9.-]+$ || "$sync_domain" == .* || "$sync_domain" == *. ]]; then
  echo "同步域名无效，请只填写域名，不要包含 https:// 或路径。" >&2
  exit 2
fi
if [[ ! "$remote_dir" =~ ^[A-Za-z0-9._/-]+$ || "$remote_dir" == /* || "$remote_dir" == *..* ]]; then
  echo "远程目录必须是服务器用户目录下的安全相对路径。" >&2
  exit 2
fi

ssh_args=(-p "$ssh_port" -o BatchMode=yes -o ConnectTimeout=12)
scp_args=(-P "$ssh_port" -o BatchMode=yes -o ConnectTimeout=12)
if [[ -n "$identity_file" ]]; then
  ssh_args+=(-i "$identity_file")
  scp_args+=(-i "$identity_file")
fi

echo "[1/4] 检查服务器 Docker 环境"
ssh "${ssh_args[@]}" "$ssh_target" \
  'command -v docker >/dev/null && docker compose version && command -v curl >/dev/null'

temporary_dir="$(mktemp -d)"
cleanup() {
  if [[ -n "${temporary_dir:-}" && -d "$temporary_dir" ]]; then
    rm -rf -- "$temporary_dir"
  fi
}
trap cleanup EXIT

archive_path="$temporary_dir/linguabridge-sync.tgz"
env_path="$temporary_dir/.env"
receipt_path="$project_dir/sync-server/deployment.receipt.env"
umask 077
printf 'SYNC_DOMAIN=%s\nPOSTGRES_PASSWORD=%s\n' \
  "$sync_domain" "$postgres_password" > "$env_path"
tar -C "$source_dir" --exclude=.env --exclude=node_modules --exclude=test -czf "$archive_path" .

remote_archive="/tmp/linguabridge-sync-${RANDOM}-${RANDOM}.tgz"
remote_env="${remote_archive}.env"
echo "[2/4] 上传加密中继服务"
scp "${scp_args[@]}" "$archive_path" "$ssh_target:$remote_archive"
scp "${scp_args[@]}" "$env_path" "$ssh_target:$remote_env"

echo "[3/4] 启动 PostgreSQL、同步服务和自动 HTTPS"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$remote_dir" "$remote_archive" "$remote_env" <<'REMOTE_SCRIPT'
set -euo pipefail
remote_dir="$1"
remote_archive="$2"
remote_env="$3"
base_dir="$PWD"
target_dir="$base_dir/$remote_dir"
staging_dir="${target_dir}.incoming.$$"
mkdir -p "$staging_dir"
tar -xzf "$remote_archive" -C "$staging_dir"
install -m 600 "$remote_env" "$staging_dir/.env"
cd "$staging_dir"
docker compose config --quiet
mkdir -p "$target_dir"
cp -R "$staging_dir/." "$target_dir/"
cd "$target_dir"
docker compose up -d --build --remove-orphans
for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1/healthz >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    docker compose ps
    docker compose logs --tail=120 relay caddy
    exit 1
  fi
  sleep 2
done
rm -rf -- "$staging_dir" "$remote_archive" "$remote_env"
REMOTE_SCRIPT

echo "[4/4] 验证公网 HTTPS"
for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error "https://$sync_domain/healthz" >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    echo "HTTPS 暂未就绪。请确认域名 A/AAAA 记录指向服务器，且 80/443 端口已开放。" >&2
    exit 1
  fi
  sleep 3
done

printf 'SYNC_SERVER_URL=https://%s\nSSH_TARGET=%s\nREMOTE_DIR=%s\n' \
  "$sync_domain" "$ssh_target" "$remote_dir" > "$receipt_path"
chmod 600 "$receipt_path"
echo "部署完成；本机配置回执已保存到：$receipt_path"
