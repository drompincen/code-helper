import time

from mcp.server.fastmcp import FastMCP

from .config import Config
from .server_manager import ensure_server_running
from . import client, parsers

config = Config()
mcp = FastMCP("javaducker")


def _ensure() -> None:
    ensure_server_running(config)


@mcp.tool()
def javaducker_health() -> dict:
    """Check if the JavaDucker search server is running and healthy.
    Use this before any other JavaDucker operation to confirm the server is available.
    Returns status and version."""
    _ensure()
    return parsers.parse_health(client.cli_health(config))


@mcp.tool()
def javaducker_index_file(file_path: str) -> dict:
    """Upload and index a single file into the JavaDucker search engine.
    Supported types: .java, .xml, .md, .yml, .json, .txt, .pdf.
    Returns artifact_id which can be used with javaducker_wait_for_indexed to confirm indexing."""
    _ensure()
    return parsers.parse_upload_file(client.cli_upload_file(config, file_path))


@mcp.tool()
def javaducker_index_directory(directory: str, extensions: str = "") -> dict:
    """Recursively upload and index all source files in a directory — the primary way to ingest
    an entire codebase. Indexing is async; use javaducker_stats to monitor progress.
    extensions: comma-separated list like '.java,.xml,.md' (default: .java,.xml,.md,.yml,.json,.txt,.pdf)"""
    _ensure()
    ext = extensions.strip() or config.default_extensions
    return parsers.parse_upload_dir(client.cli_upload_dir(config, directory, ext))


@mcp.tool()
def javaducker_search(phrase: str, mode: str = "hybrid", max_results: int = 0) -> dict:
    """Search the indexed codebase for files or code matching a phrase.
    mode options:
      - 'exact': case-insensitive substring match, best for annotations, class names, or literal strings
      - 'semantic': TF-IDF vector similarity, best for conceptual questions
      - 'hybrid' (default): weighted combination (30% exact + 70% semantic), best general-purpose
    Returns ranked results with file name, chunk index, score, and text preview."""
    _ensure()
    n = max_results if max_results > 0 else config.default_max_results
    return parsers.parse_find(client.cli_find(config, phrase, mode, n))


@mcp.tool()
def javaducker_get_file_text(artifact_id: str) -> dict:
    """Retrieve the full extracted text content of a previously indexed file by artifact_id.
    Useful after a search to read the complete file rather than just the chunk preview.
    Returns the raw extracted text (plain text for source files, PDF text extraction for PDFs)."""
    _ensure()
    return parsers.parse_cat(client.cli_cat(config, artifact_id))


@mcp.tool()
def javaducker_get_artifact_status(artifact_id: str) -> dict:
    """Check the ingestion status of a specific artifact.
    Lifecycle: RECEIVED -> STORED_IN_INTAKE -> PARSING -> CHUNKED -> EMBEDDED -> INDEXED (or FAILED).
    Returns current status, timestamps, and any error message."""
    _ensure()
    return parsers.parse_status(client.cli_status(config, artifact_id))


@mcp.tool()
def javaducker_wait_for_indexed(artifact_id: str, timeout_seconds: int = 0) -> dict:
    """Block and poll until a specific artifact reaches INDEXED or FAILED status.
    Use this after javaducker_index_file to ensure a file is fully searchable before searching.
    Returns the final status and elapsed time."""
    _ensure()
    timeout = timeout_seconds if timeout_seconds > 0 else config.ingestion_poll_timeout_secs
    start = time.monotonic()

    while True:
        elapsed = time.monotonic() - start
        if elapsed >= timeout:
            raise TimeoutError(
                f"Artifact {artifact_id} did not reach INDEXED within {timeout}s "
                f"(current: {parsers.parse_status(client.cli_status(config, artifact_id))['status']})"
            )
        status_dict = parsers.parse_status(client.cli_status(config, artifact_id))
        if status_dict["status"] in ("INDEXED", "FAILED"):
            return {
                "artifact_id": artifact_id,
                "final_status": status_dict["status"],
                "elapsed_seconds": round(elapsed, 1),
            }
        time.sleep(config.ingestion_poll_interval_secs)


@mcp.tool()
def javaducker_stats() -> dict:
    """Return aggregate statistics about what is indexed: total artifacts, how many are indexed
    vs pending vs failed, total chunks, and total bytes. Use after javaducker_index_directory
    to monitor bulk ingestion progress."""
    _ensure()
    return parsers.parse_stats(client.cli_stats(config))


if __name__ == "__main__":
    mcp.run()
