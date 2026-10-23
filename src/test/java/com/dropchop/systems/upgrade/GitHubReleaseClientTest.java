package com.dropchop.systems.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dropchop.systems.cli.CliException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises real HTTP requests against local fixtures, never public release endpoints.
 */
class GitHubReleaseClientTest {
  @TempDir
  Path directory;
  private HttpServer server;
  private URI root;
  private GitHubReleaseClient client;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile int status = 200;
  private volatile String metadata;
  private volatile String redirect;

  @BeforeEach
  void startServer() throws IOException {
    metadata = """
        {"tag_name":"v0.1.10","draft":false,"prerelease":false,"assets":[
          {"name":"cluster-ctl-0.1.10-linux-amd64.tar.gz"},
          {"name":"cluster-ctl-0.1.10-linux-amd64.tar.gz.sha256"}]}
        """;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      assertEquals("GET", exchange.getRequestMethod());
      assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = (exchange.getRequestURI().getPath().equals("/latest") ? metadata : "fixture-asset")
          .getBytes(StandardCharsets.UTF_8);
      if (redirect != null) {
        exchange.getResponseHeaders().add("Location", redirect);
      }
      exchange.sendResponseHeaders(status, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    client = new GitHubReleaseClient(new ObjectMapper(), root.resolve("latest"), root.resolve("downloads/"));
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void readsStableMetadataAndDownloadsBothPublicAssetsWithoutCredentials() throws IOException {
    var release = client.latest();
    assertEquals("0.1.10", release.version().value());
    client.download(release, "linux-amd64", directory.resolve("archive"), directory.resolve("checksum"));
    assertEquals("fixture-asset", Files.readString(directory.resolve("archive")));
    assertEquals("fixture-asset", Files.readString(directory.resolve("checksum")));
    assertEquals(3, requests.get());
  }

  @Test
  void rejectsDraftsPrereleasesMalformedMetadataAndMissingPlatformAssets() {
    for (String invalid : List.of("not-json", "{}", "null",
        metadata.replace("\"draft\":false", "\"draft\":true"),
        metadata.replace("\"prerelease\":false", "\"prerelease\":true"),
        metadata.replace("v0.1.10", "v0.1.10-rc.1"))) {
      String original = metadata;
      metadata = invalid;
      assertThrows(CliException.class, client::latest);
      metadata = original;
    }
    var release = client.latest();
    assertThrows(CliException.class, () -> release.archiveName("macos-arm64"));
  }

  @Test
  void reportsMissingReleasesRateLimitsAndServerFailures() {
    for (int failure : List.of(404, 403, 429, 500)) {
      status = failure;
      assertThrows(CliException.class, client::latest);
    }
  }

  @Test
  void refusesInsecureRedirectsAndRedirectLoops() {
    status = 302;
    redirect = "http://example.test/unsafe";
    CliException insecure = assertThrows(CliException.class, client::latest);
    assertTrue(insecure.getMessage().contains("insecure"));
    redirect = root.resolve("latest").toString();
    CliException loop = assertThrows(CliException.class, client::latest);
    assertTrue(loop.getMessage().contains("redirects"));
  }

  @Test
  void reportsMalformedAssetRedirectsAsDownloadFailures() {
    var release = client.latest();
    status = 302;
    redirect = "https://[invalid";
    assertThrows(IOException.class,
        () -> client.download(release, "linux-amd64", directory.resolve("archive"), directory.resolve("checksum")));
  }

  @Test
  void rejectsOversizedMetadata() {
    metadata = " ".repeat(1_048_577);
    assertTrue(assertThrows(CliException.class, client::latest).getMessage().contains("size limit"));
  }
}
