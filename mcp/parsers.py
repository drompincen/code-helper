"""Parse CLI text output into structured dicts."""


def parse_health(output: str) -> dict:
    result = {"status": "", "version": ""}
    for line in output.splitlines():
        if line.startswith("Status:"):
            result["status"] = line.split(":", 1)[1].strip()
        elif line.startswith("Version:"):
            result["version"] = line.split(":", 1)[1].strip()
    return result


def parse_upload_file(output: str) -> dict:
    result = {"artifact_id": "", "status": ""}
    for line in output.splitlines():
        if line.startswith("Artifact ID:"):
            result["artifact_id"] = line.split(":", 1)[1].strip()
        elif line.startswith("Status:"):
            result["status"] = line.split(":", 1)[1].strip()
    return result


def parse_upload_dir(output: str) -> dict:
    uploaded = []
    summary = {"uploaded": 0, "skipped": 0, "failed": 0}
    in_summary = False

    for line in output.splitlines():
        stripped = line.strip()
        if stripped == "Summary:":
            in_summary = True
            continue
        if in_summary:
            if stripped.startswith("Uploaded:"):
                summary["uploaded"] = int(stripped.split(":", 1)[1].strip())
            elif stripped.startswith("Skipped:"):
                summary["skipped"] = int(stripped.split(":", 1)[1].strip())
            elif stripped.startswith("Failed:"):
                summary["failed"] = int(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("Uploaded:"):
            # "  Uploaded: <path> -> <artifact-id>"
            rest = stripped[len("Uploaded:"):].strip()
            if " -> " in rest:
                file_path, artifact_id = rest.rsplit(" -> ", 1)
                uploaded.append({"file": file_path.strip(), "artifact_id": artifact_id.strip()})

    return {"uploaded": uploaded, "summary": summary}


def parse_find(output: str) -> dict:
    results = []
    current = None
    preview_lines = []

    for line in output.splitlines():
        if line.startswith("Results:"):
            total = int(line.split(":", 1)[1].strip())
            continue
        # Result header: "#1 [hybrid] score=0.8732  file=foo.java  chunk=3"
        if line.startswith("#") and "[" in line and "score=" in line:
            if current is not None:
                current["preview"] = "\n".join(preview_lines).strip()
                results.append(current)
                preview_lines = []

            # Parse rank
            bracket_start = line.index("[")
            rank = int(line[1:bracket_start].strip())

            bracket_end = line.index("]")
            match_type = line[bracket_start + 1:bracket_end]

            parts = line[bracket_end + 1:].strip().split()
            score = 0.0
            file_name = ""
            chunk_index = 0
            for part in parts:
                if part.startswith("score="):
                    score = float(part[6:])
                elif part.startswith("file="):
                    file_name = part[5:]
                elif part.startswith("chunk="):
                    chunk_index = int(part[6:])

            current = {
                "rank": rank,
                "match_type": match_type,
                "score": score,
                "file": file_name,
                "chunk_index": chunk_index,
                "preview": "",
            }
        elif current is not None and line.startswith("    "):
            preview_lines.append(line[4:])  # strip 4-space indent

    if current is not None:
        current["preview"] = "\n".join(preview_lines).strip()
        results.append(current)

    total = 0
    for line in output.splitlines():
        if line.startswith("Results:"):
            total = int(line.split(":", 1)[1].strip())
            break

    return {"total_results": total, "results": results}


def parse_cat(output: str) -> dict:
    result = {"artifact_id": "", "extraction_method": "", "text_length": 0, "text": ""}
    lines = output.splitlines()
    sep_index = None

    for i, line in enumerate(lines):
        if line.startswith("Artifact:"):
            result["artifact_id"] = line.split(":", 1)[1].strip()
        elif line.startswith("Method:"):
            result["extraction_method"] = line.split(":", 1)[1].strip()
        elif line.startswith("Length:"):
            result["text_length"] = int(line.split(":", 1)[1].strip())
        elif line == "---":
            sep_index = i
            break

    if sep_index is not None:
        result["text"] = "\n".join(lines[sep_index + 1:])

    return result


def parse_status(output: str) -> dict:
    result = {
        "artifact_id": "", "file": "", "status": "",
        "error": "", "created_at": "", "updated_at": "", "indexed_at": "",
    }
    for line in output.splitlines():
        if line.startswith("Artifact:"):
            result["artifact_id"] = line.split(":", 1)[1].strip()
        elif line.startswith("File:"):
            result["file"] = line.split(":", 1)[1].strip()
        elif line.startswith("Status:"):
            result["status"] = line.split(":", 1)[1].strip()
        elif line.startswith("Error:"):
            result["error"] = line.split(":", 1)[1].strip()
        elif line.startswith("Created:"):
            result["created_at"] = line.split(":", 1)[1].strip()
        elif line.startswith("Updated:"):
            result["updated_at"] = line.split(":", 1)[1].strip()
        elif line.startswith("Indexed:"):
            result["indexed_at"] = line.split(":", 1)[1].strip()
    return result


def parse_stats(output: str) -> dict:
    result = {
        "total_artifacts": 0, "indexed": 0, "failed": 0, "pending": 0,
        "total_chunks": 0, "total_bytes": 0, "by_status": {},
    }
    in_by_status = False

    for line in output.splitlines():
        stripped = line.strip()
        if line.startswith("Total artifacts:"):
            result["total_artifacts"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("Indexed:"):
            result["indexed"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("Failed:"):
            result["failed"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("Pending:"):
            result["pending"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("Total chunks:"):
            result["total_chunks"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("Total bytes:"):
            result["total_bytes"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("By status:"):
            in_by_status = True
        elif in_by_status and stripped and ":" in stripped:
            k, v = stripped.split(":", 1)
            result["by_status"][k.strip()] = int(v.strip())

    return result
