#!/bin/sh
# Exercise publishing preflight and draft ordering with synthetic assets and gh.
set -eu

# Remove only the generated test sandbox.
release_test_cleanup() {
  rm -rf -- "$release_test_directory"
}

# Run the publisher with the fixture version and repository.
release_test_publish() {
  sh "$release_test_root/scripts/publish-release.sh" 0.1.0 "$release_test_assets" example-org/example-cli "$1"
}

# Require a rejected release without swallowing unexpected failures.
release_test_expect_failure() {
  if release_test_publish false > "$release_test_directory/output.log" 2>&1; then
    printf 'Expected publishing failure.\n' >&2
    exit 1
  fi
}

# Create complete archives containing only synthetic executable/config fixtures.
release_test_create_assets() {
  for release_test_platform in linux-amd64 linux-arm64 macos-arm64; do
    release_test_bundle=cluster-ctl-0.1.0-$release_test_platform
    mkdir -p "$release_test_directory/staging/$release_test_bundle"
    cp "$release_test_root/src/test/sh/fixtures/cluster-ctl" "$release_test_directory/staging/$release_test_bundle/cluster-ctl"
    cp "$release_test_root/config.example.yaml" "$release_test_directory/staging/$release_test_bundle/config.example.yaml"
    release_test_archive=$release_test_assets/$release_test_bundle.tar.gz
    tar -czf "$release_test_archive" -C "$release_test_directory/staging" "$release_test_bundle"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$release_test_archive" > "$release_test_archive.sha256"
    else
      shasum -a 256 "$release_test_archive" > "$release_test_archive.sha256"
    fi
  done
}

# Verify valid public assets are uploaded as a draft before publication.
release_test_success() {
  release_test_publish false > "$release_test_directory/output.log"
  grep -q 'release create v0.1.0.*--verify-tag --draft' "$PUBLISH_TEST_LOG"
  grep -q 'release edit v0.1.0.*--draft=false' "$PUBLISH_TEST_LOG"
  for release_test_platform in linux-amd64 linux-arm64 macos-arm64; do
    grep -q "cluster-ctl-0.1.0-$release_test_platform.tar.gz.sha256" "$PUBLISH_TEST_LOG"
  done
  test "$(tail -n 1 "$PUBLISH_TEST_LOG")" = 'release edit v0.1.0 --repo example-org/example-cli --draft=false'
  printf 'PASS: draft upload precedes public release\n'
}

# Reject incomplete/corrupt assets, private repositories, and failed uploads.
release_test_failures() {
  : > "$PUBLISH_TEST_LOG"
  mv "$release_test_assets/cluster-ctl-0.1.0-macos-arm64.tar.gz.sha256" "$release_test_directory/saved.sha256"
  release_test_expect_failure
  test ! -s "$PUBLISH_TEST_LOG"
  mv "$release_test_directory/saved.sha256" "$release_test_assets/cluster-ctl-0.1.0-macos-arm64.tar.gz.sha256"
  cp "$release_test_assets/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256" "$release_test_directory/saved.sha256"
  printf '%064d\n' 0 > "$release_test_assets/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256"
  release_test_expect_failure
  test ! -s "$PUBLISH_TEST_LOG"
  cp "$release_test_directory/saved.sha256" "$release_test_assets/cluster-ctl-0.1.0-linux-amd64.tar.gz.sha256"
  PUBLISH_TEST_VISIBILITY=PRIVATE
  export PUBLISH_TEST_VISIBILITY
  release_test_expect_failure
  if grep -q 'release create' "$PUBLISH_TEST_LOG"; then exit 1; fi
  PUBLISH_TEST_VISIBILITY=PUBLIC
  PUBLISH_TEST_FAIL_CREATE=true
  export PUBLISH_TEST_VISIBILITY PUBLISH_TEST_FAIL_CREATE
  : > "$PUBLISH_TEST_LOG"
  release_test_expect_failure
  if grep -q 'release edit' "$PUBLISH_TEST_LOG"; then exit 1; fi
  unset PUBLISH_TEST_FAIL_CREATE
  : > "$PUBLISH_TEST_LOG"
  release_test_publish true > "$release_test_directory/output.log"
  test ! -s "$PUBLISH_TEST_LOG"
  grep -q '\[preview\]' "$release_test_directory/output.log"
  printf 'PASS: preflight failures, upload failure, and no-write preview\n'
}

release_test_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
release_test_directory=$(mktemp -d "${TMPDIR:-/tmp}/cluster-ctl-release-test.XXXXXXXX")
trap release_test_cleanup 0
trap 'exit 1' 1 2 15
release_test_assets=$release_test_directory/assets
mkdir -p "$release_test_assets" "$release_test_directory/tools"
cp "$release_test_root/src/test/sh/fixtures/gh" "$release_test_directory/tools/gh"
chmod 755 "$release_test_directory/tools/gh"
PUBLISH_TEST_LOG=$release_test_directory/gh.log
PATH=$release_test_directory/tools:$PATH
export PUBLISH_TEST_LOG PATH
unset PUBLISH_TEST_VISIBILITY PUBLISH_TEST_FAIL_CREATE
release_test_create_assets
release_test_success
release_test_failures
