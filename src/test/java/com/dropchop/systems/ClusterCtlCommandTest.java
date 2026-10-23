package com.dropchop.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.service.TerminalDetector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/**
 * End-to-end dispatch tests that preview every external command family.
 */
@QuarkusMainTest
@TestProfile(ClusterCtlCommandTest.TerminalProfile.class)
public class ClusterCtlCommandTest {
  private static final String MULTI_CLUSTER = Path.of("src/test/resources/multi-cluster-test.yaml")
      .toAbsolutePath().toString();
  private static final String SINGLE_CLUSTER = Path.of("src/test/resources/single-cluster-test.yaml")
      .toAbsolutePath().toString();
  private static final String KEY = Path.of("src/test/resources/synthetic-key.pub")
      .toAbsolutePath().toString();

  /**
   * Makes dispatch previews reproducible in headless test environments.
   */
  public static class TerminalProfile implements QuarkusTestProfile {
    /** {@inheritDoc} */
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(TestTerminalDetector.class);
    }
  }

  /**
   * Selects Konsole for dispatch tests; detector tests cover real environment selection.
   */
  @Alternative
  @ApplicationScoped
  public static class TestTerminalDetector extends TerminalDetector {
    /** {@inheritDoc} */
    @Override
    public String resolve() {
      return "konsole";
    }
  }

  @Test
  void showsRootUsageWithoutLoadingConfiguration(QuarkusMainLauncher launcher) {
    assertSuccess(launcher.launch());
  }

  @Test
  void helpFlagsDoNotRequireArgumentsOrExecuteOperations(QuarkusMainLauncher launcher) {
    for (String flag : List.of("-h", "--help")) {
      assertSuccess(launcher.launch(flag));
      for (String command : List.of("docker", "term", "exec", "ssh-key-list", "ssh-key-add",
          "ssh-key-remove", "build", "deploy", "self-upgrade")) {
        LaunchResult result = launcher.launch("--dry-run", command, flag);

        assertSuccess(result);
        assertTrue(result.getOutput().contains("Examples"), combined(result));
        assertFalse(result.getOutput().contains("$ "), combined(result));
      }
    }
  }

  @Test
  void helpBypassesOperationsEvenWhenTheirArgumentsAreComplete(QuarkusMainLauncher launcher) {
    for (List<String> command : List.of(
        List.of("term", "beta", "nodes", "--help"),
        List.of("exec", "beta", "nodes", "hostname", "-h"),
        List.of("docker", "pull", "db", "--help"),
        List.of("build", "beta", "qa", "all", "--help"),
        List.of("deploy", "beta", "qa", "batch-job", "--help"))) {
      List<String> arguments = new ArrayList<>(List.of("--config", MULTI_CLUSTER, "--dry-run"));
      arguments.addAll(command);
      LaunchResult result = launcher.launch(arguments.toArray(String[]::new));

      assertSuccess(result);
      assertTrue(result.getOutput().contains("Examples"), combined(result));
      assertFalse(result.getOutput().contains("$ "), combined(result));
    }
  }

  @Test
  void selfUpgradeIgnoresClusterConfigAndRefusesJvmReplacement(QuarkusMainLauncher launcher) {
    LaunchResult help = launcher.launch("--config", "/missing-config.yaml", "self-upgrade", "--help");
    assertSuccess(help);
    assertTrue(help.getOutput().contains("self-upgrade"), combined(help));
    LaunchResult execution = launcher.launch("--config", "/missing-config.yaml", "self-upgrade");
    assertEquals(2, execution.exitCode(), combined(execution));
    assertTrue(execution.getErrorOutput().contains("requires an installed native executable"), combined(execution));
    LaunchResult invalid = launcher.launch("self-upgrade", "extra");
    assertEquals(2, invalid.exitCode(), combined(invalid));
    assertTrue(invalid.getOutput().contains("Usage: cluster-ctl self-upgrade"), combined(invalid));
  }

  @Test
  void helpIncludesOnlyRelevantInventoriesForTheSelectedCluster(QuarkusMainLauncher launcher) {
    for (String command : List.of("term", "exec", "ssh-key-list", "ssh-key-add", "ssh-key-remove",
        "build", "deploy")) {
      LaunchResult result = launcher.launch("--config", MULTI_CLUSTER, "--dry-run", command, "beta", "--help");

      assertSuccess(result);
      String output = result.getOutput();
      assertEquals(!command.equals("deploy"), output.contains("Cluster beta (ssh)"), combined(result));
      assertFalse(output.contains("Cluster alpha"), combined(result));
      assertEquals(!List.of("build", "deploy").contains(command), output.contains("Group (aliases)"),
          combined(result));
      assertEquals(command.equals("deploy"), output.contains("Application group"), combined(result));
      if (command.equals("deploy")) {
        assertTrue(output.contains("batch-job"), combined(result));
      }
      assertEquals(command.equals("deploy") || command.equals("build"), output.contains("Environments"),
          combined(result));
      if (command.equals("deploy") || command.equals("build")) {
        assertTrue(output.contains("validation"), combined(result));
        assertFalse(output.contains("release"), combined(result));
      }
      assertFalse(output.contains("$ "), combined(result));
    }
  }

  @Test
  void helpUsesOnlyTheConfiguredDefaultCluster(QuarkusMainLauncher launcher) {
    for (String fixture : List.of(MULTI_CLUSTER,
        Path.of("src/test/resources/default-secondary-cluster.yaml").toAbsolutePath().toString())) {
      LaunchResult result = launcher.launch("--config", fixture, "--dry-run", "term", "-h");
      assertSuccess(result);
      String expected = fixture.equals(MULTI_CLUSTER) ? "alpha" : "beta";
      String other = expected.equals("alpha") ? "beta" : "alpha";
      assertTrue(result.getOutput().contains("Cluster " + expected + " (ssh) [default]"), combined(result));
      assertFalse(result.getOutput().contains("Cluster " + other), combined(result));
    }
  }

  @Test
  void rejectsRemovedHelpCommandAndReportsInvalidHelpConfiguration(QuarkusMainLauncher launcher) {
    assertEquals(2, launcher.launch("help").exitCode());
    LaunchResult result = launcher.launch("--config", "src/test/resources/missing-config.yaml", "deploy", "--help");
    assertEquals(2, result.exitCode(), combined(result));
    assertTrue(result.getOutput().contains("Examples"), combined(result));
  }

  @Test
  void dispatchesTerminalAndExecCommands(QuarkusMainLauncher launcher) {
    LaunchResult terminal = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "term", "beta", "node");
    LaunchResult exec = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "exec", "beta", "nodes", "uptime -p");
    LaunchResult hostAlias = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "exec", "alpha", "node-1", "hostname");

    assertSuccess(terminal);
    assertTrue(terminal.getOutput().contains("newSession"), terminal.getOutput());
    assertTrue(terminal.getOutput().contains("setTitle"), terminal.getOutput());
    assertTrue(terminal.getOutput().contains("sendText"), terminal.getOutput());
    assertFalse(terminal.getOutput().contains("konsole --new-tab"), terminal.getOutput());
    assertTrue(terminal.getOutput().contains("test-admin@beta-node-3.example.test"),
        terminal.getOutput());
    assertSuccess(exec);
    assertTrue(exec.getOutput().matches("(?s).*\\[\\d{4}-\\d{2}-\\d{2} "
        + "\\d{2}:\\d{2}:\\d{2} (Z|[+-]\\d{2}:\\d{2})].*"), exec.getOutput());
    assertTrue(exec.getOutput().contains("Executing uptime -p"), exec.getOutput());
    assertTrue(exec.getOutput().contains("on test-admin@beta-node-1.example.test"),
        exec.getOutput());
    assertTrue(exec.getOutput().contains(" output "), exec.getOutput());
    assertTrue(exec.getOutput().contains("ssh test-admin@beta-node-1.example.test"),
        exec.getOutput());
    assertTrue(exec.getOutput().contains("'uptime -p'"), exec.getOutput());
    assertSuccess(hostAlias);
    assertTrue(hostAlias.getOutput().contains("test-admin@alpha-node-1.example.test"),
        hostAlias.getOutput());
    assertFalse(hostAlias.getOutput().contains("test-admin@alpha-node-2.example.test"),
        hostAlias.getOutput());
  }

  @Test
  void overridesSshLoginForTerminalGroupsAndHosts(QuarkusMainLauncher launcher) {
    LaunchResult group = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "term", "test-user@api");
    LaunchResult host = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "term", "beta", "test-user@beta-node-1");
    LaunchResult configured = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "term", "api");

    assertSuccess(group);
    assertTrue(group.getOutput().contains("ssh test-user@alpha-api-1.example.test"),
        combined(group));
    assertTrue(group.getOutput().contains("ssh test-user@alpha-api-2.example.test"),
        combined(group));
    assertFalse(group.getOutput().contains("ssh test-admin@"), combined(group));
    assertSuccess(host);
    assertTrue(host.getOutput().contains("ssh test-user@beta-node-1.example.test"),
        combined(host));
    assertFalse(host.getOutput().contains("beta-node-2"), combined(host));
    assertSuccess(configured);
    assertTrue(configured.getOutput().contains("ssh test-admin@alpha-api-1.example.test"),
        combined(configured));
  }

  @Test
  void rejectsInvalidTerminalUserPrefixesBeforeDispatch(QuarkusMainLauncher launcher) {
    for (String target : List.of("@api", "test-user@", "test-user@@api", "-bad@api",
        "bad user@api", "bad;user@api")) {
      LaunchResult result = launcher.launch(
          "--config", MULTI_CLUSTER, "--dry-run", "term", "--", target);

      assertEquals(2, result.exitCode(), combined(result));
      assertFalse(result.getOutput().contains("newSession"), combined(result));
      assertTrue(result.getErrorOutput().contains("Invalid"), combined(result));
    }
  }

  @Test
  void dispatchesAllSshKeyCommands(QuarkusMainLauncher launcher) {
    LaunchResult list = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "ssh-key-list", "alpha", "nodes", "test-user");
    LaunchResult add = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "ssh-key-add", "alpha", "nodes", KEY,
        "test-user", "verify-required");
    LaunchResult remove = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "ssh-key-remove", "alpha", "nodes",
        "fixture-key", "test-user");

    assertSuccess(list);
    assertTrue(list.getOutput().contains("Authorized keys for test-user"), list.getOutput());
    assertSuccess(add);
    assertTrue(add.getOutput().contains("Adding authorized key for test-user"), add.getOutput());
    assertSuccess(remove);
    assertTrue(remove.getOutput().contains("grep -nF"), remove.getOutput());
  }

  @Test
  void dispatchesDockerBuildAndDeployStrategies(QuarkusMainLauncher launcher) {
    LaunchResult docker = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "docker", "pull", "db");
    LaunchResult build = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "build", "beta", "qa", "all-apps");
    LaunchResult deploy = launcher.launch(
        "--config", MULTI_CLUSTER, "--dry-run", "deploy", "beta", "qa", "batch-job");

    assertSuccess(docker);
    assertTrue(docker.getOutput().contains("docker pull"), docker.getOutput());
    assertTrue(docker.getOutput().contains("demo/database:1.0"), docker.getOutput());
    assertSuccess(build);
    assertTrue(build.getOutput().contains("-Dexample.feature.enabled=true"), build.getOutput());
    assertTrue(build.getOutput().contains("beta-validation"), build.getOutput());
    assertSuccess(deploy);
    assertTrue(deploy.getOutput().contains("sha1sum"), deploy.getOutput());
    assertTrue(deploy.getOutput().contains("beta-publisher.example.test"),
        deploy.getOutput());
  }

  @Test
  void supportsLegacyComposeAndMinimalBuildConfiguration(QuarkusMainLauncher launcher) {
    LaunchResult docker = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "docker", "start");
    LaunchResult build = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "build", "alpha", "qa", "library");
    LaunchResult deploy = launcher.launch(
        "--config", SINGLE_CLUSTER, "--dry-run", "deploy", "alpha", "qa", "anything");

    assertSuccess(docker);
    assertTrue(docker.getOutput().contains("docker-compose -f"), docker.getOutput());
    assertTrue(docker.getOutput().contains(Path.of("src/test/resources/compose/single/docker-compose.yaml")
        .toAbsolutePath().toString()),
        docker.getOutput());
    assertSuccess(build);
    assertTrue(build.getOutput().contains("mvn -DskipTests=true clean install"),
        build.getOutput());
    assertFalse(build.getOutput().contains("example.feature.enabled"), build.getOutput());
    assertEquals(2, deploy.exitCode(), combined(deploy));
    assertTrue(deploy.getErrorOutput().contains("Unknown application"), combined(deploy));
  }

  @Test
  void dispatchesClusterCommandsWithAnOmittedCluster(QuarkusMainLauncher launcher) {
    List<List<String>> commands = List.of(
        List.of("term", "node-1"),
        List.of("exec", "node-1", "printf 'hello world'", "&&", "hostname"),
        List.of("ssh-key-list", "node-1"),
        List.of("ssh-key-list", "node-1", "test-user"),
        List.of("ssh-key-add", "node-1", KEY),
        List.of("ssh-key-add", "node-1", KEY, "verify-required"),
        List.of("ssh-key-add", "node-1", KEY, "test-user", "verify-required"),
        List.of("ssh-key-remove", "node-1", "old-key-comment"),
        List.of("ssh-key-remove", "node-1", "old-key-comment", "test-user"),
        List.of("build", "live"),
        List.of("build", "live", "service"),
        List.of("deploy", "live", "service-api"));
    for (List<String> command : commands) {
      List<String> implicitArguments = new ArrayList<>(List.of("--config", MULTI_CLUSTER, "--dry-run"));
      implicitArguments.addAll(command);
      List<String> explicitArguments = new ArrayList<>(implicitArguments);
      explicitArguments.add(4, "alpha");
      LaunchResult implicit = launcher.launch(implicitArguments.toArray(String[]::new));
      LaunchResult explicit = launcher.launch(explicitArguments.toArray(String[]::new));

      assertSuccess(implicit);
      assertSuccess(explicit);
      // Timestamps may cross a second boundary between the two launches.
      assertEquals(explicit.getOutput().replaceAll("\\[\\d{4}-\\d{2}-\\d{2} [^]]+]", "[time]"),
          implicit.getOutput().replaceAll("\\[\\d{4}-\\d{2}-\\d{2} [^]]+]", "[time]"),
          String.join(" ", command));
    }
  }

  @Test
  void usesConfiguredDefaultAndAllowsAnExplicitOverride(QuarkusMainLauncher launcher) {
    String config = Path.of("src/test/resources/default-secondary-cluster.yaml").toAbsolutePath().toString();
    LaunchResult implicit = launcher.launch(
        "--config", config, "--dry-run", "exec", "node-1", "hostname");
    LaunchResult explicit = launcher.launch(
        "--config", config, "--dry-run", "exec", "alpha", "node-1", "hostname");

    assertSuccess(implicit);
    assertTrue(implicit.getOutput().contains("test-admin@beta-node-1.example.test"), combined(implicit));
    assertFalse(implicit.getOutput().contains("test-admin@alpha-node-1.example.test"), combined(implicit));
    assertSuccess(explicit);
    assertTrue(explicit.getOutput().contains("test-admin@alpha-node-1.example.test"), combined(explicit));
    assertFalse(explicit.getOutput().contains("test-admin@beta-node-1.example.test"), combined(explicit));
  }

  @Test
  void rejectsIncompleteAndExtraArgumentsBeforeDispatch(QuarkusMainLauncher launcher) {
    List<List<String>> commands = List.of(
        List.of("term", "alpha"),
        List.of("exec", "alpha", "node-1"),
        List.of("ssh-key-list", "alpha"),
        List.of("ssh-key-add", "alpha", "node-1"),
        List.of("ssh-key-remove", "alpha", "node-1"),
        List.of("build", "alpha"),
        List.of("deploy", "alpha", "live"),
        List.of("term", "node-1", "extra"),
        List.of("ssh-key-list", "node-1", "test-admin", "extra"),
        List.of("build", "live", "service", "extra"),
        List.of("deploy", "live", "service-api", "extra"));
    for (List<String> command : commands) {
      List<String> arguments = new ArrayList<>(List.of("--config", MULTI_CLUSTER, "--dry-run"));
      arguments.addAll(command);
      LaunchResult result = launcher.launch(arguments.toArray(String[]::new));

      assertEquals(2, result.exitCode(), combined(result));
      assertTrue(result.getOutput().contains("Usage: cluster-ctl " + command.getFirst()),
          combined(result));
      assertTrue(result.getOutput().contains("Examples"), combined(result));
      assertFalse(result.getOutput().contains("$ "), combined(result));
    }
  }

  @Test
  void parseAndSelectorErrorsShowCommandHelpWithoutExecutingOperations(QuarkusMainLauncher launcher) {
    for (List<String> command : List.of(
        List.of("docker"),
        List.of("docker", "unknown-action"),
        List.of("docker", "pull", "unknown-image"),
        List.of("docker", "log"),
        List.of("term", "--unknown-option"),
        List.of("term", "alpha", "node-1", "extra"),
        List.of("term", "missing-host"),
        List.of("exec", "missing-host", "hostname"),
        List.of("ssh-key-list", "node-1", "bad user"),
        List.of("ssh-key-add", "node-1", KEY, "verify-required", "verify-required"),
        List.of("ssh-key-remove", "node-1", ""),
        List.of("build", "missing-environment"),
        List.of("build", "live", "missing-project"),
        List.of("deploy", "live", "missing-app"))) {
      List<String> arguments = new ArrayList<>(List.of("--config", MULTI_CLUSTER, "--dry-run"));
      arguments.addAll(command);
      LaunchResult result = launcher.launch(arguments.toArray(String[]::new));

      assertEquals(2, result.exitCode(), combined(result));
      assertTrue(result.getErrorOutput().contains("Error:"), combined(result));
      assertTrue(result.getOutput().contains("Usage: cluster-ctl " + command.getFirst()), combined(result));
      assertTrue(result.getOutput().contains("Examples"), combined(result));
      assertFalse(combined(result).contains("CliUsageException"), combined(result));
      assertFalse(result.getOutput().contains("$ "), combined(result));
    }
  }

  private static void assertSuccess(LaunchResult result) {
    assertEquals(0, result.exitCode(), combined(result));
  }

  private static String combined(LaunchResult result) {
    return result.getOutput() + System.lineSeparator() + result.getErrorOutput();
  }
}
