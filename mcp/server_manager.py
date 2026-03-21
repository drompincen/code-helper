import socket
import subprocess
import time

from .config import Config


def is_server_healthy(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def ensure_server_running(config: Config) -> None:
    if is_server_healthy(config.grpc_host, config.grpc_port):
        return

    subprocess.Popen(
        [str(config.run_server_sh)],
        cwd=str(config.project_root),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )

    deadline = time.monotonic() + config.server_startup_timeout_secs
    while time.monotonic() < deadline:
        time.sleep(config.server_startup_poll_interval_secs)
        if is_server_healthy(config.grpc_host, config.grpc_port):
            return

    raise RuntimeError(
        f"JavaDucker server did not become ready within {config.server_startup_timeout_secs}s. "
        f"Make sure the project is built: mvn package -DskipTests"
    )
