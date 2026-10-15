#!/bin/sh
# Exercise installation and failure safeguards using offline fixture packages.
set -eu

# Remove only this test's generated sandbox.
cleanup_tests() {
  rm -rf -- "$install_test_dir"
}

# Fail if the expected error is missing or a failure unexpectedly installs a file.
expect_failure() {
  if sh "$install_test_root/scripts/install.sh" --install-dir "$install_test_bin_dir" "$@" > "$install_test_dir/error.log" 2>&1; then
    printf 'Expected installer failure.\n' >&2
    exit 1
  fi
}

# Check diagnostics using ripgrep when installed, otherwise the system grep.
expect_error() {
  if command -v rg >/dev/null 2>&1; then
    rg -q "$1" "$install_test_dir/error.log"
  else
    grep -q "$1" "$install_test_dir/error.log"
  fi
}

# Build tiny, credential-free packages for each supported platform.
create_packages() {
  for install_test_platform in linux-amd64 linux-arm64 macos-arm64; do
    install_test_bundle=cluster-ctl-0.1.0-$install_test_platform
    mkdir -p "$install_test_dir/staging/$install_test_bundle"
    cp "$install_test_root/src/test/sh/fixtures/cluster-ctl" "$install_test_dir/staging/$install_test_bundle/cluster-ctl"
    install_test_archive=$INSTALL_TEST_PACKAGES/cluster-ctl-0.1.0-$install_test_platform.tar.gz
    tar -czf "$install_test_archive" -C "$install_test_dir/staging" "$install_test_bundle"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$install_test_archive" > "$install_test_archive.sha256"
    else
      shasum -a 256 "$install_test_archive" > "$install_test_archive.sha256"
    fi
  done
}

# Run the actual installer under all available requested shells.
test_installations() {
  for install_test_shell in sh bash zsh; do
    if ! command -v "$install_test_shell" >/dev/null 2>&1; then
      printf 'SKIP: %s is not installed.\n' "$install_test_shell"
      continue
    fi
    for install_test_platform in linux-amd64 linux-arm64 macos-arm64; do
      case "$install_test_platform" in
        linux-amd64) INSTALL_TEST_OS=Linux; INSTALL_TEST_ARCH=x86_64 ;;
        linux-arm64) INSTALL_TEST_OS=Linux; INSTALL_TEST_ARCH=aarch64 ;;
        macos-arm64) INSTALL_TEST_OS=Darwin; INSTALL_TEST_ARCH=arm64 ;;
      esac
      install_test_bin_dir="$install_test_dir/$install_test_shell-$install_test_platform/bin with spaces"
      XDG_CONFIG_HOME="$install_test_dir/$install_test_shell-$install_test_platform/config with spaces"
      install_test_config_dir=$XDG_CONFIG_HOME/cluster-ctl
      export INSTALL_TEST_OS INSTALL_TEST_ARCH XDG_CONFIG_HOME
      # Reading from stdin also exercises curl | sh's execution model.
      "$install_test_shell" -s -- --version v0.1.0 --install-dir "$install_test_bin_dir" < "$install_test_root/scripts/install.sh" > "$install_test_dir/install.log"
      test "$("$install_test_bin_dir/cluster-ctl" --version)" = 'cluster-ctl 0.1.0'
      test -x "$install_test_bin_dir/cluster-ctl"
      test ! -e "$XDG_CONFIG_HOME"
      test ! -e "$CLUSTER_CTL_CONFIG_DIR"
      test ! -e "$CLUSTER_CTL_INSTALL_DIR"
      # User-owned configuration remains untouched on reinstalls.
      mkdir -p "$install_test_config_dir"
      printf 'user config\n' > "$install_test_config_dir/config.yaml"
      printf 'user example\n' > "$install_test_config_dir/config.example.yaml"
      "$install_test_shell" "$install_test_root/scripts/install.sh" --install-dir "$install_test_bin_dir" > "$install_test_dir/install.log"
      test "$(head -n 1 "$install_test_config_dir/config.yaml")" = 'user config'
      test "$(head -n 1 "$install_test_config_dir/config.example.yaml")" = 'user example'
      test ! -e "$CLUSTER_CTL_CONFIG_DIR"
      test ! -e "$CLUSTER_CTL_INSTALL_DIR"
      printf 'PASS: %s / %s (install and reinstall)\n' "$install_test_shell" "$install_test_platform"
    done
  done
}

# Verify failures leave existing installations alone and reject unsafe arguments.
test_failures() {
  INSTALL_TEST_OS=Linux
  INSTALL_TEST_ARCH=x86_64
  export INSTALL_TEST_OS INSTALL_TEST_ARCH
  install_test_bin_dir="$install_test_dir/failures/bin"
  mkdir -p "$install_test_bin_dir"
  cp "$install_test_root/src/test/sh/fixtures/cluster-ctl" "$install_test_bin_dir/cluster-ctl"
  cp "$INSTALL_TEST_PACKAGES/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256" "$install_test_dir/original.sha256"
  printf '%064d\n' 0 > "$INSTALL_TEST_PACKAGES/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256"
  expect_failure
  expect_error 'SHA-256 checksum mismatch'
  cmp "$install_test_root/src/test/sh/fixtures/cluster-ctl" "$install_test_bin_dir/cluster-ctl"
  cp "$install_test_dir/original.sha256" "$INSTALL_TEST_PACKAGES/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256"
  printf 'not a checksum\n' > "$INSTALL_TEST_PACKAGES/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256"
  expect_failure
  expect_error 'Invalid SHA-256'
  expect_failure --version ../../unsafe
  expect_failure --install-dir relative/path
  INSTALL_TEST_OS=Darwin
  INSTALL_TEST_ARCH=x86_64
  export INSTALL_TEST_OS INSTALL_TEST_ARCH
  expect_failure
  expect_error 'Unsupported platform'
  INSTALL_TEST_OS=Linux
  export INSTALL_TEST_OS
  printf 'PASS: checksum, argument, and platform failures\n'
}

install_test_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
install_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/cluster-ctl-install-test.XXXXXXXX")
trap cleanup_tests 0
trap 'exit 1' 1 2 15
mkdir -p "$install_test_dir/tools" "$install_test_dir/packages"
cp "$install_test_root/src/test/sh/fixtures/curl" "$install_test_root/src/test/sh/fixtures/uname" "$install_test_dir/tools/"
chmod 755 "$install_test_dir/tools/curl" "$install_test_dir/tools/uname"
INSTALL_TEST_PACKAGES=$install_test_dir/packages
PATH=$install_test_dir/tools:$PATH
export INSTALL_TEST_PACKAGES PATH
# Removed overrides must be ignored, including a non-GitHub download URL.
CLUSTER_CTL_RELEASE_URL=https://invalid.example.test/releases
CLUSTER_CTL_INSTALL_DIR=$install_test_dir/ignored-bin
CLUSTER_CTL_CONFIG_DIR=$install_test_dir/ignored-config
export CLUSTER_CTL_RELEASE_URL CLUSTER_CTL_INSTALL_DIR CLUSTER_CTL_CONFIG_DIR
unset GITHUB_USER GITHUB_TOKEN GH_TOKEN
create_packages
test_installations
test_failures
