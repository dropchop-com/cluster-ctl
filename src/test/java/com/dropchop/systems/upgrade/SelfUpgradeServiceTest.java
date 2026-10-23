package com.dropchop.systems.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.service.SelfUpgradeService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises actual extraction, version probing, permissions, and atomic replacement on disposable files.
 */
class SelfUpgradeServiceTest {
  private static final String BUNDLE = "cluster-ctl-0.1.10-linux-amd64";
  private static final String OLD = "#!/bin/sh\nprintf 'cluster-ctl 0.1.2\\n'\n";
  private static final String NEW = "#!/bin/sh\nprintf 'cluster-ctl 0.1.10\\n'\n";
  @TempDir
  Path directory;
  private Path binary;
  private Path archive;
  private Path checksum;
  private final StringWriter output = new StringWriter();
  private FixtureClient releases;
  private NativeInstallation installation;

  /**
   * Supplies metadata and disposable downloads without opening a network connection.
   */
  private class FixtureClient extends GitHubReleaseClient {
    private String latest = "0.1.10";
    private int downloads;
    private boolean failDownload;

    FixtureClient() {
      super(new ObjectMapper());
    }

    /** {@inheritDoc} */
    @Override
    public Release latest() {
      String name = "cluster-ctl-" + latest + "-linux-amd64.tar.gz";
      return new Release("v" + latest, new ReleaseVersion(latest), Set.of(name, name + ".sha256"));
    }

    /** {@inheritDoc} */
    @Override
    public void download(Release release, String platform, Path destination, Path sidecar) throws IOException {
      downloads++;
      Files.copy(archive, destination);
      if (failDownload) {
        throw new IOException("fixture connection interrupted");
      }
      Files.copy(checksum, sidecar);
    }
  }

  /**
   * Resolves only a disposable fixture while retaining the real installation operations.
   */
  private class FixtureInstallation extends NativeInstallation {
    private final Path invoked;

    FixtureInstallation(Path invoked) {
      this.invoked = invoked;
    }

    /** {@inheritDoc} */
    @Override
    public Path executable() {
      return invoked;
    }

    /** {@inheritDoc} */
    @Override
    public String platform() {
      return "linux-amd64";
    }
  }

  private SelfUpgradeService service() {
    return new SelfUpgradeService(releases, installation, new ReleaseArchive(), "0.1.2");
  }

  private void checksum() throws Exception {
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
    Files.writeString(checksum, digest + "  " + archive.getFileName() + "\n");
  }

  private void archive(List<String> names, String contents, boolean link) throws Exception {
    byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
    try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))) {
      for (String name : names) {
        TarArchiveEntry entry = link ? new TarArchiveEntry(name, (byte) '2') : new TarArchiveEntry(name);
        if (link) {
          entry.setLinkName("/etc/passwd");
        } else {
          entry.setSize(bytes.length);
        }
        tar.putArchiveEntry(entry);
        if (!link) {
          tar.write(bytes);
        }
        tar.closeArchiveEntry();
      }
    }
    checksum();
  }

  private void assertUnchanged() throws IOException {
    assertEquals(OLD, Files.readString(binary));
    try (var paths = Files.list(binary.getParent())) {
      assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".cluster-ctl-upgrade-")));
    }
  }

  @BeforeEach
  void createInstallation() throws Exception {
    Files.createDirectory(directory.resolve("bin"));
    binary = directory.resolve("bin/renamed-ctl");
    Files.writeString(binary, OLD);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-x---"));
    archive = directory.resolve(BUNDLE + ".tar.gz");
    checksum = directory.resolve(BUNDLE + ".tar.gz.sha256");
    releases = new FixtureClient();
    installation = new FixtureInstallation(binary);
    archive(List.of(BUNDLE + "/cluster-ctl"), NEW, false);
  }

  @Test
  void upgradesAtomicallyPreservingNamePermissionsAndVersion() throws Exception {
    service().execute(false, new PrintWriter(output));
    assertEquals(NEW, Files.readString(binary));
    assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(binary));
    assertTrue(output.toString().contains("Upgraded to 0.1.10"));
    assertEquals(1, releases.downloads);
  }

  @Test
  void replacesSymlinkTargetWithoutRemovingInvocationSymlink() throws Exception {
    Path link = directory.resolve("bin/alias-ctl");
    Files.createSymbolicLink(link, binary.getFileName());
    installation = new FixtureInstallation(link);
    service().execute(false, new PrintWriter(output));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(NEW, Files.readString(link));
    assertEquals(NEW, Files.readString(binary));
  }

  @Test
  void neverReinstallsOrDowngrades() throws IOException {
    for (String version : List.of("0.1.2", "0.1.1")) {
      releases.latest = version;
      service().execute(false, new PrintWriter(output));
      assertUnchanged();
    }
    assertEquals(0, releases.downloads);
  }

  @Test
  void dryRunChecksWithoutCreatingStagingFilesOrDownloading() throws IOException {
    service().execute(true, new PrintWriter(output));
    assertEquals(0, releases.downloads);
    assertTrue(output.toString().contains("Would download"));
    assertUnchanged();
  }

  @Test
  void rejectsTamperedChecksumBeforeExecutingCandidate() throws Exception {
    Path marker = directory.resolve("should-not-exist");
    archive(List.of(BUNDLE + "/cluster-ctl"), "#!/bin/sh\ntouch '" + marker + "'\n", false);
    Files.writeString(checksum, "0".repeat(64) + "\n");
    assertTrue(assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)))
        .getMessage().contains("checksum mismatch"));
    assertFalse(Files.exists(marker));
    assertUnchanged();
  }

  @Test
  void rejectsMalformedChecksumAndInterruptedDownloads() throws Exception {
    Files.writeString(checksum, "invalid checksum\n");
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
    releases.failDownload = true;
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
  }

  @Test
  void rejectsWrongVersionsMissingExecutablesAndUnsafeEntries() throws Exception {
    for (List<String> names : List.of(List.of("../escape"), List.of(BUNDLE + "/README.md"),
        List.of(BUNDLE + "/cluster-ctl", BUNDLE + "/cluster-ctl"))) {
      archive(names, NEW, false);
      assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
      assertUnchanged();
    }
    archive(List.of(BUNDLE + "/cluster-ctl"), NEW, true);
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
    archive(List.of(BUNDLE + "/cluster-ctl"), OLD, false);
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
  }

  @Test
  void refusesNonAtomicReplacementWithoutTouchingCurrentExecutable() throws Exception {
    installation = new FixtureInstallation(binary) {
      @Override
      protected void move(Path staged, Path target) throws IOException {
        throw new AtomicMoveNotSupportedException(staged.toString(), target.toString(), "fixture filesystem");
      }
    };
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
  }

  @Test
  void rejectsTruncatedGzipEvenWhenTheDownloadedChecksumMatches() throws Exception {
    byte[] bytes = Files.readAllBytes(archive);
    Files.write(archive, Arrays.copyOf(bytes, bytes.length - 8));
    checksum();
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
  }

  @Test
  void preservesCurrentExecutableWhenPermissionsCannotBeApplied() throws Exception {
    installation = new FixtureInstallation(binary) {
      /** {@inheritDoc} */
      @Override
      public void prepare(Snapshot original, Path staged, String version) throws IOException {
        throw new AccessDeniedException(staged.toString());
      }
    };
    assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)));
    assertUnchanged();
  }

  @Test
  void doesNotOverwriteAnInstallationChangedDuringDownload() throws Exception {
    installation = new FixtureInstallation(binary) {
      /** {@inheritDoc} */
      @Override
      public void prepare(Snapshot original, Path staged, String version) throws IOException {
        super.prepare(original, staged, version);
        Files.writeString(binary, "changed-by-another-upgrade");
      }
    };
    assertTrue(assertThrows(CliException.class, () -> service().execute(false, new PrintWriter(output)))
        .getMessage().contains("changed during self-upgrade"));
    assertEquals("changed-by-another-upgrade", Files.readString(binary));
  }

  @Test
  void rejectsJvmLaunchesAndUnsupportedPlatforms() {
    assertThrows(CliException.class, () -> new NativeInstallation().executable());
    assertEquals("linux-amd64", NativeInstallation.platform("Linux", "amd64"));
    assertEquals("linux-arm64", NativeInstallation.platform("Linux", "aarch64"));
    assertEquals("macos-arm64", NativeInstallation.platform("Mac OS X", "aarch64"));
    assertThrows(CliException.class, () -> NativeInstallation.platform("Mac OS X", "x86_64"));
    assertThrows(CliException.class, () -> NativeInstallation.platform("Windows", "amd64"));
  }
}
