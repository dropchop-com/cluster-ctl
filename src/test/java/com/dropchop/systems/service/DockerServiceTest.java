package com.dropchop.systems.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.cli.CliUsageException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.config.ConfigLoader;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

/**
 * Verifies Docker pulls retain live terminal output, selection, previews, and failures.
 */
class DockerServiceTest {
  private static DockerService service(ProcessRunner runner) {
    return new DockerService(new ClusterCatalog(new ConfigLoader()), runner);
  }

  private static ClusterCtlConfig config() {
    ClusterCtlConfig config = new ClusterCtlConfig();
    for (String name : List.of("database", "queue")) {
      ClusterCtlConfig.Image image = new ClusterCtlConfig.Image();
      image.url = "registry.example.test/demo/" + name + ":1.0";
      image.aliases.add(name);
      config.local.docker.images.add(image);
    }
    return config;
  }

  /**
   * Records pull requests without invoking Docker and rejects captured-output pulls.
   */
  private static class RecordingRunner extends ProcessRunner {
    private final List<ProcessRequest> requests = new ArrayList<>();
    private int exitCode;

    /** {@inheritDoc} */
    @Override
    public ProcessResult run(ProcessRequest request) {
      throw new AssertionError("Docker pulls must use attached terminal output.");
    }

    /** {@inheritDoc} */
    @Override
    public ProcessResult runAttached(ProcessRequest request) {
      requests.add(request);
      return new ProcessResult(exitCode, "");
    }
  }

  @Test
  void pullsSelectedImageWithAttachedOutputAndInheritedInput() {
    RecordingRunner runner = new RecordingRunner();

    service(runner).execute(config(), "pull", "database", false);

    assertEquals(1, runner.requests.size());
    ProcessRequest request = runner.requests.getFirst();
    assertEquals(List.of("docker", "pull", "registry.example.test/demo/database:1.0"),
        request.command());
    assertNull(request.input());
    assertFalse(request.dryRun());
  }

  @Test
  void previewsEveryImageThroughAttachedOutputForGetAlias() {
    RecordingRunner runner = new RecordingRunner();
    ClusterCtlConfig config = config();

    service(runner).execute(config, "get", null, true);

    assertEquals(2, runner.requests.size());
    for (int index = 0; index < runner.requests.size(); index++) {
      ProcessRequest request = runner.requests.get(index);
      assertEquals(List.of("docker", "pull", config.local.docker.images.get(index).url),
          request.command());
      assertTrue(request.dryRun());
    }
  }

  @Test
  void rejectsInvalidImageSelectorsBeforeLoggingIntoTheRegistry() {
    RecordingRunner runner = new RecordingRunner();
    ClusterCtlConfig config = config();
    config.local.docker.repoToken = "fixture-token";

    assertThrows(CliUsageException.class,
        () -> service(runner).execute(config, "pull", "unknown-image", false));

    assertTrue(runner.requests.isEmpty());
  }

  @Test
  void stopsPullingAfterAnAttachedProcessFails() {
    RecordingRunner runner = new RecordingRunner();
    runner.exitCode = 7;

    CliException exception = assertThrows(CliException.class,
        () -> service(runner).execute(config(), "pull", null, false));

    assertEquals("Docker pull failed with exit code 7.", exception.getMessage());
    assertFalse(exception instanceof CliUsageException);
    assertEquals(1, runner.requests.size());
  }
}
