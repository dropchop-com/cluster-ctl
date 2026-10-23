package com.dropchop.systems.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.upgrade.GitHubReleaseClient;
import com.dropchop.systems.upgrade.NativeInstallation;
import com.dropchop.systems.upgrade.ReleaseArchive;
import com.dropchop.systems.upgrade.ReleaseVersion;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Upgrades the native installation independently of cluster configuration.
 */
@ApplicationScoped
public class SelfUpgradeService {
  private final GitHubReleaseClient releases;
  private final NativeInstallation installation;
  private final ReleaseArchive archives;
  private final String version;

  /**
   * Creates the self-upgrade workflow from replaceable CDI services.
   *
   * @param releases public release metadata and downloads
   * @param installation native installation discovery and atomic replacement
   * @param archives checksum validation and safe extraction
   * @param version installed Maven build version
   */
  public SelfUpgradeService(GitHubReleaseClient releases, NativeInstallation installation, ReleaseArchive archives,
      @ConfigProperty(name = "quarkus.application.version") String version) {
    this.releases = releases;
    this.installation = installation;
    this.archives = archives;
    this.version = version;
  }

  private void cleanup(Path path, PrintWriter out) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      out.println("Warning: unable to remove temporary upgrade path: " + path);
    }
  }

  /**
   * Installs a newer stable release, or checks availability without changing files.
   *
   * @param dryRun whether to check availability without downloading or replacing
   * @param out destination for progress and status messages
   */
  public void execute(boolean dryRun, PrintWriter out) {
    Path work = null;
    Path staged = null;
    try {
      String platform = installation.platform();
      NativeInstallation.Snapshot original = installation.inspect();
      ReleaseVersion current = new ReleaseVersion(version);
      out.println("Checking GitHub Releases (installed " + version + ", " + platform + ")...");
      out.flush();
      GitHubReleaseClient.Release release = releases.latest();
      if (release.version().compareTo(current) <= 0) {
        out.println("No newer stable release: installed " + version + ", latest " + release.version().value() + ".");
        return;
      }
      String name = release.archiveName(platform);
      out.println("New version available: " + version + " -> " + release.version().value());
      if (dryRun) {
        out.println("Would download " + name + ", verify SHA-256 and version, and replace " + original.executable());
        return;
      }
      if (!Files.isWritable(original.executable().getParent())) {
        throw new CliException("Executable directory is not writable: " + original.executable().getParent()
            + ". Run the installer with an appropriate writable --install-dir instead.");
      }
      // Never overwrite a running executable in place; stage beside it for an atomic rename.
      staged = Files.createTempFile(original.executable().getParent(), ".cluster-ctl-upgrade-", ".tmp");
      work = Files.createTempDirectory("cluster-ctl-upgrade-");
      Path archive = work.resolve("archive.tar.gz");
      Path checksum = work.resolve("archive.sha256");
      out.println("Downloading and verifying " + name + "...");
      out.flush();
      releases.download(release, platform, archive, checksum);
      archives.extract(archive, checksum, name.substring(0, name.length() - ".tar.gz".length()), staged);
      installation.prepare(original, staged, release.version().value());
      installation.replace(original, staged);
      out.println("Upgraded to " + release.version().value() + ": " + original.executable());
      out.println("Run the command again to use the new version.");
    } catch (IOException exception) {
      throw new CliException("Self-upgrade failed before replacement; current executable was not changed: "
          + exception.getMessage(), exception);
    } finally {
      cleanup(staged, out);
      if (work != null) {
        cleanup(work.resolve("archive.tar.gz"), out);
        cleanup(work.resolve("archive.sha256"), out);
        cleanup(work, out);
      }
      out.flush();
    }
  }
}
