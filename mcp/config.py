import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Config:
    grpc_host: str = field(default_factory=lambda: os.environ.get("GRPC_HOST", "localhost"))
    grpc_port: int = field(default_factory=lambda: int(os.environ.get("GRPC_PORT", "9090")))
    project_root: Path = field(default_factory=lambda: (
        Path(os.environ["PROJECT_ROOT"]) if "PROJECT_ROOT" in os.environ
        else Path(__file__).parent.parent.resolve()
    ))
    server_startup_timeout_secs: int = field(
        default_factory=lambda: int(os.environ.get("SERVER_STARTUP_TIMEOUT_SECS", "60"))
    )
    server_startup_poll_interval_secs: float = 2.0
    ingestion_poll_timeout_secs: int = field(
        default_factory=lambda: int(os.environ.get("INGESTION_POLL_TIMEOUT_SECS", "120"))
    )
    ingestion_poll_interval_secs: float = 3.0
    default_extensions: str = ".java,.xml,.md,.yml,.json,.txt,.pdf"
    default_max_results: int = 20

    @property
    def run_client_sh(self) -> Path:
        return self.project_root / "run-client.sh"

    @property
    def run_server_sh(self) -> Path:
        return self.project_root / "run-server.sh"
