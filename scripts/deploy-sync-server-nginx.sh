#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_dir="$project_dir/sync-server"

ssh_target="${LINGUABRIDGE_SSH_TARGET:-}"
ssh_port="${LINGUABRIDGE_SSH_PORT:-22}"
sync_domain="${LINGUABRIDGE_SYNC_DOMAIN:-}"
server_ipv4="${LINGUABRIDGE_SERVER_IPV4:-}"
remote_dir="${LINGUABRIDGE_REMOTE_DIR:-linguabridge-sync}"
identity_file="${LINGUABRIDGE_SSH_IDENTITY:-}"
backend_port="${LINGUABRIDGE_BACKEND_PORT:-18787}"
postgres_password="${LINGUABRIDGE_POSTGRES_PASSWORD:-$(openssl rand -hex 32)}"

if [[ -z "$ssh_target" || -z "$sync_domain" || -z "$server_ipv4" ]]; then
  echo "需要 LINGUABRIDGE_SSH_TARGET、LINGUABRIDGE_SYNC_DOMAIN 和 LINGUABRIDGE_SERVER_IPV4。" >&2
  exit 2
fi
if [[ ! "$ssh_port" =~ ^[0-9]{1,5}$ || ! "$backend_port" =~ ^[0-9]{1,5}$ ]]; then
  echo "SSH 或后端端口无效。" >&2
  exit 2
fi
if [[ ! "$sync_domain" =~ ^[A-Za-z0-9.-]+$ || "$sync_domain" == .* || "$sync_domain" == *. ]]; then
  echo "同步域名无效，请只填写域名。" >&2
  exit 2
fi
if [[ ! "$server_ipv4" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  echo "服务器 IPv4 地址无效。" >&2
  exit 2
fi
if [[ ! "$remote_dir" =~ ^[A-Za-z0-9._/-]+$ || "$remote_dir" == /* || "$remote_dir" == *..* ]]; then
  echo "远程目录必须是服务器用户目录下的安全相对路径。" >&2
  exit 2
fi

resolved_ipv4="$(dig +short A "$sync_domain" | tail -n 1)"
if [[ "$resolved_ipv4" != "$server_ipv4" ]]; then
  echo "$sync_domain 当前解析为 ${resolved_ipv4:-无记录}，应先解析到 $server_ipv4。" >&2
  exit 2
fi

ssh_args=(-p "$ssh_port" -o BatchMode=yes -o ConnectTimeout=12)
scp_args=(-P "$ssh_port" -o BatchMode=yes -o ConnectTimeout=12)
if [[ -n "$identity_file" ]]; then
  ssh_args+=(-i "$identity_file")
  scp_args+=(-i "$identity_file")
fi

echo "[1/6] 检查共享服务器环境"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$backend_port" <<'REMOTE_CHECK'
set -euo pipefail
backend_port="$1"
command -v docker >/dev/null
docker info >/dev/null
command -v nginx >/dev/null
nginx -t
command -v certbot >/dev/null
command -v curl >/dev/null
command -v ss >/dev/null
command -v python3 >/dev/null
if ss -H -ltn | awk '{print $4}' | grep -Eq "(^|:)$backend_port$"; then
  echo "回环后端端口 $backend_port 已被占用。" >&2
  exit 1
fi
if [[ ! -d /etc/nginx/sites-available || ! -d /etc/nginx/sites-enabled ]]; then
  echo "未找到 Debian Nginx 站点目录，已停止以保护现有配置。" >&2
  exit 1
fi
REMOTE_CHECK

temporary_dir="$(mktemp -d)"
cleanup() {
  if [[ -n "${temporary_dir:-}" && -d "$temporary_dir" ]]; then
    find "$temporary_dir" -depth -delete
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
echo "[2/6] 上传加密中继服务"
scp "${scp_args[@]}" "$archive_path" "$ssh_target:$remote_archive"
scp "${scp_args[@]}" "$env_path" "$ssh_target:$remote_env"

echo "[3/6] 启动仅监听回环地址的独立容器"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- \
  "$remote_dir" "$remote_archive" "$remote_env" "$backend_port" <<'REMOTE_CONTAINERS'
set -euo pipefail
remote_dir="$1"
remote_archive="$2"
remote_env="$3"
backend_port="$4"
target_dir="$PWD/$remote_dir"
staging_dir="${target_dir}.incoming.$$"
postgres_name="linguabridge-memory-postgres"
relay_name="linguabridge-memory-relay"
network_name="linguabridge-memory"
volume_name="linguabridge-memory-postgres"
image_name="linguabridge-memory-relay:local"

for container_name in "$postgres_name" "$relay_name"; do
  if docker container inspect "$container_name" >/dev/null 2>&1; then
    owner="$(docker container inspect --format '{{ index .Config.Labels "com.linguabridge.owner" }}' "$container_name")"
    if [[ "$owner" != "translation-project" ]]; then
      echo "容器名 $container_name 已被其他业务使用，已停止。" >&2
      exit 1
    fi
    echo "检测到既有 LinguaBridge 容器；为避免意外覆盖，请使用专门的升级流程。" >&2
    exit 1
  fi
done

mkdir -p "$staging_dir"
tar -xzf "$remote_archive" -C "$staging_dir"
install -m 600 "$remote_env" "$staging_dir/.env"
set -a
. "$staging_dir/.env"
set +a

docker build --label com.linguabridge.owner=translation-project -t "$image_name" "$staging_dir"
docker network inspect "$network_name" >/dev/null 2>&1 || docker network create \
  --label com.linguabridge.owner=translation-project "$network_name" >/dev/null
docker volume inspect "$volume_name" >/dev/null 2>&1 || docker volume create \
  --label com.linguabridge.owner=translation-project "$volume_name" >/dev/null

docker run -d \
  --name "$postgres_name" \
  --label com.linguabridge.owner=translation-project \
  --restart unless-stopped \
  --network "$network_name" \
  --env-file "$staging_dir/.env" \
  -e POSTGRES_DB=memory_sync \
  -e POSTGRES_USER=memory_sync \
  -v "$volume_name:/var/lib/postgresql/data" \
  --health-cmd='pg_isready -U memory_sync' \
  --health-interval=5s \
  --health-timeout=3s \
  --health-retries=12 \
  postgres:17-alpine >/dev/null

for attempt in $(seq 1 30); do
  postgres_health="$(docker inspect --format '{{.State.Health.Status}}' "$postgres_name")"
  if [[ "$postgres_health" == "healthy" ]]; then break; fi
  if [[ "$postgres_health" == "unhealthy" || "$attempt" == 30 ]]; then
    docker logs --tail=120 "$postgres_name"
    docker stop "$postgres_name" >/dev/null || true
    docker rm "$postgres_name" >/dev/null || true
    exit 1
  fi
  sleep 2
done

if ! docker run -d \
  --name "$relay_name" \
  --label com.linguabridge.owner=translation-project \
  --restart unless-stopped \
  --network "$network_name" \
  -p "127.0.0.1:$backend_port:8787" \
  -e PGHOST="$postgres_name" \
  -e PGPORT=5432 \
  -e PGDATABASE=memory_sync \
  -e PGUSER=memory_sync \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  -e PORT=8787 \
  "$image_name" >/dev/null; then
  docker stop "$postgres_name" >/dev/null || true
  docker rm "$postgres_name" >/dev/null || true
  exit 1
fi

for attempt in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1:$backend_port/healthz" >/dev/null; then break; fi
  if [[ "$attempt" == 30 ]]; then
    docker logs --tail=120 "$relay_name"
    docker stop "$relay_name" "$postgres_name" >/dev/null || true
    docker rm "$relay_name" "$postgres_name" >/dev/null || true
    exit 1
  fi
  sleep 2
done

mkdir -p "$target_dir"
cp -R "$staging_dir/." "$target_dir/"
find "$staging_dir" -depth -delete
unlink "$remote_archive"
unlink "$remote_env"
REMOTE_CONTAINERS

echo "[4/6] 新增独立 Nginx 子域名并申请证书"
ssh "${ssh_args[@]}" "$ssh_target" bash -s -- "$remote_dir" "$sync_domain" <<'REMOTE_NGINX'
set -euo pipefail
remote_dir="$1"
sync_domain="$2"
target_dir="$PWD/$remote_dir"
available="/etc/nginx/sites-available/linguabridge-memory.conf"
enabled="/etc/nginx/sites-enabled/linguabridge-memory.conf"
incoming="${available}.incoming.$$"

if [[ -e "$available" || -L "$enabled" ]]; then
  echo "LinguaBridge Nginx 站点已经存在；为避免覆盖，已停止。" >&2
  exit 1
fi
mkdir -p /var/www/linguabridge-acme
sed "s/__SYNC_DOMAIN__/$sync_domain/g" "$target_dir/nginx-http.conf.template" > "$incoming"
install -m 644 "$incoming" "$available"
unlink "$incoming"
ln -s "$available" "$enabled"
if ! nginx -t; then
  unlink "$enabled"
  unlink "$available"
  exit 1
fi
systemctl reload nginx

if ! certbot certonly \
  --webroot \
  --webroot-path /var/www/linguabridge-acme \
  --domain "$sync_domain" \
  --non-interactive \
  --agree-tos \
  --register-unsafely-without-email \
  --keep-until-expiring; then
  echo "证书申请失败；已保留仅该子域名的 HTTP 配置供诊断，现有业务未修改。" >&2
  exit 1
fi

mkdir -p /etc/letsencrypt/renewal-hooks/deploy
install -m 755 "$target_dir/renew-nginx.sh" \
  /etc/letsencrypt/renewal-hooks/deploy/linguabridge-nginx-reload
mkdir -p /srv/linguabridge-memory/android
install -m 755 "$target_dir/mirror-android-release.sh" \
  /usr/local/sbin/linguabridge-android-mirror
install -m 644 "$target_dir/linguabridge-android-mirror.service" \
  /etc/systemd/system/linguabridge-android-mirror.service
install -m 644 "$target_dir/linguabridge-android-mirror.timer" \
  /etc/systemd/system/linguabridge-android-mirror.timer
systemctl daemon-reload
systemctl enable --now linguabridge-android-mirror.timer >/dev/null

sed "s/__SYNC_DOMAIN__/$sync_domain/g" "$target_dir/nginx-https.conf.template" > "$incoming"
install -m 644 "$incoming" "$available"
unlink "$incoming"
if ! nginx -t; then
  sed "s/__SYNC_DOMAIN__/$sync_domain/g" "$target_dir/nginx-http.conf.template" > "$incoming"
  install -m 644 "$incoming" "$available"
  unlink "$incoming"
  nginx -t
  systemctl reload nginx
  exit 1
fi
systemctl reload nginx
REMOTE_NGINX

echo "[5/6] 验证公网 HTTPS 和零明文中继"
for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error "https://$sync_domain/healthz" >/dev/null; then break; fi
  if [[ "$attempt" == 30 ]]; then
    echo "HTTPS 暂未就绪；容器和独立站点已保留供诊断，现有业务未改动。" >&2
    exit 1
  fi
  sleep 3
done

echo "[6/6] 保存本机配置回执"
printf 'SYNC_SERVER_URL=https://%s\nSSH_TARGET=%s\nREMOTE_DIR=%s\nBACKEND_PORT=%s\nPROXY_MODE=nginx\n' \
  "$sync_domain" "$ssh_target" "$remote_dir" "$backend_port" > "$receipt_path"
chmod 600 "$receipt_path"
echo "部署完成；本机配置回执已保存到：$receipt_path"
