package com.dropchop.systems.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.cli.CliUsageException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resolves configuration names, aliases, group composition, and artifact selectors.
 */
@ApplicationScoped
public class ClusterCatalog {
  private final ConfigLoader configLoader;

  /**
   * Creates a catalog with filesystem path expansion support.
   *
   * @param configLoader configuration loader
   */
  public ClusterCatalog(ConfigLoader configLoader) {
    this.configLoader = configLoader;
  }

  /**
   * Rejects a command disabled by the selected configuration.
   *
   * @param config active configuration
   * @param command command name
   */
  public void requireCommand(ClusterCtlConfig config, String command) {
    if (!config.commands.isEmpty() && config.commands.stream().noneMatch(command::equals)) {
      throw new CliException("Command '" + command + "' is disabled by this configuration.");
    }
  }

  /**
   * Finds a configured cluster.
   *
   * @param config active configuration
   * @param name cluster name
   * @return matching cluster
   */
  public ClusterCtlConfig.Cluster cluster(ClusterCtlConfig config, String name) {
    return config.remote.clusters.stream()
        .filter(value -> value.name.equals(name))
        .findFirst()
        .orElseThrow(() -> new CliUsageException("Unknown cluster: " + name));
  }

  /**
   * Finds an environment and verifies that it belongs to the requested cluster.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param name environment name
   * @return matching environment
   */
  public ClusterCtlConfig.Environment environment(
      ClusterCtlConfig config,
      String cluster,
      String name) {
    ClusterCtlConfig.Environment environment = config.remote.environments.stream()
        .filter(value -> value.name.equals(name))
        .findFirst()
        .orElseThrow(() -> new CliUsageException("Unknown environment: " + name));
    if (!environment.cluster.equals(cluster)) {
      throw new CliUsageException(
          "Environment " + name + " belongs to cluster " + environment.cluster + ", not " + cluster + "."
      );
    }
    return environment;
  }

  /**
   * Resolves all distinct hosts in a cluster-scoped group or one host alias.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param groupName group, host name, or alias
   * @return hosts in declaration order
   */
  public List<ClusterCtlConfig.Host> hosts(
      ClusterCtlConfig config,
      String cluster,
    String groupName) {
    cluster(config, cluster);
    Optional<ClusterCtlConfig.Group> selectedGroup = findGroup(
        config, cluster, groupName);
    if (selectedGroup.isEmpty()) {
      return findHost(config, cluster, groupName)
          .map(List::of)
          .orElseThrow(() -> new CliUsageException(
              "Unknown host group, host, or alias '" + groupName + "' in cluster " + cluster + "."
          ));
    }
    LinkedHashMap<String, ClusterCtlConfig.Host> result = new LinkedHashMap<>();
    collectHosts(config, selectedGroup.get(), result, new HashSet<>());
    if (result.isEmpty()) {
      throw new CliException("Host group " + groupName + " is empty.");
    }
    return List.copyOf(result.values());
  }

  /**
   * Resolves one artifact name, alias, or group for a cluster.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param selector artifact selector
   * @return selected artifacts
   */
  public List<ClusterCtlConfig.Artifact> artifacts(
      ClusterCtlConfig config,
      String cluster,
      String selector) {
    List<ClusterCtlConfig.Artifact> matches = new ArrayList<>();
    for (ClusterCtlConfig.Artifact artifact : config.apps.artifacts) {
      if (!artifact.clusters.isEmpty() && !artifact.clusters.contains(cluster)) {
        continue;
      }
      if (artifact.name.equals(selector)
          || artifact.group.equals(selector)
          || artifact.aliases.contains(selector)) {
        matches.add(artifact);
      }
    }
    if (matches.isEmpty()) {
      throw new CliUsageException("Unknown application or application group: " + selector);
    }
    return matches;
  }

  /**
   * Resolves a configured path relative to the YAML file and expands a leading tilde.
   *
   * @param config active configuration
   * @param value configured path
   * @return normalized path
   */
  public Path path(ClusterCtlConfig config, String value) {
    if (value == null || value.isBlank()) {
      throw new CliException("A required filesystem path is not configured.");
    }
    Path path = configLoader.expandHome(value);
    if (!path.isAbsolute()) {
      path = config.sourceDirectory.resolve(path);
    }
    return path.normalize();
  }

  /**
   * Returns every group for a cluster.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @return configured groups
   */
  public List<ClusterCtlConfig.Group> groups(ClusterCtlConfig config, String cluster) {
    return config.remote.groups.stream()
        .filter(group -> group.cluster.equals(cluster))
        .toList();
  }

  private ClusterCtlConfig.Group group(
      ClusterCtlConfig config,
      String cluster,
      String name) {
    return findGroup(config, cluster, name)
        .orElseThrow(() -> new CliException("Unknown host group or alias '" + name + "' in cluster " + cluster + "."));
  }

  private Optional<ClusterCtlConfig.Group> findGroup(
      ClusterCtlConfig config,
      String cluster,
      String name) {
    return config.remote.groups.stream()
        .filter(value -> value.cluster.equals(cluster))
        .filter(value -> value.name.equals(name) || value.aliases.contains(name))
        .findFirst();
  }

  private ClusterCtlConfig.Host host(
      ClusterCtlConfig config,
      String cluster,
      String name) {
    return findHost(config, cluster, name)
        .orElseThrow(() -> new CliException(
            "Group refers to unknown host '" + name + "' in cluster " + cluster + "."
        ));
  }

  private Optional<ClusterCtlConfig.Host> findHost(
      ClusterCtlConfig config,
      String cluster,
      String name) {
    return config.remote.hosts.stream()
        .filter(value -> value.cluster.equals(cluster))
        .filter(value -> value.name.equals(name) || value.aliases.contains(name))
        .findFirst();
  }

  private void collectHosts(
      ClusterCtlConfig config,
      ClusterCtlConfig.Group group,
      LinkedHashMap<String, ClusterCtlConfig.Host> result,
      Set<String> visiting) {
    String key = group.cluster + ":" + group.name.toLowerCase(Locale.ROOT);
    if (!visiting.add(key)) {
      throw new CliException("Host group cycle detected at " + group.name + ".");
    }
    for (String hostName : group.hosts) {
      ClusterCtlConfig.Host host = host(config, group.cluster, hostName);
      result.putIfAbsent(host.name, host);
    }
    for (String nested : group.groups) {
      collectHosts(config, group(config, group.cluster, nested), result, visiting);
    }
    visiting.remove(key);
  }
}
