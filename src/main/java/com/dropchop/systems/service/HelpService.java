package com.dropchop.systems.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;

import jakarta.enterprise.context.ApplicationScoped;
import picocli.CommandLine;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Prints command syntax and configuration-aware inventory help.
 */
@ApplicationScoped
public class HelpService {
  private final ClusterCatalog catalog;

  /**
   * Creates the help service.
   *
   * @param catalog configuration catalog
   */
  public HelpService(ClusterCatalog catalog) {
    this.catalog = catalog;
  }

  private String parameterSynopsis(PositionalParamSpec parameter) {
    String suffix = parameter.arity().max() == Integer.MAX_VALUE ? "..." : "";
    return parameter.paramLabel() + suffix;
  }

  private String commandSynopsis(CommandLine rootCommand, CommandLine command) {
    String commandName = command.getCommandName();
    String commandAliases = String.join("|", command.getCommandSpec().aliases());
    String displayedName = commandAliases.isBlank()
        ? commandName
        : commandName + "|" + commandAliases;
    String parameters = command.getCommandSpec().positionalParameters().stream()
        .map(this::parameterSynopsis)
        .reduce((left, right) -> left + " " + right)
        .orElse("");
    return rootCommand.getCommandName() + " " + displayedName
        + (parameters.isBlank() ? "" : " " + parameters);
  }

  private void printCommands(ClusterCtlConfig config, CommandLine rootCommand) {
    List<String> enabledCommands = config.commands.isEmpty()
        ? rootCommand.getSubcommands().values().stream()
            .map(CommandLine::getCommandName)
            .distinct()
            .toList()
        : config.commands;
    System.out.println("Command list:");
    for (String commandName : enabledCommands) {
      CommandLine command = rootCommand.getSubcommands().get(commandName);
      if (command == null) {
        System.out.println("  " + rootCommand.getCommandName() + " " + commandName + " (not registered)");
        continue;
      }
      String description = String.join(" ", command.getCommandSpec().usageMessage().description());
      System.out.println("  " + commandSynopsis(rootCommand, command));
      if (!description.isBlank()) {
        System.out.println("    " + description);
      }
    }
  }

  private String aliases(java.util.List<String> values) {
    return values.isEmpty() ? "" : " (aliases: " + String.join(", ", values) + ")";
  }

  /**
   * Prints enabled command syntax and the inventory for selected clusters.
   *
   * @param config active configuration
   * @param selectedCluster optional cluster name
   * @param rootCommand root command metadata
   */
  public void print(ClusterCtlConfig config, String selectedCluster, CommandLine rootCommand) {
    printCommands(config, rootCommand);
    for (ClusterCtlConfig.Cluster cluster : config.remote.clusters) {
      if (selectedCluster != null && !selectedCluster.equals(cluster.name)) {
        continue;
      }
      catalog.cluster(config, cluster.name);
      System.out.println();
      System.out.println("Cluster " + cluster.name + " (" + cluster.control + ")");
      System.out.println("Host groups:");
      for (ClusterCtlConfig.Group group : catalog.groups(config, cluster.name)) {
        System.out.println("  " + group.name + aliases(group.aliases)
            + ": " + catalog.hosts(config, cluster.name, group.name).stream()
                .map(host -> host.aliases.isEmpty() ? host.name : host.aliases.getFirst())
                .toList());
      }
      Set<String> applications = new LinkedHashSet<>();
      for (ClusterCtlConfig.Artifact artifact : config.apps.artifacts) {
        if (artifact.clusters.isEmpty() || artifact.clusters.contains(cluster.name)) {
          applications.add(artifact.group + "/" + artifact.name);
        }
      }
      System.out.println("Applications: " + (applications.isEmpty() ? "none" : String.join(", ", applications)));
    }
    if (selectedCluster != null) {
      catalog.cluster(config, selectedCluster);
    }
  }
}
