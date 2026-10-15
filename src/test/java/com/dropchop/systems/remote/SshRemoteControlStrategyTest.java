package com.dropchop.systems.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

/**
 * Verifies SSH commands select orange stderr without changing stdin, stdout, or errors.
 */
class SshRemoteControlStrategyTest {
  private static ClusterCtlConfig.Host host() {
    ClusterCtlConfig.Host host = new ClusterCtlConfig.Host();
    host.name = "node.example.test";
    host.sshUser = "test-admin";
    return host;
  }

  /**
   * Records colored-stderr requests without contacting remote hosts.
   */
  private static class RecordingRunner extends ProcessRunner {
    private final List<ProcessRequest> requests = new ArrayList<>();
    private ProcessResult result = new ProcessResult(0, "");

    /** {@inheritDoc} */
    @Override
    public ProcessResult run(ProcessRequest request) {
      throw new AssertionError("SSH must use orange stderr.");
    }

    /** {@inheritDoc} */
    @Override
    public ProcessResult runWithOrangeStderr(ProcessRequest request) {
      requests.add(request);
      return result;
    }
  }

  @Test
  void executesSshWithOrangeStderrAndPreservesFailureStatus() {
    RecordingRunner runner = new RecordingRunner();
    SshRemoteControlStrategy strategy = new SshRemoteControlStrategy(runner);

    strategy.execute(host(), "hostname", false);

    ProcessRequest request = runner.requests.getFirst();
    assertEquals(List.of("ssh", "test-admin@node.example.test", "-T", "hostname"), request.command());
    assertNull(request.input());
    assertTrue(request.printOutput());
    assertFalse(request.dryRun());

    runner.result = new ProcessResult(255, "");
    CliException exception = assertThrows(CliException.class,
        () -> strategy.execute(host(), "hostname", false));
    assertTrue(exception.getMessage().contains("exit code 255"), exception.getMessage());
  }

  @Test
  void keyOperationsUseOrangeStderrAndPreserveCapturedMatches() {
    RecordingRunner runner = new RecordingRunner();
    SshRemoteControlStrategy strategy = new SshRemoteControlStrategy(runner);
    runner.result = new ProcessResult(0, "3:matching-key\n");

    String matches = strategy.findKeys(host(), "test-user", "matching-key", false);

    assertEquals("3:matching-key\n", matches);
    assertEquals("test-user\nmatching-key", runner.requests.getFirst().input());
    assertFalse(runner.requests.getFirst().printOutput());

    strategy.listKeys(host(), "test-user", true);
    strategy.addKey(host(), "test-user", "fixture-key", false, true);
    strategy.removeKeys(host(), "test-user", "matching-key", true);
    assertEquals(4, runner.requests.size());
    assertTrue(runner.requests.subList(1, 4).stream().allMatch(ProcessRequest::dryRun));
  }
}
