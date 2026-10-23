package com.dropchop.systems.upgrade;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.dropchop.systems.cli.CliException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fetches public GitHub release metadata and size-limited assets over HTTPS without credentials.
 */
@ApplicationScoped
public class GitHubReleaseClient {
  private static final URI LATEST = URI.create("https://api.github.com/repos/dropchop-com/cluster-ctl/releases/latest");
  private static final URI DOWNLOADS = URI.create("https://github.com/dropchop-com/cluster-ctl/releases/download/");
  private final ObjectMapper mapper;
  private final URI latest;
  private final URI downloads;

  /**
   * Describes the latest full release and its advertised asset names.
   *
   * @param tag release tag
   * @param version semantic version
   * @param assets available release assets
   */
  public record Release(String tag, ReleaseVersion version, Set<String> assets) {
    /**
     * Resolves and validates the archive name for one supported platform.
     *
     * @param platform supported OS/architecture identifier
     * @return published archive name
     */
    public String archiveName(String platform) {
      String name = "cluster-ctl-" + version.value() + "-" + platform + ".tar.gz";
      if (!assets.contains(name) || !assets.contains(name + ".sha256")) {
        throw new CliException("Release " + tag + " is missing the archive or SHA-256 checksum for " + platform + ".");
      }
      return name;
    }
  }

  // Alternate endpoints are an in-process test seam, never CLI or environment settings.
  GitHubReleaseClient(ObjectMapper mapper, URI latest, URI downloads) {
    this.mapper = mapper;
    this.latest = latest;
    this.downloads = downloads;
  }

  /**
   * Creates the public-repository release client.
   *
   * @param mapper JSON reader
   */
  @Inject
  public GitHubReleaseClient(ObjectMapper mapper) {
    this(mapper, LATEST, DOWNLOADS);
  }

  private void checkUri(URI uri) throws IOException {
    boolean testLoopback = "http".equals(latest.getScheme()) && "http".equals(uri.getScheme())
        && "127.0.0.1".equals(latest.getHost()) && latest.getHost().equals(uri.getHost())
        && latest.getPort() == uri.getPort();
    if ((!"https".equals(uri.getScheme()) && !testLoopback) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getFragment() != null) {
      throw new IOException("Refusing an insecure release URL or redirect.");
    }
  }

  private void transfer(URI source, OutputStream destination, long maximum) throws IOException {
    URI uri = source;
    for (int redirects = 0; redirects <= 5; redirects++) {
      checkUri(uri);
      HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(60_000);
      connection.setRequestProperty("User-Agent", "cluster-ctl-self-upgrade");
      connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
      try {
        int status = connection.getResponseCode();
        if (Set.of(301, 302, 303, 307, 308).contains(status)) {
          String location = connection.getHeaderField("Location");
          if (location == null || redirects == 5) {
            throw new IOException("Invalid or excessive release download redirects.");
          }
          try {
            uri = uri.resolve(location);
          } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid release download redirect.", exception);
          }
          continue;
        }
        if (status != 200) {
          String reason = switch (status) {
            case 404 -> "No public release or matching release asset was found.";
            case 403, 429 -> "GitHub denied the request or its API rate limit was reached; try again later.";
            default -> "Release request failed with HTTP " + status + ".";
          };
          throw new IOException(reason);
        }
        if (connection.getContentLengthLong() > maximum) {
          throw new IOException("Release response exceeds its size limit.");
        }
        try (InputStream input = connection.getInputStream()) {
          byte[] buffer = new byte[16_384];
          long count = 0;
          for (int read; (read = input.read(buffer)) != -1;) {
            count += read;
            if (count > maximum || Thread.currentThread().isInterrupted()) {
              throw new IOException("Release download exceeded its size limit or was interrupted.");
            }
            destination.write(buffer, 0, read);
          }
        }
        return;
      } finally {
        connection.disconnect();
      }
    }
  }

  /**
   * Reads GitHub's latest non-draft, non-prerelease release and validates its tag.
   *
   * @return latest stable release
   */
  public Release latest() {
    try {
      ByteArrayOutputStream json = new ByteArrayOutputStream();
      transfer(latest, json, 1_048_576);
      JsonNode document = mapper.readTree(json.toByteArray());
      if (document == null || !document.isObject() || !document.path("draft").isBoolean()
          || !document.path("prerelease").isBoolean() || document.path("draft").asBoolean()
          || document.path("prerelease").asBoolean() || !document.path("assets").isArray()) {
        throw new CliException("GitHub did not return a published stable release.");
      }
      String tag = document.path("tag_name").asText();
      ReleaseVersion version = new ReleaseVersion(tag);
      if (!version.stable()) {
        throw new CliException("Self-upgrade only installs stable releases.");
      }
      Set<String> assets = new LinkedHashSet<>();
      for (JsonNode asset : document.path("assets")) {
        assets.add(asset.path("name").asText());
      }
      return new Release(tag, version, Set.copyOf(assets));
    } catch (IOException | IllegalArgumentException exception) {
      throw new CliException("Unable to check GitHub Releases: " + exception.getMessage(), exception);
    }
  }

  /**
   * Downloads the selected archive and its checksum into caller-owned temporary paths.
   *
   * @param release validated stable release
   * @param platform supported OS/architecture identifier
   * @param archive temporary archive path
   * @param checksum temporary checksum path
   * @throws IOException when a download fails
   */
  public void download(Release release, String platform, Path archive, Path checksum) throws IOException {
    String name = release.archiveName(platform);
    URI root = downloads.resolve(release.tag() + "/");
    try (OutputStream output = Files.newOutputStream(checksum)) {
      transfer(root.resolve(name + ".sha256"), output, 4096);
    }
    try (OutputStream output = Files.newOutputStream(archive)) {
      transfer(root.resolve(name), output, 256L * 1024 * 1024);
    }
  }
}
