package com.dropchop.systems.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ProcessProperties;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Locates the actual native executable and replaces it atomically after validation.
 */
@ApplicationScoped
public class NativeInstallation {
  /**
   * Captures the resolved executable and its identity before an upgrade starts.
   *
   * @param executable canonical target, preserving any invocation symlink
   * @param attributes original file identity, owner, group, and permissions
   */
  public record Snapshot(Path executable, PosixFileAttributes attributes) {
  }

  static String platform(String operatingSystem, String architecture) {
    String arch = architecture.toLowerCase(Locale.ROOT);
    String system = operatingSystem.toLowerCase(Locale.ROOT);
    if (system.equals("linux")) {
      if (arch.equals("amd64") || arch.equals("x86_64")) {
        return "linux-amd64";
      }
      if (arch.equals("aarch64") || arch.equals("arm64")) {
        return "linux-arm64";
      }
    }
    if ((system.equals("mac os x") || system.equals("darwin"))
        && (arch.equals("aarch64") || arch.equals("arm64"))) {
      return "macos-arm64";
    }
    throw new CliException("Self-upgrade supports Linux x86-64/ARM64 and macOS Apple Silicon only.");
  }

  /**
   * Determines the current host's published native platform.
   *
   * @return release platform identifier
   */
  public String platform() {
    return platform(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /**
   * Resolves the native image itself, never the JVM, a PATH match, or an argv[0] alias.
   *
   * @return canonical native executable
   */
  public Path executable() {
    if (!ImageInfo.inImageRuntimeCode()) {
      throw new CliException("Self-upgrade requires an installed native executable; JVM launches cannot upgrade themselves.");
    }
    String name = ProcessProperties.getExecutableName();
    if (name == null || name.isBlank()) {
      throw new CliException("Unable to locate the running native executable.");
    }
    return Path.of(name);
  }

  /**
   * Records the canonical executable's identity for later concurrent-change detection.
   *
   * @return original native installation
   * @throws IOException when the executable cannot be inspected
   */
  public Snapshot inspect() throws IOException {
    Path target = executable().toRealPath();
    PosixFileAttributes attributes = Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || !Files.isExecutable(target)) {
      throw new CliException("The running executable is not a regular executable file: " + target);
    }
    return new Snapshot(target, attributes);
  }

  private void verifyVersion(Path staged, String version) throws IOException {
    Process process = new ProcessBuilder(staged.toString(), "--version").redirectErrorStream(true).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        // Bound both output and execution time before trusting the new executable.
        process.getOutputStream().close();
        var output = executor.submit(() -> process.getInputStream().readNBytes(4097));
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          throw new CliException("New executable version check timed out; current executable was not changed.");
        }
        byte[] bytes = output.get(2, TimeUnit.SECONDS);
        String reported = new String(bytes, StandardCharsets.UTF_8).strip();
        if (bytes.length > 4096 || process.exitValue() != 0 || !reported.equals("cluster-ctl " + version)) {
          throw new CliException("New executable failed its version check; current executable was not changed.");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Self-upgrade was interrupted.", exception);
      } catch (ExecutionException | TimeoutException exception) {
        throw new IOException("Unable to read the new executable's version.", exception);
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
        process.getInputStream().close();
      }
    }
  }

  /**
   * Preserves the installation's ownership and permissions and checks the staged version.
   *
   * @param original installation being upgraded
   * @param staged private replacement file beside the executable
   * @param version expected release version
   * @throws IOException when metadata cannot be preserved or the executable cannot run
   */
  public void prepare(Snapshot original, Path staged, String version) throws IOException {
    PosixFileAttributes attributes = original.attributes();
    if (!Files.getOwner(staged).equals(attributes.owner())) {
      throw new CliException("Executable is owned by another user; upgrade it using its installation owner.");
    }
    PosixFileAttributeView view = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
    view.setGroup(attributes.group());
    // The candidate is not executable until its archive checksum has been verified.
    view.setPermissions(attributes.permissions());
    verifyVersion(staged, version);
  }

  protected void move(Path staged, Path target) throws IOException {
    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Atomically replaces an unchanged installation, refusing unsafe in-place-copy fallbacks.
   *
   * @param original installation snapshot captured before downloading
   * @param staged verified replacement on the same filesystem
   * @throws IOException when an atomic replacement is unavailable
   */
  public void replace(Snapshot original, Path staged) throws IOException {
    PosixFileAttributes before = original.attributes();
    PosixFileAttributes now = Files.readAttributes(
        original.executable(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!now.isRegularFile() || !Objects.equals(before.fileKey(), now.fileKey())
        || before.size() != now.size() || !before.lastModifiedTime().equals(now.lastModifiedTime())
        || !before.owner().equals(now.owner()) || !before.group().equals(now.group())
        || !before.permissions().equals(now.permissions())) {
      throw new CliException("Executable changed during self-upgrade; retry using the current installation.");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new IOException("Self-upgrade was interrupted.");
    }
    move(staged, original.executable());
  }
}
