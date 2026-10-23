#!/bin/sh
# Exercise the publish-only Maven profile with synthetic archives and no uploads.
set -eu

# Remove only this test's generated sandbox.
release_profile_test_cleanup() {
  rm -rf -- "$release_profile_test_directory"
}

# Invoke the real deploy lifecycle in preview mode, forwarding Maven options.
release_profile_test_maven() {
  "$release_profile_test_root/mvnw" --batch-mode -f "$release_profile_test_root/pom.xml" \
    -Ppublish-release -Drelease.dry-run=true \
    "-Drelease.directory=$release_profile_test_assets" \
    "$@" deploy > "$release_profile_test_directory/maven.log" 2>&1
}

# Create all platform archives with the version declared in the main POM.
release_profile_test_create_assets() {
  for release_profile_test_platform in linux-amd64 linux-arm64 macos-arm64; do
    release_profile_test_bundle=cluster-ctl-$release_profile_test_version-$release_profile_test_platform
    mkdir -p "$release_profile_test_directory/staging/$release_profile_test_bundle"
    cp "$release_profile_test_root/src/test/sh/fixtures/cluster-ctl" \
      "$release_profile_test_directory/staging/$release_profile_test_bundle/cluster-ctl"
    cp "$release_profile_test_root/config.example.yaml" \
      "$release_profile_test_directory/staging/$release_profile_test_bundle/config.example.yaml"
    release_profile_test_archive=$release_profile_test_assets/$release_profile_test_bundle.tar.gz
    tar -czf "$release_profile_test_archive" -C "$release_profile_test_directory/staging" "$release_profile_test_bundle"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$release_profile_test_archive" > "$release_profile_test_archive.sha256"
    else
      shasum -a 256 "$release_profile_test_archive" > "$release_profile_test_archive.sha256"
    fi
  done
}

# Require a successful preview without compilation, tests, or publication.
release_profile_test_success() {
  if ! release_profile_test_maven "$@"; then
    tail -n 60 "$release_profile_test_directory/maven.log" >&2
    exit 1
  fi
  grep -q "\[preview\] gh release create v$release_profile_test_version" "$release_profile_test_directory/maven.log"
  grep -q 'publish-github-release' "$release_profile_test_directory/maven.log"
  test ! -s "$PUBLISH_TEST_LOG"
  if grep -Eq -- '--- (quarkus:|compiler:|surefire:)|:deploy-file ' "$release_profile_test_directory/maven.log"; then
    printf 'Publish-only profile unexpectedly built the application or deployed Maven packages.\n' >&2
    exit 1
  fi
  printf 'PASS: publish-only preview uses the main version without building or uploading\n'
}

# Reject damaged assets and incompatible native-build profiles before publishing.
release_profile_test_failures() {
  release_profile_test_checksum=$release_profile_test_assets/cluster-ctl-$release_profile_test_version-linux-amd64.tar.gz.sha256
  cp "$release_profile_test_checksum" "$release_profile_test_directory/saved.sha256"
  printf '%064d\n' 0 > "$release_profile_test_checksum"
  if release_profile_test_maven "$@"; then
    printf 'Expected checksum validation failure.\n' >&2
    exit 1
  fi
  grep -q 'Release archives/checksums are missing or invalid' "$release_profile_test_directory/maven.log"
  cp "$release_profile_test_directory/saved.sha256" "$release_profile_test_checksum"
  if release_profile_test_maven "$@" -Pnative-release; then
    printf 'Expected conflicting profiles to fail.\n' >&2
    exit 1
  fi
  grep -q 'Use publish-release separately' "$release_profile_test_directory/maven.log"
  test ! -s "$PUBLISH_TEST_LOG"
  printf 'PASS: invalid checksums and conflicting build profiles are rejected\n'
}

release_profile_test_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
release_profile_test_directory=$(mktemp -d "${TMPDIR:-/tmp}/cluster-ctl-release-profile-test.XXXXXXXX")
trap release_profile_test_cleanup 0
trap 'exit 1' 1 2 15
release_profile_test_assets=$release_profile_test_directory/assets
release_profile_test_version=$(sed -n 's/^  <version>\(.*\)<\/version>$/\1/p' "$release_profile_test_root/pom.xml")
test -n "$release_profile_test_version"
mkdir -p "$release_profile_test_assets" "$release_profile_test_directory/tools"
cp "$release_profile_test_root/src/test/sh/fixtures/gh" "$release_profile_test_directory/tools/gh"
chmod 755 "$release_profile_test_directory/tools/gh"
PUBLISH_TEST_LOG=$release_profile_test_directory/gh.log
PATH=$release_profile_test_directory/tools:$PATH
export PUBLISH_TEST_LOG PATH
release_profile_test_create_assets
release_profile_test_success "$@"
release_profile_test_failures "$@"
