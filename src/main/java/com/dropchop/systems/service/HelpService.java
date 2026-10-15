package com.dropchop.systems.service;

import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ProcessProperties;

import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.Style;
import picocli.CommandLine.Help.Ansi.Text;
import picocli.CommandLine.Help.ColorScheme;

/**
 * Prints command-specific synthetic examples and selected-cluster inventory tables.
 */
@ApplicationScoped
public class HelpService {
  private static final int OUTPUT_WIDTH = 150;
  private static final int INVENTORY_COLUMN_WIDTH = 72;
  private static final int APPLICATION_COLUMN_WIDTH = 25;
  private static final String COLUMN_SEPARATOR = " | ";
  private static final ColorScheme COLORS = new ColorScheme.Builder(Ansi.ON).build();
  private static final ColorScheme PLAIN = new ColorScheme.Builder(Ansi.OFF).build();
  private final ClusterCatalog catalog;
  private final Supplier<String> argumentZero;

  /**
   * Identifies one application within its group while combining duplicate aliases.
   */
  private record ApplicationKey(String group, String name) {
  }

  /**
   * Illustrative command arguments and their explanation, independent of real configuration.
   */
  private record Example(String arguments, String description) {
  }

  private List<Example> examples(String command) {
    return switch (command) {
      case "docker" -> List.of(
          new Example("docker pull pg", "Pull a configured image by alias."),
          new Example("docker start", "Start local containers."),
          new Example("docker stop", "Stop local containers."),
          new Example("docker log web-1", "Follow a container's logs."));
      case "term" -> List.of(
          new Example("term web", "Open a host group on the default cluster."),
          new Example("term c2 operator@web-1", "Select a cluster, host, and SSH user."));
      case "exec" -> List.of(
          new Example("exec web \"uptime -p\"", "Run a command on a default-cluster group."),
          new Example("exec c2 web-1 \"hostname\"", "Run a command on an explicitly selected host and cluster."));
      case "ssh-key-list" -> List.of(
          new Example("ssh-key-list web operator", "List a user's keys on a default-cluster group."),
          new Example("ssh-key-list c2 web-1 operator", "List keys on a host in an explicit cluster."));
      case "ssh-key-add" -> List.of(
          new Example("ssh-key-add web ~/.ssh/id_ed25519.pub operator", "Add a key to a default-cluster group."),
          new Example("ssh-key-add c2 web-1 ~/.ssh/id_ed25519.pub operator verify-required",
              "Add a key with verification on a selected host and cluster."));
      case "ssh-key-remove" -> List.of(
          new Example("ssh-key-remove web old-key operator", "Remove matching keys from a default-cluster group."),
          new Example("ssh-key-remove c2 web-1 old-key operator", "Select a cluster and host for key removal."));
      case "build" -> List.of(
          new Example("build prod services", "Build projects for an environment on the default cluster."),
          new Example("build c2 stage all", "Build all projects for an explicit cluster and environment."));
      case "deploy" -> List.of(
          new Example("deploy prod web-api", "Deploy an application on the default cluster."),
          new Example("deploy c2 stage services", "Deploy an application group on an explicit cluster."));
      default -> List.of(new Example(command + " --help", "Show command usage and options."));
    };
  }

  private static String nativeArgumentZero() {
    if (!ImageInfo.inImageRuntimeCode()) {
      return null;
    }
    try {
      return ProcessProperties.getArgumentVectorProgramName();
    } catch (UnsupportedOperationException exception) {
      return null;
    }
  }

  HelpService(ClusterCatalog catalog, Supplier<String> argumentZero) {
    this.catalog = catalog;
    this.argumentZero = argumentZero;
  }

  /**
   * Creates the help service.
   *
   * @param catalog configuration catalog
   */
  @Inject
  public HelpService(ClusterCatalog catalog) {
    this(catalog, HelpService::nativeArgumentZero);
  }

  /**
   * Resolves the native argv[0] basename, falling back to command metadata on the JVM.
   *
   * @param fallback configured command name
   * @return invocation name suitable for usage and examples
   */
  public String invocationName(String fallback) {
    String argument = argumentZero.get();
    if (argument == null || argument.isBlank() || argument.chars().anyMatch(Character::isISOControl)) {
      return fallback;
    }
    try {
      Path name = Path.of(argument).getFileName();
      return name == null || name.toString().isBlank() ? fallback : name.toString();
    } catch (InvalidPathException exception) {
      return fallback;
    }
  }

  private Text plain(String value) {
    return PLAIN.apply(value.replace('\t', ' '), List.of());
  }

  private Text colored(String value, Style style) {
    return COLORS.apply(value.replace('\t', ' '), List.of(style));
  }

  private Text withAliases(Text name, List<String> aliases) {
    if (aliases.isEmpty()) {
      return name;
    }
    return COLORS.apply("", List.of()).concat(name).concat(plain(" ("))
        .concat(colored(String.join(", ", aliases), Style.fg_cyan)).concat(plain(")"));
  }

  private void printSection(PrintWriter out, String title) {
    out.println();
    out.println(title + ":");
    out.println();
  }

  private List<Text> wrapCell(Text value, int width) {
    List<Text> lines = new ArrayList<>();
    // Slice styled text by its visible characters so ANSI codes never affect layout.
    for (Text original : value.splitLines()) {
      String line = original.plainString();
      int start = 0;
      int end = line.stripTrailing().length();
      while (start < end && Character.isWhitespace(line.charAt(start))) {
        start++;
      }
      while (end - start > width) {
        int boundary = line.lastIndexOf(' ', start + width);
        if (boundary <= start) {
          boundary = start + width;
        }
        lines.add(original.substring(start, boundary));
        start = boundary;
        while (start < end && Character.isWhitespace(line.charAt(start))) {
          start++;
        }
      }
      lines.add(original.substring(start, end));
    }
    return lines;
  }

  private String alignedLine(List<Text> cells, int[] widths, String separator) {
    StringBuilder line = new StringBuilder();
    for (int column = 0; column < cells.size(); column++) {
      if (column > 0) {
        line.append(separator);
      }
      Text value = cells.get(column);
      line.append(value);
      if (column < cells.size() - 1) {
        line.repeat(" ", widths[column] - value.plainString().length());
      }
    }
    return line.toString().stripTrailing();
  }

  private void printRows(PrintWriter out, List<List<Text>> rows, int[] widths, String separator) {
    String padding = " ".repeat(2);
    if (rows.isEmpty()) {
      out.println(padding + "None configured.");
      return;
    }

    for (List<Text> row : rows) {
      List<List<Text>> wrapped = new ArrayList<>();
      int height = 1;
      for (int column = 0; column < widths.length; column++) {
        List<Text> lines = wrapCell(row.get(column), widths[column]);
        wrapped.add(lines);
        height = Math.max(height, lines.size());
      }
      for (int line = 0; line < height; line++) {
        List<Text> cells = new ArrayList<>();
        for (List<Text> column : wrapped) {
          cells.add(line < column.size() ? column.get(line) : plain(""));
        }
        out.println(padding + alignedLine(cells, widths, separator));
      }
    }
  }

  private void printTable(PrintWriter out, List<String> headers, List<List<Text>> rows, int... widths) {
    out.println("  " + alignedLine(headers.stream().map(this::plain).toList(), widths, COLUMN_SEPARATOR));
    List<Text> rule = new ArrayList<>();
    for (int width : widths) {
      rule.add(plain("-".repeat(width)));
    }
    // Use the same column padding and separator positions as the header and body.
    out.println("  " + alignedLine(rule, widths, COLUMN_SEPARATOR).replace(' ', '-').replace('|', '+'));
    printRows(out, rows, widths, COLUMN_SEPARATOR);
  }

  private void printExamples(PrintWriter out, ClusterCtlConfig config, CommandLine rootCommand, CommandLine command) {
    List<String> enabledCommands = command == rootCommand
        ? rootCommand.getSubcommands().values().stream().map(CommandLine::getCommandName).distinct()
            .filter(name -> config == null || config.commands.isEmpty() || config.commands.contains(name)).toList()
        : List.of(command.getCommandName());
    List<List<Text>> rows = new ArrayList<>();
    for (String commandName : enabledCommands) {
      for (Example example : examples(commandName)) {
        rows.add(List.of(plain(rootCommand.getCommandName() + " " + example.arguments()), plain(example.description())));
      }
    }
    printSection(out, "Examples");
    out.println("  Synthetic names below are illustrative; replace them with your configured selectors.");
    out.println();
    int invocationWidth = Math.min(100, rows.stream()
        .mapToInt(row -> row.getFirst().plainString().length()).max().orElse(0));
    printRows(out, rows, new int[] {invocationWidth, OUTPUT_WIDTH - 6 - invocationWidth}, "  ");
  }

  private void printHostGroups(PrintWriter out, ClusterCtlConfig config, String cluster) {
    List<List<Text>> rows = new ArrayList<>();
    for (ClusterCtlConfig.Group group : catalog.groups(config, cluster)) {
      String hosts = String.join(", ", catalog.hosts(config, cluster, group.name).stream()
          .map(host -> host.aliases.isEmpty() ? host.name : host.aliases.getFirst())
          .toList());
      Text groupLabel = withAliases(colored(group.name, Style.fg_yellow), group.aliases);
      rows.add(List.of(groupLabel, plain(hosts.isEmpty() ? "-" : hosts)));
    }
    printTable(out, List.of("Group (aliases)", "Hosts"), rows, INVENTORY_COLUMN_WIDTH, INVENTORY_COLUMN_WIDTH);
  }

  private void printApplications(PrintWriter out, ClusterCtlConfig config, String cluster) {
    Map<ApplicationKey, Set<String>> applications = new LinkedHashMap<>();
    for (ClusterCtlConfig.Artifact artifact : config.apps.artifacts) {
      if (artifact.clusters.isEmpty() || artifact.clusters.contains(cluster)) {
        applications.computeIfAbsent(new ApplicationKey(artifact.group, artifact.name),
            ignored -> new LinkedHashSet<>()).addAll(artifact.aliases);
      }
    }
    List<List<Text>> rows = applications.entrySet().stream()
        .map(entry -> List.of(colored(entry.getKey().group(), Style.fg_yellow),
            withAliases(plain(entry.getKey().name()), List.copyOf(entry.getValue()))))
        .toList();
    printTable(out, List.of("Application group", "Application"), rows, APPLICATION_COLUMN_WIDTH, APPLICATION_COLUMN_WIDTH);
  }

  private String display(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private void printEnvironments(PrintWriter out, ClusterCtlConfig config, String cluster) {
    List<List<Text>> rows = config.remote.environments.stream()
        .filter(environment -> cluster.equals(environment.cluster))
        .map(environment -> List.of(colored(environment.name, Style.fg_yellow),
            plain(display(environment.namespace)),
            plain(display(environment.buildEnvironment))))
        .toList();
    printSection(out, "Environments");
    printTable(out, List.of("Environment", "Namespace", "Build environment"), rows, 25, 25, 25);
  }

  private String selectedCluster(ClusterCtlConfig config, CommandLine command) {
    var parameters = command.getCommandSpec().positionalParameters();
    List<String> arguments = parameters.isEmpty() ? List.of() : parameters.getFirst().stringValues();
    if (!arguments.isEmpty() && config.remote.clusters.stream()
        .anyMatch(cluster -> cluster.name.equals(arguments.getFirst()))) {
      return arguments.getFirst();
    }
    return config.remote.defaultCluster;
  }

  /**
   * Prints standard usage, synthetic examples, and only inventories relevant to this command.
   * Missing configuration never prevents help; actual inventories remain cluster-scoped.
   *
   * @param config active configuration, or null when no configuration is available
   * @param rootCommand root command metadata
   * @param command command whose help was requested
   */
  public void print(ClusterCtlConfig config, CommandLine rootCommand, CommandLine command) {
    PrintWriter out = command.getOut();
    command.usage(out);
    printExamples(out, config, rootCommand, command);
    String name = command == rootCommand ? "" : command.getCommandName();
    boolean hosts = name.startsWith("ssh-") || List.of("exec", "term").contains(name);
    boolean environments = name.equals("build") || name.equals("deploy");
    if (!hosts && !environments) {
      out.flush();
      return;
    }
    if (config == null) {
      out.println();
      out.println("  Set CLUSTER_CTL_CONF_PATH or pass --config to include inventory listings.");
      out.flush();
      return;
    }
    ClusterCtlConfig.Cluster cluster = catalog.cluster(config, selectedCluster(config, command));
    if (!name.equals("deploy")) {
      String defaultMarker = cluster.name.equals(config.remote.defaultCluster) ? " [default]" : "";
      printSection(out, "Cluster " + cluster.name + " (" + cluster.control + ")" + defaultMarker);
    }
    if (hosts) {
      printHostGroups(out, config, cluster.name);
    }
    if (name.equals("deploy")) {
      out.println();
      printApplications(out, config, cluster.name);
    }
    if (environments) {
      printEnvironments(out, config, cluster.name);
    }
    out.flush();
  }
}
