from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import time
from pathlib import Path

from .logutil import daily_hopper_log, die, log, log_keep_days, prune_hopper_logs
from .paths import bin_dir, DAEMON_NAME, KEY_DIR, ChainContext, ensure_dirs
from .version import version_field, release_asset_urls, load_version


def detect_arch() -> str:
    machine = os.uname().machine
    if machine in ("x86_64", "amd64"):
        return "amd64"
    if machine in ("aarch64", "arm64"):
        return "arm64"
    die(f"Unsupported architecture: {machine}")
    return ""


def hopper_binary_path() -> Path:
    return bin_dir() / f"{DAEMON_NAME}-linux-{detect_arch()}"


def _ensure_executable(path: Path) -> None:
    if path.is_file():
        path.chmod(0o755)


def _binary_passes_check(path: Path) -> bool:
    if not path.is_file():
        return False
    try:
        _ensure_executable(path)
        check = subprocess.run([str(path), "-check"], capture_output=True)
        return check.returncode == 0
    except (PermissionError, OSError) as exc:
        log(f"WARN: cannot run {path.name} -check: {exc}")
        return False


def purge_stale_binaries() -> None:
    """Remove wrong-arch or invalid hopperd binaries from dist/."""
    ensure_dirs()
    arch = detect_arch()
    expected = hopper_binary_path()
    for path in bin_dir().glob(f"{DAEMON_NAME}-linux-*"):
        if path == expected:
            if path.is_file() and not _binary_passes_check(path):
                log(f"Removing invalid {path.name} (failed hopperd -check)")
                path.unlink(missing_ok=True)
            continue
        if path.is_file():
            log(f"Removing wrong-arch binary {path.name} (host is linux-{arch})")
            path.unlink(missing_ok=True)


def resolve_binary() -> Path:
    ensure_dirs()
    purge_stale_binaries()
    dest = hopper_binary_path()
    if dest.is_file() and _binary_passes_check(dest):
        log(f"Using bundled {DAEMON_NAME} at {dest} ({dest.stat().st_size} bytes)")
        return dest
    if dest.is_file():
        log(f"Existing {dest.name} failed hopperd -check — removing")
        dest.unlink(missing_ok=True)
    if shutil.which("go") and (Path(__file__).resolve().parent.parent / "cmd" / "hopperd" / "main.go").is_file():
        log(f"Building {DAEMON_NAME} into {dest}...")
        ver = version_field("version", "dev")
        server_root = Path(__file__).resolve().parent.parent
        subprocess.run(
            [
                "go", "build",
                "-ldflags", f"-s -w -X main.version={ver}",
                "-o", str(dest),
                "./cmd/hopperd",
            ],
            cwd=server_root,
            env={**os.environ, "GOOS": "linux", "GOARCH": detect_arch(), "CGO_ENABLED": "0"},
            check=True,
        )
        _ensure_executable(dest)
    else:
        die(f"Missing {dest}. Run hopper update or install from deploy.")
    if not _binary_passes_check(dest):
        die(f"{dest} failed hopperd -check")
    dest.chmod(0o755)
    return dest


def refresh_binary_from_release() -> bool:
    load_version.cache_clear()
    purge_stale_binaries()
    dest = hopper_binary_path()
    arch = detect_arch()
    urls = release_asset_urls(arch)
    if not urls:
        log("ERROR: no hopperd release URLs configured (check VERSION.json git_remote)")
        return False

    log(f"hopperd download: arch=linux-{arch} dest={dest} urls={len(urls)}")
    last_err = ""
    for i, url in enumerate(urls, start=1):
        log(f"Downloading [{i}/{len(urls)}] {url}...")
        err = _download_release_asset(url, dest)
        if err is None:
            log(f"Installed {dest.name} ({dest.stat().st_size} bytes)")
            return True
        log(f"Download failed: {err}")
        last_err = err

    log(f"ERROR: hopperd download failed for linux-{arch}: {last_err}")
    return False


def _download_release_asset(url: str, dest: Path) -> str | None:
    """Download url to dest. Returns an error string, or None on success."""
    tmp = dest.with_name(dest.name + ".download")
    tmp.unlink(missing_ok=True)
    user_agent = "hopper-server-install"

    if shutil.which("curl"):
        result = subprocess.run(
            [
                "curl",
                "-fsSL",
                "--retry",
                "2",
                "--retry-delay",
                "1",
                "-A",
                user_agent,
                "-o",
                str(tmp),
                url,
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            tmp.unlink(missing_ok=True)
            return detail or f"curl exit {result.returncode}"
    elif shutil.which("wget"):
        result = subprocess.run(
            ["wget", "-q", "-L", "--tries=2", "-O", str(tmp), url],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            tmp.unlink(missing_ok=True)
            return detail or f"wget exit {result.returncode}"
    else:
        return "curl or wget required to download hopperd"

    if not tmp.is_file() or tmp.stat().st_size < 1024:
        tmp.unlink(missing_ok=True)
        return "empty download (release missing or blocked?)"

    if tmp.read_bytes()[:4] != b"\x7fELF":
        tmp.unlink(missing_ok=True)
        return "response is not a Linux binary (404 HTML or wrong asset name?)"

    tmp.chmod(0o755)
    try:
        check = subprocess.run([str(tmp), "-check"], capture_output=True, text=True)
    except (PermissionError, OSError) as exc:
        tmp.unlink(missing_ok=True)
        return f"cannot execute {tmp.name}: {exc}"

    if check.returncode != 0:
        detail = (check.stderr or check.stdout or "").strip()
        tmp.unlink(missing_ok=True)
        return detail or "hopperd -check failed"

    try:
        tmp.replace(dest)
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        return f"cannot install {dest.name}: {exc}"
    _ensure_executable(dest)
    return None


def port_listening(port: int) -> bool:
    if shutil.which("ss"):
        r = subprocess.run(["ss", "-ltn"], capture_output=True, text=True)
        return f":{port} " in r.stdout or f".{port} " in r.stdout
    if shutil.which("netstat"):
        r = subprocess.run(["netstat", "-ltn"], capture_output=True, text=True)
        return f":{port} " in r.stdout
    return False


def hopper_pids_for_config(cfg: Path) -> list[int]:
    if not shutil.which("pgrep"):
        return []
    r = subprocess.run(["pgrep", "-f", str(cfg)], capture_output=True, text=True)
    if r.returncode != 0:
        return []
    return [int(x) for x in r.stdout.split() if x.strip().isdigit()]


def kill_hopper_port(port: int) -> None:
    if shutil.which("fuser"):
        subprocess.run(["fuser", "-k", "-TERM", f"{port}/tcp"], capture_output=True)
        time.sleep(0.2)
        subprocess.run(["fuser", "-k", "-KILL", f"{port}/tcp"], capture_output=True)
        return
    if shutil.which("ss"):
        r = subprocess.run(["ss", "-ltnp"], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            if f":{port} " not in line:
                continue
            if "pid=" in line:
                pid = line.split("pid=")[1].split(",")[0]
                subprocess.run(["kill", "-TERM", pid], capture_output=True)
                time.sleep(0.2)
                subprocess.run(["kill", "-KILL", pid], capture_output=True)
                return


def stop_chain_daemon(ctx: ChainContext) -> None:
    try:
        resolve_binary()
    except SystemExit:
        pass
    for pid in hopper_pids_for_config(ctx.hopper_config):
        subprocess.run(["kill", "-TERM", str(pid)], capture_output=True)
    kill_hopper_port(ctx.listen_port)
    for _ in range(30):
        if not hopper_pids_for_config(ctx.hopper_config):
            break
        time.sleep(0.1)
    for pid in hopper_pids_for_config(ctx.hopper_config):
        subprocess.run(["kill", "-KILL", str(pid)], capture_output=True)
    kill_hopper_port(ctx.listen_port)
    ctx.hopper_ready.unlink(missing_ok=True)
    from .registry import registry_remove

    registry_remove(ctx.chain_id)
    log(f"Stopped hopperd for chain {ctx.chain_id}")


def chain_is_running(ctx: ChainContext) -> bool:
    if not ctx.hopper_ready.is_file() or ctx.hopper_ready.stat().st_size == 0:
        return False
    for line in ctx.hopper_ready.read_text().splitlines():
        if line.startswith("READY "):
            port = int(line.split()[1])
            return port_listening(port)
    return False


def write_hopper_config(
    ctx: ChainContext,
    role: str,
    addr: str,
    index: int = 0,
    upstream_host: str = "",
    upstream_port: int = 22,
    upstream_user: str = "",
    upstream_tunnel_port: int = 0,
    downstream_host: str = "",
    downstream_port: int = 22,
    downstream_user: str = "",
    downstream_tunnel_port: int = 0,
) -> None:
    octet = ctx.overlay_cidr.split(".")[2]
    cfg: dict = {
        "chain_id": ctx.chain_id,
        "addr": addr,
        "overlay": ctx.overlay_cidr,
        "tun": ctx.tun_name,
        "listen_host": "127.0.0.1",
        "listen_port": ctx.listen_port,
    }
    # Only the entry hop (index 0) assigns overlay client addresses.
    if index == 0:
        cfg["client_pool"] = f"10.64.{octet}.2/24"
        cfg["client_lease_ttl_sec"] = 3600
    if role == "exit":
        cfg["nat"] = True
    else:
        cfg["await_reverse"] = True
    if upstream_host:
        cfg["upstream"] = {
            "host": upstream_host,
            "port": upstream_port,
            "user": upstream_user,
            "key_path": str(Path.home() / ".hopper" / "id_ed25519"),
            "tunnel_port": upstream_tunnel_port or ctx.listen_port,
        }
    if downstream_host:
        cfg["downstream"] = {
            "host": downstream_host,
            "port": downstream_port,
            "user": downstream_user,
            "key_path": str(Path.home() / ".hopper" / "id_ed25519"),
            "tunnel_port": downstream_tunnel_port or ctx.listen_port,
        }
    ctx.hopper_config.write_text(json.dumps(cfg, indent=2) + "\n")


def start_hopperd_detached(ctx: ChainContext) -> int:
    binary = resolve_binary()
    time.sleep(0.2)
    keep_days = log_keep_days()
    prune_hopper_logs(ctx.chain_dir, keep_days=keep_days)
    ctx.hopper_log = daily_hopper_log(ctx.chain_dir)
    log(f"Starting hopperd chain={ctx.chain_id} ({ctx.hopper_config})")
    # hopperd owns daily rotate+prune via --log-dir; avoid holding a redirected FD
    # that would prevent pruned log inodes from being freed while the daemon runs.
    cmd = [
        str(binary), "-verbose",
        "--config", str(ctx.hopper_config),
        "--ready-file", str(ctx.hopper_ready),
        "--log-dir", str(ctx.chain_dir),
        "--log-keep-days", str(keep_days),
    ]
    if shutil.which("setsid"):
        subprocess.Popen(
            ["setsid", "-f", *cmd],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )
    else:
        subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )
    for _ in range(40):
        if ctx.hopper_ready.is_file() and hopper_pids_for_config(ctx.hopper_config):
            for line in ctx.hopper_ready.read_text().splitlines():
                if line.startswith("READY "):
                    port = int(line.split()[1])
                    if port_listening(port):
                        return port
        time.sleep(0.25)
    die(f"hopperd failed — see {ctx.hopper_log}")
    return 0


def emit_ready_json(ctx: ChainContext, role: str, addr: str, index: int, port: int) -> str:
    return json.dumps(
        {
            "ready": True,
            "mode": role,
            "addr": addr,
            "index": index,
            "overlay": ctx.overlay_cidr,
            "port": port,
            "listen_port": ctx.listen_port,
            "chain_id": ctx.chain_id,
            "nat": role == "exit",
        },
        separators=(",", ":"),
    )


def stop_all_legacy_cleanup() -> None:
    log("Legacy cleanup: stopping all hopperd instances...")
    subprocess.run(["pkill", "-TERM", "-f", f"{DAEMON_NAME}-linux-"], capture_output=True)
    for port in range(7400, 7655):
        kill_hopper_port(port)
    time.sleep(0.5)
    subprocess.run(["pkill", "-KILL", "-f", f"{DAEMON_NAME}-linux-"], capture_output=True)
    if shutil.which("ip"):
        r = subprocess.run(["ip", "-o", "link", "show"], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            if ": hopper" in line:
                tun = line.split(": ", 2)[1].split("@")[0].strip()
                subprocess.run(["ip", "link", "set", tun, "down"], capture_output=True)
                subprocess.run(["ip", "link", "delete", tun], capture_output=True)
    for legacy in (KEY_DIR / "hopper.json", KEY_DIR / "hopper-ready", KEY_DIR / "hopper.log"):
        legacy.unlink(missing_ok=True)


def maybe_setcap() -> None:
    if os.geteuid() != 0 or not shutil.which("setcap"):
        return
    try:
        binary = resolve_binary()
        subprocess.run(["setcap", "cap_net_admin+ep", str(binary)], capture_output=True)
    except SystemExit:
        pass
