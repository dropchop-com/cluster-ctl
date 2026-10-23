package com.dropchop.systems.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

/**
 * Ensures deployments retain SCP's live terminal progress without making real transfers.
 */
class ScpDeploymentStrategyTest {
  @TempDir
  Path directory;

  /**
   * Records terminal-attached transfers and rejects the captured-output regression.
   */
  private static class RecordingRunner extends ProcessRunner {
    private final List<ProcessRequest> requests = new ArrayList<>();
    private int exitCode;

    /** {@inheritDoc} */
    @Override
    public ProcessResult run(ProcessRequest request) {
      throw new AssertionError("SCP must inherit the terminal to display progress.");
    }

    /** {@inheritDoc} */
    @Override
    public ProcessResult runAttached(ProcessRequest request) {
      requests.add(request);
      return new ProcessResult(exitCode, "");
    }
  }

  private ClusterCtlConfig.Environment environment() {
    ClusterCtlConfig.Environment environment = new ClusterCtlConfig.Environment();
    environment.deploy.host = "publisher.example.test";
    environment.deploy.user = "operator";
    environment.deploy.path = "/srv/example-deploy";
    return environment;
  }

  @Test
  void attachesEachTransferToTheTerminalAndPreservesArgumentBoundaries() throws Exception {
    Path artifact = directory.resolve("service app.jar");
    Files.writeString(artifact, "fixture");
    RecordingRunner runner = new RecordingRunner();

    new ScpDeploymentStrategy(runner).deploy(new DeploymentRequest(environment(), List.of(artifact), false));

    assertEquals(1, runner.requests.size());
    ProcessRequest request = runner.requests.getFirst();
    assertEquals(List.of("scp", artifact.toString(), "operator@publisher.example.test:/srv/example-deploy"),
        request.command());
    assertNull(request.input());
    assertFalse(request.dryRun());
  }

  @Test
  void attachesChecksumAndArtifactTransfersInTheOriginalOrder() throws Exception {
    Path artifact = directory.resolve("service.jar");
    Files.writeString(artifact, "abc");
    ClusterCtlConfig.Environment environment = environment();
    environment.deploy.checksum = "sha1";
    RecordingRunner runner = new RecordingRunner();

    new ScpDeploymentStrategy(runner).deploy(new DeploymentRequest(environment, List.of(artifact), false));

    Path checksum = directory.resolve("service.jar.sha1");
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d  service.jar" + System.lineSeparator(),
        Files.readString(checksum));
    assertEquals(List.of(checksum.toString(), artifact.toString()),
        runner.requests.stream().map(request -> request.command().get(1)).toList());
  }

  @Test
  void previewsAttachedTransfersWithoutCreatingChecksums() {
    Path artifact = directory.resolve("not-built-yet.jar");
    ClusterCtlConfig.Environment environment = environment();
    environment.deploy.checksum = "sha1";
    environment.deploy.user = null;
    RecordingRunner runner = new RecordingRunner();

    new ScpDeploymentStrategy(runner).deploy(new DeploymentRequest(environment, List.of(artifact), true));

    assertEquals(2, runner.requests.size());
    assertTrue(runner.requests.stream().allMatch(ProcessRequest::dryRun));
    assertEquals("publisher.example.test:/srv/example-deploy", runner.requests.getFirst().command().get(2));
    assertFalse(Files.exists(directory.resolve("not-built-yet.jar.sha1")));
  }

  @Test
  void stopsDeploymentOnTheFirstFailedTransfer() {
    RecordingRunner runner = new RecordingRunner();
    runner.exitCode = 7;
    Path artifact = directory.resolve("service.jar");

    CliException failure = assertThrows(CliException.class,
        () -> new ScpDeploymentStrategy(runner).deploy(new DeploymentRequest(
            environment(), List.of(artifact, directory.resolve("another.jar")), false)));

    assertEquals(1, runner.requests.size());
    assertTrue(failure.getMessage().contains("exit code 7"));
  }
}
