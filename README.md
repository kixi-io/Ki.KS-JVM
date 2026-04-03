# Getting Started
[Comprehensive KS Tutorial](KS-Language-Manual.md)

# Ki Script Command-Line Tools

Cross-platform launchers for running Ki Script files and the interactive REPL.

## Commands

| Command | Description | Example |
|---------|-------------|---------|
| `ks` | Run KS script files | `ks hello.ks` |
| `ksr` | Interactive REPL | `ksr` |

## Quick Start

### Running Scripts

```sh
ks hello.ks              # Run a script
ks hello                 # Same — .ks extension is auto-appended
ks ~/scripts/report.ks   # Absolute or relative paths
ks test1.ks test2.ks     # Multiple files (with summary)
```

### Making Scripts Executable (Unix)

```sh
#!/usr/bin/env ks
# hello.ks

say "Hello from Ki Script!"
```

```sh
chmod +x hello.ks
./hello.ks
```

### Interactive REPL

```sh
ksr                      # Start the REPL
ksr --no-color           # Without ANSI colors
```

## Installation

### macOS / Linux

```sh
# Download and extract the distribution
tar xzf ki-script.tar.gz
cd ki-script

# Install (creates ~/.ki and adds to PATH)
./install.sh
```

### Windows

```powershell
# Extract ki-script.zip, then:
cd ki-script
.\install.ps1
```

### From Source (Gradle)

```sh
# Build the distribution archive
./gradlew dist           # Creates build/dist/ki-script.tar.gz
./gradlew distZip        # Creates build/dist/ki-script.zip

# Or install directly to ~/.ki
./gradlew installLocal
```

## Installation Layout

```
~/.ki/                         # KI_HOME (Unix)
%USERPROFILE%\.ki\             # KI_HOME (Windows)
├── bin/
│   ├── ks                     # Script launcher (Unix)
│   ├── ksr                    # REPL launcher (Unix)
│   ├── ks.cmd                 # Script launcher (Windows)
│   └── ksr.cmd                # REPL launcher (Windows)
├── lib/
│   └── ks-runtime.jar         # Runtime (internal — never referenced by users)
└── VERSION
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KI_HOME` | `~/.ki` | Installation root |
| `KI_JAVA_HOME` | — | Override JVM location |
| `KS_OPTS` | — | Extra JVM options (e.g., `-Xmx512m`) |
| `NO_COLOR` | — | Disable ANSI color output |

## File Extensions

| Extension | Description |
|-----------|-------------|
| `.ks` | Ki Script source file |
| `.ksc` | Ki Script compiled file (future) |
| `.kap` | Ki Application Package (future) |
| `.kd` | Ki Data file |

## Platform Support

| Platform | Shell | Launcher |
|----------|-------|----------|
| macOS | zsh, bash, ksh | `ks` / `ksr` (shell scripts) |
| Linux | bash, zsh, ksh, fish | `ks` / `ksr` (shell scripts) |
| Windows | cmd.exe | `ks.cmd` / `ksr.cmd` |
| Windows | PowerShell | `ks.cmd` / `ksr.cmd` |
| Windows | Git Bash / WSL | `ks` / `ksr` (shell scripts) |

## Prerequisites

- **Java 21 or later** — Ki Script runs on the JVM internally.
  Install from [Adoptium](https://adoptium.net) if needed.
  The JVM is an implementation detail; users interact only with `ks` and `ksr`.

## Development

The existing IDE workflow is preserved:

- **IntelliJ Run Configurations** continue to work as before for `App.kt` and the REPL
- The `ksr.sh` script in the project root still works for dev-time REPL launches
- `./gradlew repl` launches the REPL from a terminal

The new system adds an *installable* distribution on top of the existing dev workflow.
