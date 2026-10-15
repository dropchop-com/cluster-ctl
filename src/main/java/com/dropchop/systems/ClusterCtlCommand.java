package com.dropchop.systems;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.cli.CliUsageException;
import com.dropchop.systems.cli.ClusterCtlVersion;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.config.ConfigException;
import com.dropchop.systems.config.ConfigLoader;
import com.dropchop.systems.service.BuildService;
import com.dropchop.systems.service.DeploymentService;
import com.dropchop.systems.service.DockerService;
import com.dropchop.systems.service.HostService;
import com.dropchop.systems.service.TerminalService;

import io.quarkus.arc.Unremovable;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Root command for the native cluster control utility.
 */
@TopCommand
@Command(
    name = "cluster-ctl",
    description = "Build, deploy, and operate configured clusters.",
    mixinStandardHelpOptions = true,
    versionProvider = ClusterCtlVersion.class,
    subcommands = {
        ClusterCtlCommand.DockerCommand.class,
        ClusterCtlCommand.TermCommand.class,
        ClusterCtlCommand.ExecCommand.class,
        ClusterCtlCommand.SshKeyListCommand.class,
        ClusterCtlCommand.SshKeyAddCommand.class,
        ClusterCtlCommand.SshKeyRemoveCommand.class,
        ClusterCtlCommand.BuildCommand.class,
        ClusterCtlCommand.DeployCommand.class
    })
public class ClusterCtlCommand implements Callable<Integer> {
  private final ConfigLoader configLoader;
  private final ClusterCatalog catalog;

  @Spec
  CommandSpec spec;

  @Option(
      names = {"-c", "--config"},
      description = "YAML config path (otherwise CLUSTER_CTL_CONF_PATH).",
      scope = CommandLine.ScopeType.INHERIT)
  String configPath;

  @Option(
      names = "--dry-run",
      description = "Print external commands without executing them.",
      scope = CommandLine.ScopeType.INHERIT)
  boolean dryRun;

  @Option(
      names = {"-y", "--yes"},
      description = "Assume yes for deployment and key removal prompts.",
      scope = CommandLine.ScopeType.INHERIT)
  boolean assumedYes;

  /**
   * Creates the root command.
   *
   * @param configLoader external configuration loader
   * @param catalog configuration catalog
   */
  public ClusterCtlCommand(ConfigLoader configLoader, ClusterCatalog catalog) {
    this.configLoader = configLoader;
    this.catalog = catalog;
  }

  /**
   * Prints root usage when no subcommand is supplied.
   *
   * @return successful exit code
   */
  @Override
  public Integer call() {
    spec.commandLine().usage(System.out);
    return 0;
  }

  /**
   * Holds a resolved cluster and the remaining command arguments.
   */
  record ClusterArguments(String cluster, List<String> values) {
    String valueOrDefault(int index, String fallback) {
      return index < values.size() ? values.get(index) : fallback;
    }
  }

  ClusterArguments resolveArguments(
      ClusterCtlConfig config,
      String command,
      List<String> arguments,
      int minimum,
      int maximum,
      String syntax) {
    // Collectors accept partial input for --help; execution enforces required values here.
    boolean explicitCluster = !arguments.isEmpty() && config.remote.clusters.stream()
        .anyMatch(cluster -> cluster.name.equals(arguments.getFirst()));
    String cluster = explicitCluster ? arguments.getFirst() : config.remote.defaultCluster;
    List<String> values = explicitCluster
        ? arguments.subList(1, arguments.size())
        : arguments;
    if (values.size() < minimum || values.size() > maximum) {
      String reason = values.size() < minimum ? "Missing required arguments" : "Too many arguments";
      throw new CliUsageException(reason + " for " + command + ". Expected: " + syntax);
    }
    catalog.cluster(config, cluster);
    return new ClusterArguments(cluster, values);
  }

  int invoke(String command, ConfigAction action) {
    try {
      ClusterCtlConfig config = configLoader.load(configPath);
      catalog.requireCommand(config, command);
      action.run(config);
      return 0;
    } catch (CliUsageException exception) {
      throw exception;
    } catch (ConfigException | CliException exception) {
      System.err.println("Error: " + exception.getMessage());
      return 2;
    }
  }

  Path path(String value) {
    return configLoader.expandHome(value).toAbsolutePath().normalize();
  }

  @FunctionalInterface
  interface ConfigAction {
    void run(ClusterCtlConfig config);
  }

  /**
   * Runs local Docker development environment actions.
   */
  @Dependent
  @Unremovable
  @Command(name = "docker", description = "Pull, start, stop, or tail local containers.")
  public static class DockerCommand implements Callable<Integer> {
    private final DockerService dockerService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0", paramLabel = "ACTION")
    String action;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[IMAGE_OR_CONTAINER]", hideParamSyntax = true)
    String selector;

    /**
     * Creates the Docker command.
     *
     * @param dockerService Docker operation service
     */
    public DockerCommand(DockerService dockerService) {
      this.dockerService = dockerService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("docker", config -> dockerService.execute(
          config, action, selector, parent.dryRun));
    }
  }

  /**
   * Opens terminal tabs for a configured host group.
   */
  @Dependent
  @Unremovable
  @Command(
      name = "term",
      description = "Open one SSH terminal tab for every host in a group.")
  public static class TermCommand implements Callable<Integer> {
    private final TerminalService terminalService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..2", hideParamSyntax = true,
        paramLabel = "[CLUSTER] [USER@]HOST_GROUP_OR_HOST")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the terminal command.
     *
     * @param terminalService terminal operation service
     */
    public TermCommand(TerminalService terminalService) {
      this.terminalService = terminalService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("term", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "term", arguments, 1, 1, "[CLUSTER] [USER@]HOST_GROUP_OR_HOST");
        terminalService.open(
            config, resolved.cluster(), resolved.values().getFirst(), parent.dryRun);
      });
    }
  }

  /**
   * Executes a remote command on a host group.
   */
  @Dependent
  @Unremovable
  @Command(name = "exec", description = "Execute a command on every host in a group.")
  public static class ExecCommand implements Callable<Integer> {
    private final HostService hostService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..*", hideParamSyntax = true,
        paramLabel = "[CLUSTER] HOST_GROUP COMMAND...")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the remote exec command.
     *
     * @param hostService host operation service
     */
    public ExecCommand(HostService hostService) {
      this.hostService = hostService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("exec", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "exec", arguments, 2, Integer.MAX_VALUE,
            "[CLUSTER] HOST_GROUP COMMAND...");
        hostService.execute(
            config, resolved.cluster(), resolved.values().getFirst(),
            String.join(" ", resolved.values().subList(1, resolved.values().size())),
            parent.dryRun);
      });
    }
  }

  /**
   * Lists authorized keys for a user across a host group.
   */
  @Dependent
  @Unremovable
  @Command(name = "ssh-key-list", description = "List authorized keys on group hosts.")
  public static class SshKeyListCommand implements Callable<Integer> {
    private final HostService hostService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..3", hideParamSyntax = true,
        paramLabel = "[CLUSTER] HOST_GROUP [USER]")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the authorized-key list command.
     *
     * @param hostService host operation service
     */
    public SshKeyListCommand(HostService hostService) {
      this.hostService = hostService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("ssh-key-list", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "ssh-key-list", arguments, 1, 2, "[CLUSTER] HOST_GROUP [USER]");
        hostService.listKeys(
            config, resolved.cluster(), resolved.values().getFirst(),
            resolved.valueOrDefault(1, "root"), parent.dryRun);
      });
    }
  }

  /**
   * Adds an authorized key for a user across a host group.
   */
  @Dependent
  @Unremovable
  @Command(name = "ssh-key-add", description = "Add an authorized key on group hosts.")
  public static class SshKeyAddCommand implements Callable<Integer> {
    private final HostService hostService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..5", hideParamSyntax = true,
        paramLabel = "[CLUSTER] HOST_GROUP PUBLIC_KEY_PATH [USER] [verify-required]")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the authorized-key add command.
     *
     * @param hostService host operation service
     */
    public SshKeyAddCommand(HostService hostService) {
      this.hostService = hostService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("ssh-key-add", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "ssh-key-add", arguments, 2, 4,
            "[CLUSTER] HOST_GROUP PUBLIC_KEY_PATH [USER] [verify-required]");
        boolean verifyRequired = false;
        String user = "root";
        boolean userSet = false;
        for (String option : resolved.values().subList(2, resolved.values().size())) {
          if (option.equals("verify-required")) {
            if (verifyRequired) {
              throw new CliUsageException("verify-required was specified more than once.");
            }
            verifyRequired = true;
          } else if (!userSet) {
            user = option;
            userSet = true;
          } else {
            throw new CliUsageException("ssh-key-add accepts one user and verify-required.");
          }
        }
        hostService.addKey(
            config,
            resolved.cluster(),
            resolved.values().getFirst(),
            parent.path(resolved.values().get(1)),
            user,
            verifyRequired,
            parent.dryRun);
      });
    }
  }

  /**
   * Removes matching authorized keys across a host group.
   */
  @Dependent
  @Unremovable
  @Command(name = "ssh-key-remove", description = "Remove matching authorized keys.")
  public static class SshKeyRemoveCommand implements Callable<Integer> {
    private final HostService hostService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..4", hideParamSyntax = true,
        paramLabel = "[CLUSTER] HOST_GROUP MATCHING_STRING [USER]")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the authorized-key removal command.
     *
     * @param hostService host operation service
     */
    public SshKeyRemoveCommand(HostService hostService) {
      this.hostService = hostService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("ssh-key-remove", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "ssh-key-remove", arguments, 2, 3,
            "[CLUSTER] HOST_GROUP MATCHING_STRING [USER]");
        hostService.removeKeys(
            config, resolved.cluster(), resolved.values().getFirst(),
            resolved.values().get(1), resolved.valueOrDefault(2, "root"),
            parent.assumedYes, parent.dryRun);
      });
    }
  }

  /**
   * Builds configured projects for a cluster environment.
   */
  @Dependent
  @Unremovable
  @Command(name = "build", description = "Build projects for a cluster environment.")
  public static class BuildCommand implements Callable<Integer> {
    private final BuildService buildService;

    @ParentCommand
    ClusterCtlCommand parent;

    @Parameters(index = "0..*", arity = "0..3", hideParamSyntax = true,
        paramLabel = "[CLUSTER] ENVIRONMENT [PROJECTS]")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the build command.
     *
     * @param buildService build operation service
     */
    public BuildCommand(BuildService buildService) {
      this.buildService = buildService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("build", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "build", arguments, 1, 2, "[CLUSTER] ENVIRONMENT [PROJECTS]");
        buildService.build(
            config, resolved.cluster(), resolved.values().getFirst(),
            resolved.valueOrDefault(1, "all"), parent.dryRun);
      });
    }
  }

  /**
   * Deploys a configured application or group.
   */
  @Dependent
  @Unremovable
  @Command(name = "deploy", description = "Deploy an app or app group.")
  public static class DeployCommand implements Callable<Integer> {
    private final DeploymentService deploymentService;

    @ParentCommand
    ClusterCtlCommand parent;

    // The label describes the complete argument tuple, not each individual value.
    @Parameters(index = "0..*", arity = "0..3", hideParamSyntax = true,
        paramLabel = "[CLUSTER] ENVIRONMENT APP_OR_GROUP")
    List<String> arguments = new ArrayList<>();

    /**
     * Creates the deploy command.
     *
     * @param deploymentService deployment operation service
     */
    public DeployCommand(DeploymentService deploymentService) {
      this.deploymentService = deploymentService;
    }

    /** {@inheritDoc} */
    @Override
    public Integer call() {
      return parent.invoke("deploy", config -> {
        ClusterArguments resolved = parent.resolveArguments(
            config, "deploy", arguments, 2, 2, "[CLUSTER] ENVIRONMENT APP_OR_GROUP");
        deploymentService.deploy(
            config, resolved.cluster(), resolved.values().getFirst(),
            resolved.values().get(1), parent.assumedYes, parent.dryRun);
      });
    }
  }
}
