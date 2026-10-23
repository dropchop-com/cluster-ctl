# Development

Build, test, extension, and release instructions for `cluster-ctl`.
For installation and runtime configuration, see [README.md](README.md).

## Replaceable CDI components

The Picocli subcommands are thin CDI beans. Behavior is selected at runtime by
terminal detection, the terminal environment override, or YAML discriminators:

| Selection source | CDI contract | Included implementations |
| --- | --- | --- |
| `CLUSTER_CTL_LOCAL_TERMINAL` or terminal detection | `TerminalStrategy` | `konsole`, `tmux`, `kitty`, `iterm` |
| `local.build.type` | `BuildStrategy` | `maven` |
| `remote.environments[].deploy.type` | `DeploymentStrategy` | `scp` |
| `remote.clusters[].control` | `RemoteControlStrategy` | `ssh` |

External tool arguments are passed directly to `ProcessBuilder`. Konsole's
D-Bus `sendText` executes a safely quoted SSH command in the new tab's shell.

A new implementation only needs to be a CDI bean, implement the relevant
interface, and return its strategy discriminator from `type()`. Command parsing,
config loading, alias resolution, dry-run handling, and validation remain
reusable. The included remote-control implementation supports SSH;
`ssh-docker` and `kubernetes` can be added through the same contract when their
container/pod metadata is defined.

## Build and test

Requirements for a JVM build are JDK 21 and Maven, or the included Maven
wrapper. Runtime commands additionally require only the tools they invoke,
such as `ssh`, `scp`, `docker`, `mvn`, and the selected terminal.

Run the dispatch tests:

```bash
./mvnw test
sh src/test/sh/install-test.sh
sh src/test/sh/release-test.sh
sh src/test/sh/release-profile-test.sh
```

The tests launch the real Quarkus main application and cover help routing, Docker,
terminal, exec, all three SSH-key commands, Maven build selection, SCP deploy
selection, aliases, multi-cluster routing, and legacy Compose behavior. Cluster
operations run in dry-run mode. Upgrade tests use a loopback HTTP server and
disposable fixture executables: no real installation is replaced or public
release downloaded. They cover numeric version comparison, SHA-256 checks,
safe archive extraction, version probing, symlinks, permissions, concurrent
changes, failed downloads, and atomic replacement failures.
Help checks cover flag handling and inventory scope without locking in spacing
or table-layout snapshots.

The installer tests use offline fixture packages to cover sh, Bash, and Zsh,
platform detection, token-free downloads, binary-only installation, reinstalls,
and config preservation.
Release tests use synthetic assets and a mock GitHub CLI to check checksum
preflight, draft ordering, failure handling, and preview behavior.

Build the JVM application:

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar --config ./config.example.yaml --help
```

Build a native executable with a local GraalVM installation:

```bash
./mvnw package -Dnative
```

Or build it in a container:

```bash
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

The executable is produced at:

```text
target/cluster-ctl
```

Copy that `cluster-ctl` executable on machines with the same operating
system and CPU architecture. No JVM is required at runtime.

## Native releases

Native releases are built and published locally with Maven, without GitHub
Actions or CI/CD. Build each target on a matching OS/architecture:

| Target | Release asset | Platform |
| --- | --- | --- |
| Linux x86-64 | `cluster-ctl-0.1.2-linux-amd64.tar.gz` | `linux-amd64` |
| Linux ARM64 | `cluster-ctl-0.1.2-linux-arm64.tar.gz` | `linux-arm64` |
| macOS Apple Silicon | `cluster-ctl-0.1.2-macos-arm64.tar.gz` | `macos-arm64` |

Linux builds use the UBI 9 Mandrel JDK 25 builder for glibc 2.34 or newer.
All builds use `quarkus.native.march=compatibility` for baseline CPU instructions.
macOS requires Apple Silicon, GraalVM JDK 25, and Xcode Command Line Tools;
its minimum OS version depends on the build host/SDK. macOS binaries are
ad-hoc signed, not notarized. Intel macOS and 32-bit targets are not supported.
Maven cannot cross-compile macOS executables from Linux.

Build and verify on each matching machine, using the same committed sources:

```bash
./mvnw -Pnative-release clean verify
```

Linux uses Docker by default; add `-Dquarkus.native.container-build=false` to
use local GraalVM instead. The profile runs tests, checks the executable version,
smoke-tests help and a dry-run exec, and creates archives/checksums under
`target/release`. Use `verify`, not just `package`, to generate checksums.

Each archive contains `cluster-ctl`, README, and `config.example.yaml`. Real
environment YAML, legacy samples, and credentials are excluded.

### Publish GitHub Release assets

Collect all three archives and their `.sha256` files in one machine's
`target/release`. Publishing uses GitHub Releases,
which permits token-free installation when the repository is public.

Maintainers need GitHub CLI (`gh`) and a login with repository write access.
This is publisher authentication, not a requirement for installer users.
Commit/push sanitized sources, make the repository public, and push a tag
matching the Maven version before publishing:

```bash
gh auth login
git tag v0.1.2
git push origin v0.1.2
./mvnw -Ppublish-release deploy
```

The `publish-release` profile publishes existing archives without compiling or
running application tests. Do not add `clean`: it removes the collected archives
from `target/release`. The version comes from the main `pom.xml`.

The publisher checks every archive/checksum and expected archive members before
contacting GitHub. It requires a public repository and an already-pushed tag,
uploads a draft, and publishes only after the upload succeeds. Existing releases
are not overwritten. Failed uploads may leave a draft for manual review/repair;
the script does not delete it automatically.
See [GitHub CLI release creation](https://cli.github.com/manual/gh_release_create).

For safe validation without login or uploads, optionally add
`-Drelease.dry-run=true`.
Use `-Drelease.directory=/absolute/path/to/archives` for another collection directory
or `-Drelease.github.repository=OWNER/REPO` for a different GitHub repository.

For an intentionally single-platform release, build and publish directly:

```bash
./mvnw -Pnative-release clean deploy
```

That release includes only the current platform; collect all three targets and
use `-Ppublish-release` instead for the complete release. Do not publish a partial
release first if you plan to publish all targets under the same version.

For a new version, update the main POM, build the committed sources, and push the
matching `vVERSION` tag. Tags do not trigger builds; Maven publishing does not
create or push tags.
v2NARsJi3XYNkuMmiQZX
## Before making the repository public

Review the committed tree, all reachable history and tags, and hosted workflow
logs/artifacts. Removing data from the latest commit or adding it to
`.gitignore` does not remove historical copies.

- Use fictional `example.test` hosts and synthetic accounts, paths, artifacts,
  registry images, and deployment mappings in examples, fixtures, and assertions.
- Keep real environment YAML and credentials outside the public repository.
- Check historical branches/tags as well as the current branch before publication.
- Review retained workflow logs and archives, including bundled documentation.
  [GitHub makes Actions history and logs visible when a repository becomes public](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility).
- Coordinate any history rewrite and hosted-data removal with maintainers;
  these operations need explicit approval and a recovery plan.

## TODO

- Color `exec` stderr orange and stream it separately from stdout.
- Format the exec header as `[timestamp] Exec on host USER@HOST => [command]`.
