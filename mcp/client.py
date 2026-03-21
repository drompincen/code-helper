import subprocess

from .config import Config


def run_cli(config: Config, *args: str, timeout: int = 30) -> str:
    cmd = [
        str(config.run_client_sh),
        "--host", config.grpc_host,
        "--port", str(config.grpc_port),
        *args,
    ]
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(config.project_root),
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"CLI exited with code {result.returncode}")
    return result.stdout


def cli_health(config: Config) -> str:
    return run_cli(config, "health")


def cli_upload_file(config: Config, file_path: str) -> str:
    return run_cli(config, "upload-file", "--file", file_path)


def cli_upload_dir(config: Config, root: str, extensions: str) -> str:
    return run_cli(config, "upload-dir", "--root", root, "--ext", extensions, timeout=600)


def cli_find(config: Config, phrase: str, mode: str, max_results: int) -> str:
    return run_cli(config, "find", "--phrase", phrase, "--mode", mode, "--max", str(max_results))


def cli_cat(config: Config, artifact_id: str) -> str:
    return run_cli(config, "cat", "--id", artifact_id, timeout=60)


def cli_status(config: Config, artifact_id: str) -> str:
    return run_cli(config, "status", "--id", artifact_id)


def cli_stats(config: Config) -> str:
    return run_cli(config, "stats")
