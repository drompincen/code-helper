# Start Here

After cloning this repo, executable scripts are not included (corporate policy). Follow these steps to set up your local environment.

## Prerequisites

- **Java 21+** (OpenJDK recommended)
- **Maven 3.9+**
- **bash** (Linux/macOS/WSL) or **cmd** (Windows)
- **curl** and **jq** (for utility scripts)

## 1. Recreate Local Scripts

All script content is stored in `script-instructions/` as markdown files with embedded code blocks. Recreate them into the gitignored `scripts/local/` directory:

```bash
mkdir -p scripts/local/hooks
```

Then copy the code blocks from each instruction file into the corresponding script file:

| Instruction File | Scripts Created In |
|---|---|
| `script-instructions/run-scripts.md` | `scripts/local/run-*.sh`, `scripts/local/run-*.cmd` |
| `script-instructions/utility-scripts.md` | `scripts/local/enrich.sh`, `scripts/local/index-sessions.sh`, `scripts/local/orchestrate.sh`, `scripts/local/enrich-batch.ps1` |
| `script-instructions/hook-scripts.md` | `scripts/local/hooks/*.sh` (all Claude Code hooks) |

Make scripts executable (Linux/macOS/WSL):

```bash
chmod +x scripts/local/*.sh scripts/local/hooks/*.sh
```

## 2. Build the Project

```bash
mvn clean package -DskipTests
```

## 3. Run

**HTTP Server** (REST API + MCP):
```bash
bash scripts/local/run-server.sh
```

**MCP stdio mode** (for Claude Code integration):
```bash
bash scripts/local/run-mcp.sh
```

**CLI client**:
```bash
bash scripts/local/run-cli.sh
```

## 4. Claude Code Hooks

The `.claude/settings.json` file references hook scripts in `scripts/local/hooks/`. These provide:

- **memory-sync** — loads session memory and checks for in-progress plans on start
- **session-end** — reminds to persist progress
- **edit-log** — tracks file edits in JSONL
- **validate-plan** — validates drom-plans/ file structure
- **statusline** — git-aware status bar
- **track-agents** — counts background agents
- **javaducker-check** — JavaDucker lifecycle functions
- **javaducker-index** — auto-indexes edited files

If hooks are missing, Claude Code will show warnings but still work. Recreate from `script-instructions/hook-scripts.md`.

## 5. Why No Scripts in Git?

Executable files (`.sh`, `.cmd`, `.ps1`) are excluded from version control to comply with corporate security policies that block repos containing binaries or executable scripts. The `script-instructions/` folder preserves the exact content so scripts can be recreated on any machine.
