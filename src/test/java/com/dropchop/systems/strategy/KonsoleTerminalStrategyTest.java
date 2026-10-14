package com.dropchop.systems.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

/**
 * Verifies D-Bus session routing, transport fallback, previews, and failure handling.
 */
class KonsoleTerminalStrategyTest {
  private static final Map<String, String> ENVIRONMENT = Map.of(
      "KONSOLE_DBUS_SERVICE", ":1.42",
      "KONSOLE_DBUS_WINDOW", "/Windows/2");

  private static ClusterCtlConfig.Host host(String name, String alias) {
    ClusterCtlConfig.Host host = new ClusterCtlConfig.Host();
    host.name = name;
    host.sshUser = "test-admin";
    if (alias != null) {
      host.aliases.add(alias);
    }
    return host;
  }

  private static TerminalRequest request(boolean dryRun, ClusterCtlConfig.Host... hosts) {
    return new TerminalRequest("alpha", "nodes", List.of(hosts), dryRun);
  }

  /**
   * Records process requests and supplies responses without contacting the desktop bus.
   */
  private static class RecordingRunner extends ProcessRunner {
    private final List<ProcessRequest> requests = new ArrayList<>();
    private final ArrayDeque<ProcessResult> replies = new ArrayDeque<>();

    RecordingRunner(ProcessResult... replies) {
      this.replies.addAll(List.of(replies));
    }

    /** {@inheritDoc} */
    @Override
    public ProcessResult run(ProcessRequest request) {
      requests.add(request);
      return replies.isEmpty() ? new ProcessResult(0, "") : replies.removeFirst();
    }
  }

  @Test
  void createsEachBusctlTabUsingItsReturnedSessionId() {
    RecordingRunner runner = new RecordingRunner(
        new ProcessResult(0, "i 17\n"), new ProcessResult(0, ""),
        new ProcessResult(0, ""), new ProcessResult(0, ""),
        new ProcessResult(0, "i 29\n"));
    KonsoleTerminalStrategy strategy = new KonsoleTerminalStrategy(
        runner, ENVIRONMENT, tool -> true);

    strategy.open(request(false,
        host("alpha-node-1.example.test", "node-1"), host("alpha-node-2.example.test", null)));

    assertEquals(8, runner.requests.size());
    assertEquals(List.of("busctl", "--user", "call", ":1.42", "/Windows/2",
        "org.kde.konsole.Window", "newSession"), runner.requests.get(0).command());
    assertEquals(List.of("busctl", "--user", "call", ":1.42", "/Sessions/17",
        "org.kde.konsole.Session", "setTitle", "is", "1", "node-1"),
        runner.requests.get(1).command());
    assertEquals(List.of("busctl", "--user", "call", ":1.42", "/Sessions/17",
        "org.kde.konsole.Session", "sendText", "s", "ssh test-admin@alpha-node-1.example.test"),
        runner.requests.get(2).command());
    assertEquals("\n", runner.requests.get(3).command().getLast());
    assertEquals("/Sessions/29", runner.requests.get(5).command().get(4));
    assertEquals("alpha-node-2.example.test", runner.requests.get(5).command().getLast());
    assertTrue(runner.requests.stream().noneMatch(ProcessRequest::printOutput));
  }

  @Test
  void fallsBackToQdbus6WithUntypedResponsesAndArguments() {
    RecordingRunner runner = new RecordingRunner(new ProcessResult(0, "8\n"));
    KonsoleTerminalStrategy strategy = new KonsoleTerminalStrategy(
        runner, ENVIRONMENT, "qdbus6"::equals);

    strategy.open(request(false, host("node.example.test", "node-1")));

    assertEquals(List.of("qdbus6", ":1.42", "/Windows/2", "newSession"),
        runner.requests.get(0).command());
    assertEquals(List.of("qdbus6", ":1.42", "/Sessions/8", "setTitle", "1", "node-1"),
        runner.requests.get(1).command());
    assertEquals(List.of("qdbus6", ":1.42", "/Sessions/8", "sendText",
        "ssh test-admin@node.example.test"), runner.requests.get(2).command());
    assertEquals(List.of("qdbus6", ":1.42", "/Sessions/8", "sendText", "\n"),
        runner.requests.get(3).command());
  }

  @Test
  void previewsWithoutARealTerminalOrDbusTool() {
    RecordingRunner runner = new RecordingRunner();
    KonsoleTerminalStrategy strategy = new KonsoleTerminalStrategy(
        runner, Map.of(), tool -> false);

    strategy.open(request(true, host("node.example.test", "node-1")));

    assertEquals(4, runner.requests.size());
    assertTrue(runner.requests.stream().allMatch(ProcessRequest::dryRun));
    assertEquals("<KONSOLE_DBUS_SERVICE>", runner.requests.getFirst().command().get(3));
    assertEquals("<KONSOLE_DBUS_WINDOW>", runner.requests.getFirst().command().get(4));
    assertEquals("/Sessions/<new-session-1>", runner.requests.get(1).command().get(4));
  }

  @Test
  void rejectsMissingEnvironmentAndMissingToolsBeforeCreatingTabs() {
    RecordingRunner runner = new RecordingRunner();
    ClusterCtlConfig.Host host = host("node.example.test", "node-1");
    for (String missing : ENVIRONMENT.keySet()) {
      Map<String, String> environment = new java.util.HashMap<>(ENVIRONMENT);
      environment.remove(missing);
      CliException exception = assertThrows(CliException.class,
          () -> new KonsoleTerminalStrategy(runner, environment, tool -> true)
              .open(request(false, host)));
      assertTrue(exception.getMessage().contains(missing));
    }
    CliException exception = assertThrows(CliException.class,
        () -> new KonsoleTerminalStrategy(runner, ENVIRONMENT, tool -> false)
            .open(request(false, host)));
    assertTrue(exception.getMessage().contains("busctl or qdbus6"));
    assertTrue(runner.requests.isEmpty());
  }

  @Test
  void stopsOnInvalidSessionResponsesOrDbusFailures() {
    for (ProcessResult response : List.of(
        new ProcessResult(0, ""), new ProcessResult(0, "i 0\n"),
        new ProcessResult(0, "i invalid\n"), new ProcessResult(1, ""))) {
      RecordingRunner runner = new RecordingRunner(response);
      KonsoleTerminalStrategy strategy = new KonsoleTerminalStrategy(
          runner, ENVIRONMENT, tool -> true);

      assertThrows(CliException.class,
          () -> strategy.open(request(false, host("node.example.test", "node-1"))));
      assertEquals(1, runner.requests.size());
    }
    RecordingRunner runner = new RecordingRunner(
        new ProcessResult(0, "i 3\n"), new ProcessResult(1, ""));
    assertThrows(CliException.class,
        () -> new KonsoleTerminalStrategy(runner, ENVIRONMENT, tool -> true)
            .open(request(false, host("node.example.test", "node-1"))));
    assertEquals(2, runner.requests.size());
  }

  @Test
  void quotesShellSensitiveSshDestinationsBeforeSendingText() {
    RecordingRunner runner = new RecordingRunner();
    KonsoleTerminalStrategy strategy = new KonsoleTerminalStrategy(
        runner, ENVIRONMENT, tool -> true);
    strategy.open(request(true, host("host'; touch unexpected; 'name", "node-1")));

    String sshCommand = runner.requests.get(2).command().getLast();
    assertEquals("ssh 'test-admin@host'\"'\"'; touch unexpected; '\"'\"'name'", sshCommand);
    assertFalse(runner.requests.get(2).command().contains("touch"));
  }
}
