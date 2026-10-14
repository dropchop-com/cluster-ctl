#!/bin/sh
# Publish checked native assets locally through Maven and GitHub CLI, never CI.
set -eu

# Fail without deleting or overwriting an existing GitHub release.
publisher_error() {
  printf 'cluster-ctl release: %s\n' "$*" >&2
  exit 1
}

# Verify exactly the named archive, not paths recorded in a checksum file.
publisher_verify_archive() {
  publisher_archive=$publisher_directory/cluster-ctl-$publisher_version-$1.tar.gz
  [ -f "$publisher_archive" ] && [ -f "$publisher_archive.sha256" ] ||
    publisher_error "Missing archive/checksum for $1."
  publisher_expected=$(awk '
    NR == 1 && length($1) == 64 && $1 !~ /[^0-9a-fA-F]/ { print tolower($1); valid = 1 }
    END { if (NR != 1 || !valid) exit 1 }
  ' "$publisher_archive.sha256") || publisher_error "Invalid checksum for $1."
  if command -v sha256sum >/dev/null 2>&1; then
    publisher_actual=$(sha256sum "$publisher_archive" | awk '{ print $1 }')
  else
    publisher_actual=$(shasum -a 256 "$publisher_archive" | awk '{ print $1 }')
  fi
  [ "$publisher_actual" = "$publisher_expected" ] || publisher_error "Checksum mismatch for $1."
  publisher_bundle=cluster-ctl-$publisher_version-$1
  tar -tzf "$publisher_archive" "$publisher_bundle/cluster-ctl" "$publisher_bundle/config.example.yaml" >/dev/null ||
    publisher_error "Unexpected archive contents for $1."
}

# Validate every requested platform before invoking GitHub, then publish a draft.
publisher_main() {
  [ "$#" -ge 4 ] || publisher_error 'Usage: publish-release.sh VERSION DIRECTORY OWNER/REPO DRY_RUN [PLATFORM...]'
  publisher_version=${1#v}
  publisher_directory=$(CDPATH= cd -- "$2" && pwd) || publisher_error 'Release directory does not exist.'
  publisher_repository=$3
  publisher_dry_run=$4
  shift 4
  printf '%s\n' "$publisher_version" | awk '
    /^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$/ { valid = 1 }
    END { if (NR != 1 || !valid) exit 1 }
  ' || publisher_error 'Invalid release version.'
  printf '%s\n' "$publisher_repository" | awk '
    /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/ { valid = 1 }
    END { if (NR != 1 || !valid) exit 1 }
  ' || publisher_error 'Repository must be OWNER/REPO.'
  case "$publisher_dry_run" in true|false) ;; *) publisher_error 'DRY_RUN must be true or false.' ;; esac
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 ||
    publisher_error 'Install sha256sum or shasum.'
  [ "$#" -gt 0 ] || set -- linux-amd64 linux-arm64 macos-arm64
  for publisher_platform in "$@"; do
    case "$publisher_platform" in
      linux-amd64|linux-arm64|macos-arm64) ;;
      *) publisher_error "Unsupported platform: $publisher_platform" ;;
    esac
    publisher_verify_archive "$publisher_platform"
  done
  publisher_platforms=$*
  publisher_tag=v$publisher_version
  set -- gh release create "$publisher_tag" --repo "$publisher_repository" \
    --verify-tag --draft --title "cluster-ctl $publisher_tag" \
    --notes "Native executables and SHA-256 checksums for: $publisher_platforms."
  for publisher_platform in $publisher_platforms; do
    publisher_archive=$publisher_directory/cluster-ctl-$publisher_version-$publisher_platform.tar.gz
    set -- "$@" "$publisher_archive" "$publisher_archive.sha256"
  done
  case "$publisher_version" in *-*) set -- "$@" --prerelease ;; esac
  if [ "$publisher_dry_run" = true ]; then
    printf '[preview] %s\n' "$*"
    printf '[preview] gh release edit %s --repo %s --draft=false\n' "$publisher_tag" "$publisher_repository"
    return
  fi
  command -v gh >/dev/null 2>&1 || publisher_error 'Install GitHub CLI and run gh auth login.'
  gh auth status --hostname github.com || publisher_error 'Run gh auth login before publishing.'
  publisher_visibility=$(gh repo view "$publisher_repository" --json visibility --jq .visibility) ||
    publisher_error 'Cannot inspect the target repository.'
  [ "$publisher_visibility" = PUBLIC ] || publisher_error 'The repository must be public for token-free installation.'
  # --verify-tag requires a pushed tag; existing releases are not clobbered.
  "$@" || publisher_error 'Release creation/upload failed. Check for an existing release or an incomplete draft.'
  gh release edit "$publisher_tag" --repo "$publisher_repository" --draft=false
}

publisher_main "$@"
