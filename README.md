# cluster-ctl

`cluster-ctl` is a command-line utility for managing clusters, opening terminal
sessions, running Docker commands, building applications, and deploying artifacts.
Hosts, environments, and applications are defined in an external YAML configuration.

## Install

The [installer](scripts/install.sh) works with Bash and Zsh and selects Linux
x86-64, Linux ARM64, or macOS Apple Silicon automatically. It downloads public
GitHub Release assets, verifies SHA-256 and the executable version, then
installs `cluster-ctl` into `~/.local/bin`. No GitHub token, Maven, or Java is
needed. Intel macOS is not supported.

```sh
curl -fsSL https://raw.githubusercontent.com/dropchop-com/cluster-ctl/master/scripts/install.sh \
    | sh -s -- --version 0.1.0
export PATH="$HOME/.local/bin:$PATH"
cluster-ctl --version
```

Add the `PATH` export to `~/.bashrc` or `~/.zshrc` if needed; the installer does
not edit shell startup files. To review the installer first, download it to a
file, inspect it, then run `sh install.sh --version 0.1.0`.

Use `--install-dir /absolute/path` to choose a different binary directory.
The installer installs only the executable; it does not create configuration
directories or files. Successful reinstalls replace only the executable.

Requirements are `curl`, `tar`, and `sha256sum` or `shasum`; Linux needs glibc
2.34 or newer. Installation becomes available once the source repository is
public and the selected version/platform has a published GitHub Release.

### Upgrade

```sh
cluster-ctl self-upgrade
```

This checks the [latest stable GitHub Release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
from `dropchop-com/cluster-ctl` and installs it only when its version is newer.
It downloads the matching native archive, verifies SHA-256 and the executable's
reported version, then atomically
replaces the running executable. No token, YAML configuration, `curl`, or `tar`
is required. Configuration files and shell aliases are untouched.

To check availability without downloading or replacing the executable:

```sh
cluster-ctl --dry-run self-upgrade
```

Renamed binaries are upgraded in place; invocation symlinks are preserved and
their targets upgraded. The installation owner needs write access to the binary's
directory. Permissions and ownership are preserved, and a failed verification or
replacement leaves the current executable in place. No automatic privilege
elevation or downgrade is attempted. Run the command again after a successful
upgrade to use the new version. JVM launches cannot self-upgrade.

## Setup

### General

The installed executable is named **`cluster-ctl`** on every supported platform.
Start with [config.example.yaml](config.example.yaml),
copy it to your own YAML file, and customize its hosts and paths:

```sh
cluster-ctl --help
cluster-ctl --dry-run exec host-1 hostname
```

Set the path for each invocation:

```sh
CLUSTER_CTL_CONF_PATH=/path/cluster-ctl/config.yaml cluster-ctl --help
```

This is suitable for a shell alias:

```sh
alias cluster-ctl-dev='CLUSTER_CTL_CONF_PATH=/etc/cluster-ctl/dev.yaml cluster-ctl'
```

For one-off use, `--config` or `-c` takes precedence:

```sh
cluster-ctl --config ./config.yaml term c1 --help
```

### Clippitamine

For CPTM, the config is available in the `klipingmm` repository. With repository
access, clone it under `~/projects/mm` and point `cluster-ctl` to its client config:

Set up the project if not already:
```sh
mkdir -p "$HOME/projects/mm"
cd "$HOME/projects/mm"
git clone https://github.com/dropchop-com/klipingmm.git
```
Add to your environment `.zshrc` or `.bashrc`:
```sh
export KLIPING_MM_ROOT="$HOME/projects/mm/klipingmm"
alias cptm-ctl="CLUSTER_CTL_CONF_PATH=${KLIPING_MM_ROOT}/system/client/cptm.yaml cluster-ctl"
```

### Dropchop

For DC, clone the `systems` repository under `~/projects/dc`. Its client config
is in `~/projects/dc/systems/client/dc.yaml`.

Set up the project if not already:
```sh
mkdir -p "$HOME/projects/dc"
cd "$HOME/projects/dc"
git clone https://github.com/dropchop-com/systems.git
```
Add to your environment `.zshrc` or `.bashrc`:
```sh
alias dc-ctl="CLUSTER_CTL_CONF_PATH=${HOME}/projects/dc/systems/client/dc.yaml cluster-ctl"
```

## Commands

When Konsole is detected or explicitly selected, `term` creates tabs in the
invoking Konsole window using `KONSOLE_DBUS_SERVICE` and `KONSOLE_DBUS_WINDOW`. It uses
`busctl --user` when available, otherwise `qdbus6`, using the
`newSession`, `setTitle`, and `sendText` sequence. Run it from Konsole or provide
those environment variables explicitly. Dry-runs show placeholders for missing
environment values and for session IDs returned at runtime.

Use `-h` or `--help` on the CLI or any command. Every help page includes synthetic
Examples, some using the default cluster and others selecting a cluster explicitly.
The standalone `help` command has been removed. Help works without configuration;
configure a YAML file to include these command-specific inventories:

- `term`, `exec`, and `ssh-*`: cluster and host-group information.
- `deploy`: applications and environments for the selected cluster.
- `build`: environments for the selected cluster.

For example, `cluster-ctl deploy c2 --help` lists only cluster `c2`'s inventory;
`cluster-ctl deploy --help` uses the configured default cluster. No operation is
executed when help is requested, even when other arguments are supplied.
Incorrect arguments print an error followed by the same command help and return
exit code 2. Runtime failures keep their error messages without printing help.
Host-group tables remain approximately 150 characters wide; application columns
are 25 characters each. Group names are yellow and aliases cyan.
Usage and examples use the native invocation name from `argv[0]`, including renamed
binaries and symlinks. Shell aliases expanded by the shell are not visible to the program.

Each `exec` target is displayed in its own colored block. The header contains
the local timestamp, exact remote command, and SSH host; an `output` delimiter
separates that metadata from the remote process output. SSH stderr is streamed
in orange, separately from stdout, for `exec` and SSH-key operations.

```text
cluster-ctl [-h|--help]
cluster-ctl COMMAND [CLUSTER] --help
cluster-ctl docker pull|get [IMAGE_OR_ALIAS]
cluster-ctl docker start|run
cluster-ctl docker stop|kill
cluster-ctl docker log|tail CONTAINER
cluster-ctl term [CLUSTER] [USER@]HOST_GROUP_OR_HOST
cluster-ctl exec [CLUSTER] HOST_GROUP_OR_HOST "REMOTE COMMAND"
cluster-ctl ssh-key-list [CLUSTER] HOST_GROUP_OR_HOST [USER]
cluster-ctl ssh-key-add [CLUSTER] HOST_GROUP_OR_HOST PUBLIC_KEY_PATH [USER] [verify-required]
cluster-ctl ssh-key-remove [CLUSTER] HOST_GROUP_OR_HOST MATCHING_STRING [USER]
cluster-ctl build [CLUSTER] ENVIRONMENT [PROJECT_OR_ALIAS]
cluster-ctl deploy [CLUSTER] ENVIRONMENT APP_OR_GROUP
cluster-ctl self-upgrade
```

For cluster operations, the first positional argument after the command is
treated as a cluster only when it exactly matches a configured cluster name.
Otherwise, `remote.default-cluster` is used and that argument becomes the host,
group, or environment selector. If no default is configured, the first declared
cluster is used. A cluster name takes precedence when a selector has the same
name; supply the cluster explicitly in that case.

Prefix a `term` selector with `USER@` to override the SSH login for every
selected host. For example, `cluster-ctl term operator@web` connects as `operator`
to the default cluster's `web` group. This also works with individual hosts and
explicit clusters, such as `cluster-ctl term c2 operator@c2-host-1`. Without the
prefix, each host's configured `ssh-user` is used.

Set `ssh-proxy` on a host to route `term`, `exec`, and SSH-key operations through
an SSH jump host:

```yaml
remote:
  hosts:
    - name: host-1.example.test
      cluster: c1
      aliases: [host-1]
      ssh-user: root
      ssh-proxy: operator@bastion.example.test:2222
```

The value is passed to `ssh -J`. Use `[user@]host[:port]` for one hop or a
comma-separated chain for multiple hops. Omit the field or leave it blank for a
direct connection. A `term USER@...` override changes only the destination login;
the proxy's user remains as configured.

Examples:

```sh
cluster-ctl term c1 --help
cluster-ctl term support
cluster-ctl term operator@web
cluster-ctl exec host-1 "hostname"
cluster-ctl build prod app
cluster-ctl deploy prod support
cluster-ctl term c1 support
cluster-ctl exec c1 host-1 "hostname"
cluster-ctl exec c1 support "systemctl status postgresql"
cluster-ctl ssh-key-list c1 hosts operator
cluster-ctl ssh-key-add c1 hosts ~/.ssh/id_ed25519.pub operator verify-required
cluster-ctl ssh-key-remove c1 hosts "old-key-comment" operator
cluster-ctl docker pull pg
cluster-ctl build c2 test app
cluster-ctl deploy c1 prod support
```

Use `--yes` to pre-authorize deployment and matching-key removal. Key addition
is idempotent by key type plus key blob. `verify-required` upgrades a matching
entry rather than adding an unrestricted duplicate.

`deploy` attaches SCP directly to your terminal, showing its normal live transfer
progress and preserving SSH prompts. As with invoking SCP directly, the progress
meter is disabled when stdout is redirected or piped instead of connected to a terminal.

`build` attaches Maven's stdout and stderr directly to your terminal, displaying
progress immediately and preserving Maven's normal colors and interactive input.
Maven controls its color behavior just as it does when invoked directly; its
configured arguments, such as `-Dstyle.color=always` or `-Dstyle.color=never`,
still apply.

Use `--dry-run` while reviewing a config. It resolves selectors and prints all
external commands without starting terminals, SSH, Docker, Maven, or SCP:

```sh
cluster-ctl --config ./config.yaml --dry-run exec c1 support "uname -a"
cluster-ctl --config ./config.yaml --dry-run deploy c2 test service-1
```

Configuration strings support `${NAME}` and `${NAME:-fallback}` environment
expansion. A leading `~/` is expanded for filesystem paths. Relative paths,
including Docker Compose files, are resolved relative to the YAML file rather
than the current directory.

Terminal selection is automatic; `local.terminal` has been removed from YAML configuration.
`CLUSTER_CTL_LOCAL_TERMINAL` forces a strategy (`konsole`, `tmux`, `kitty`, or
`iterm`), taking precedence over detection. When it is unset or blank, the CLI
first detects tmux from a nonblank `TMUX` variable, then
detects Kitty from `KITTY_WINDOW_ID`, `KITTY_PID`, `KITTY_LISTEN_ON`,
`TERM=xterm-kitty`, or `TERM_PROGRAM=kitty`; otherwise, it detects Konsole from
`KONSOLE_DBUS_SERVICE` and `KONSOLE_DBUS_WINDOW`. Detection precedence is tmux,
Kitty, then Konsole, so a tmux session wins over its enclosing terminal.
If no terminal is detected, `term` reports an
error asking for an explicit override; other commands still work.

```sh
CLUSTER_CTL_LOCAL_TERMINAL=kitty cluster-ctl --config ./config.yaml term support
```

Supply a Docker repository token only when registry login is needed; keep it
outside the YAML file:

```sh
CLUSTER_CTL_LOCAL_DOCKER_REPO_TOKEN=ghp_example \
CLUSTER_CTL_CONF_PATH=./config.yaml \
cluster-ctl docker pull
```

The top-level sections are:

- `commands`: command names enabled for this installation. Omit or leave empty
  to allow all configured-operation commands. `self-upgrade` is always available
  independently of this list and the configuration file.
- `local`: build strategy/projects and Docker settings.
- `remote`: clusters, environments, deployment targets, hosts, and composable
  host groups.
- `apps`: source roots and artifacts selectable by name, alias, or group.

Hosts and groups are cluster-scoped, so both c1 and c2 may define a `hosts`
group. A group can list hosts directly and compose other groups. Cycles,
duplicate aliases, invalid environment mappings, and unknown config fields are
rejected while loading. Define short host names in `remote.hosts[].aliases`,
such as `host-1` or `support-1`, to avoid repeating cluster prefixes. Host aliases
can be used anywhere a host-group selector is accepted.

Artifact paths and Maven arguments accept these runtime placeholders:

- `{cluster}`
- `{environment}`
- `{build-environment}` for Maven arguments

Build, test, CDI extension, and release instructions are in [DEVELOP.md](DEVELOP.md).

