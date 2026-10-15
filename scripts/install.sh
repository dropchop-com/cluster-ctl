#!/bin/sh
# Install a verified native cluster-ctl package; callable from Bash, Zsh, or sh.
set -eu

# Report an installation failure.
installer_error() {
  printf 'cluster-ctl installer: %s\n' "$*" >&2
  exit 1
}

# Describe arguments without downloading or changing files.
installer_usage() {
  printf '%s\n' \
    'Usage: sh install.sh [--version VERSION] [--install-dir DIRECTORY]' \
    'Defaults: version 0.1.0, binary in ~/.local/bin.' \
    'Public GitHub Release downloads do not require a token.'
}

# Remove only temporary paths created by this invocation.
installer_cleanup() {
  if [ -n "$installer_staged_binary" ]; then
    rm -f -- "$installer_staged_binary"
  fi
  if [ -n "$installer_temp" ]; then
    rm -rf -- "$installer_temp"
  fi
}

# Map the current host to a published native release platform.
installer_detect_platform() {
  installer_os=$(uname -s)
  installer_arch=$(uname -m)
  case "$installer_os:$installer_arch" in
    Linux:x86_64|Linux:amd64) installer_platform=linux-amd64 ;;
    Linux:aarch64|Linux:arm64) installer_platform=linux-arm64 ;;
    Darwin:arm64|Darwin:aarch64) installer_platform=macos-arm64 ;;
    *) installer_error "Unsupported platform: $installer_os/$installer_arch (no Intel macOS build)." ;;
  esac
}

# Download public assets over HTTPS without credentials or a user curl config.
installer_download() {
  curl --disable --fail --silent --show-error --location \
    --proto '=https' --proto-redir '=https' \
    --connect-timeout 15 --max-time 300 --retry 3 \
    --output "$2" "$1" ||
    installer_error 'Download failed. Check that this version/platform has a published public GitHub Release.'
}

# Verify the SHA-256 before extracting or executing the binary.
installer_verify_checksum() {
  installer_expected=$(awk '
    NR == 1 && length($1) == 64 && $1 !~ /[^0-9a-fA-F]/ { print tolower($1); valid = 1 }
    END { if (NR != 1 || !valid) exit 1 }
  ' "$installer_temp/archive.sha256") || installer_error 'Invalid SHA-256 checksum file.'
  if command -v sha256sum >/dev/null 2>&1; then
    installer_actual=$(sha256sum "$installer_temp/archive.tar.gz" | awk '{ print $1 }')
  else
    installer_actual=$(shasum -a 256 "$installer_temp/archive.tar.gz" | awk '{ print $1 }')
  fi
  [ "$installer_actual" = "$installer_expected" ] || installer_error 'SHA-256 checksum mismatch; nothing installed.'
}

# Fetch, verify, and install only the binary without creating configuration files.
installer_main() {
  installer_version=0.1.0
  installer_bin_dir=${HOME:?HOME must be set}/.local/bin
  installer_release_url=https://github.com/dropchop-com/cluster-ctl/releases/download
  installer_temp=
  installer_staged_binary=
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --version|--install-dir)
        [ "$#" -ge 2 ] || installer_error "$1 requires a value."
        case "$1" in
          --version) installer_version=${2#v} ;;
          --install-dir) installer_bin_dir=$2 ;;
        esac
        shift 2
        ;;
      --help|-h) installer_usage; return ;;
      *) installer_error "Unknown argument: $1" ;;
    esac
  done
  printf '%s\n' "$installer_version" | awk '
    /^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$/ { valid = 1 }
    END { if (NR != 1 || !valid) exit 1 }
  ' || installer_error 'Version must be a release version such as 0.1.0 or v0.1.0.'
  case "$installer_bin_dir" in
    /) installer_error 'Use a binary directory, not the filesystem root.' ;;
    /*) ;;
    *) installer_error 'The installation directory must be an absolute path.' ;;
  esac
  for installer_tool in curl tar mktemp uname awk; do
    command -v "$installer_tool" >/dev/null 2>&1 || installer_error "Missing required tool: $installer_tool"
  done
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 ||
    installer_error 'Install sha256sum or shasum to verify downloads.'
  installer_detect_platform
  installer_target=$installer_bin_dir/cluster-ctl
  [ ! -L "$installer_target" ] && [ ! -d "$installer_target" ] ||
    installer_error 'The destination cluster-ctl is a symlink or directory; refusing to replace it.'
  umask 077
  installer_temp=$(mktemp -d "${TMPDIR:-/tmp}/cluster-ctl-install.XXXXXXXX")
  trap installer_cleanup 0
  trap 'exit 1' 1 2 15
  installer_artifact="cluster-ctl-$installer_version-$installer_platform.tar.gz"
  installer_url="${installer_release_url%/}/v$installer_version/$installer_artifact"
  printf 'Downloading cluster-ctl %s for %s...\n' "$installer_version" "$installer_platform"
  installer_download "$installer_url" "$installer_temp/archive.tar.gz"
  installer_download "$installer_url.sha256" "$installer_temp/archive.sha256"
  installer_verify_checksum
  installer_bundle="cluster-ctl-$installer_version-$installer_platform"
  # Extract exact members to explicit files; never unpack arbitrary archive paths.
  tar -xzOf "$installer_temp/archive.tar.gz" "$installer_bundle/cluster-ctl" > "$installer_temp/cluster-ctl" ||
    installer_error 'The archive does not contain the expected executable.'
  chmod 700 "$installer_temp/cluster-ctl"
  installer_binary_version=$("$installer_temp/cluster-ctl" --version) ||
    installer_error 'The executable cannot run on this host; check OS/runtime compatibility.'
  [ "$installer_binary_version" = "cluster-ctl $installer_version" ] ||
    installer_error 'Executable version does not match the requested version.'
  mkdir -p "$installer_bin_dir"
  installer_staged_binary=$(mktemp "$installer_bin_dir/.cluster-ctl.XXXXXXXX")
  cp "$installer_temp/cluster-ctl" "$installer_staged_binary"
  chmod 755 "$installer_staged_binary"
  mv -f "$installer_staged_binary" "$installer_target"
  installer_staged_binary=
  printf 'Installed %s\n' "$installer_target"
  case ":${PATH:-}:" in
    *":$installer_bin_dir:"*) ;;
    *) printf 'Add this directory to PATH in ~/.bashrc or ~/.zshrc: %s\n' "$installer_bin_dir" ;;
  esac
}

installer_main "$@"
