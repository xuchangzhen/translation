#!/usr/bin/env python3
"""Package an explicit source allowlist, excluding local deployment secrets."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[1]
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
name = f"linguabridge-wordbooks-{stamp}"
output = root / "release" / "sync-handoff"
output.mkdir(parents=True, exist_ok=True)
paths = [
    "sync-server/package.json", "sync-server/Dockerfile",
    "sync-server/docker-compose.yml", "sync-server/Caddyfile", "sync-server/README.md",
    "docs/remote-openclaw-wordbook-deploy-prompt.md",
    "docs/wordbooks-relay-implementation.md",
]
paths += [str(p.relative_to(root)) for folder in ("sync-server/src", "sync-server/test")
          for p in sorted((root / folder).glob("*.mjs"))]
files = {}
for relative in paths:
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise SystemExit(f"Refusing non-regular source: {relative}")
    files[relative] = path.read_bytes()
for relative in ("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml"):
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise SystemExit(f"Refusing non-regular reference: {relative}")
    files[f"workspace-reference/{relative}"] = path.read_bytes()
source = {
    "createdAt": stamp,
    "baseCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "source": "local working tree; file hashes identify payload, not base commit",
    "protocols": ["linguabridge-memory/1", "linguabridge-wordbooks/1"],
    "deploymentRoot": "sync-server/",
    "preserveRemoteConfiguration": True,
}
files["SOURCE.json"] = (json.dumps(source, ensure_ascii=False, indent=2) + "\n").encode()
files["SHA256SUMS"] = "".join(
    f"{hashlib.sha256(data).hexdigest()}  {path}\n" for path, data in sorted(files.items())
).encode()
archive = output / f"{name}.tar.gz"
with tarfile.open(archive, "w:gz") as tar:
    for path, data in sorted(files.items()):
        info = tarfile.TarInfo(f"{name}/{path}")
        info.size, info.mode, info.mtime = len(data), 0o644, 0
        tar.addfile(info, io.BytesIO(data))
with tarfile.open(archive) as tar:
    for member in tar.getmembers():
        relative = str(Path(member.name).relative_to(name))
        if not member.isfile() or tar.extractfile(member).read() != files[relative]:
            raise SystemExit("Archive verification failed")
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
checksum = archive.with_name(archive.name + ".sha256")
checksum.write_text(f"{digest}  {archive.name}\n")
print(json.dumps({"archive": str(archive), "checksumFile": str(checksum), "sha256": digest,
                  "files": len(files), "bytes": archive.stat().st_size}, indent=2))
