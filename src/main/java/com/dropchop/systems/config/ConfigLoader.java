package com.dropchop.systems.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resolves, interpolates, reads, and validates external YAML configuration.
 */
@ApplicationScoped
public class ConfigLoader {
  public static final String CONFIG_PATH_ENV = "CLUSTER_CTL_CONF_PATH";
  public static final String CONFIG_PATH_PROPERTY = "cluster.ctl.conf.path";

  private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}");

  private final ObjectMapper mapper;

  /**
   * Creates a strict YAML reader.
   */
  public ConfigLoader() {
    mapper = new ObjectMapper(new YAMLFactory());
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  /**
   * Checks whether a configuration path was supplied without reading any files.
   *
   * @param explicitPath optional command-line path
   * @return whether CLI, environment, or system properties select a configuration
   */
  public boolean isConfigured(String explicitPath) {
    return firstNonBlank(explicitPath, System.getenv(CONFIG_PATH_ENV),
        System.getProperty(CONFIG_PATH_PROPERTY)) != null;
  }

  /**
   * Loads configuration from an explicit path or the configured environment.
   *
   * @param explicitPath optional command-line path
   * @return validated configuration
   */
  public ClusterCtlConfig load(String explicitPath) {
    Path path = resolvePath(explicitPath);
    try {
      String yaml = interpolate(Files.readString(path));
      ClusterCtlConfig config = mapper.readValue(yaml, ClusterCtlConfig.class);
      config.sourceDirectory = path.getParent() == null
          ? Path.of(".").toAbsolutePath().normalize()
          : path.getParent();
      validate(config);
      return config;
    } catch (IOException exception) {
      throw new ConfigException("Unable to read configuration " + path + ": "
          + exception.getMessage(), exception);
    }
  }

  /**
   * Resolves the effective config path using CLI, environment, then system property.
   *
   * @param explicitPath optional command-line path
   * @return absolute normalized path
   */
  public Path resolvePath(String explicitPath) {
    String configured = firstNonBlank(
        explicitPath,
        System.getenv(CONFIG_PATH_ENV),
        System.getProperty(CONFIG_PATH_PROPERTY));
    if (configured == null) {
      throw new ConfigException("Configuration path is required. Set " + CONFIG_PATH_ENV + " or pass --config.");
    }
    Path path = expandHome(configured).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new ConfigException("Configuration is not a readable file: " + path);
    }
    return path;
  }

  /**
   * Expands a leading tilde in a configured filesystem path.
   *
   * @param value configured path
   * @return expanded path
   */
  public Path expandHome(String value) {
    if (value != null && (value.equals("~") || value.startsWith("~/"))) {
      return Path.of(System.getProperty("user.home") + value.substring(1));
    }
    assert value != null;
    return Path.of(value);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String interpolate(String input) {
    Matcher matcher = ENVIRONMENT_REFERENCE.matcher(input);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null || value.isEmpty()) {
        value = matcher.group(3);
      }
      if (value == null) {
        throw new ConfigException("Missing environment variable " + matcher.group(1));
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private void validate(ClusterCtlConfig config) {
    if (config == null || config.local == null || config.remote == null || config.apps == null) {
      throw new ConfigException("Configuration requires local, remote, and apps sections.");
    }
    Set<String> clusterNames = new HashSet<>();
    for (ClusterCtlConfig.Cluster cluster : config.remote.clusters) {
      requireName(cluster.name, "cluster");
      if (!clusterNames.add(cluster.name)) {
        throw new ConfigException("Duplicate cluster: " + cluster.name);
      }
    }
    if (clusterNames.isEmpty()) {
      throw new ConfigException("At least one remote cluster is required.");
    }
    if (config.remote.defaultCluster == null) {
      config.remote.defaultCluster = config.remote.clusters.getFirst().name;
    }
    if (!clusterNames.contains(config.remote.defaultCluster)) {
      throw new ConfigException("Unknown default cluster: " + config.remote.defaultCluster);
    }
    validateEnvironments(config, clusterNames);
    validateHosts(config, clusterNames);
    validateGroups(config, clusterNames);
  }

  private void validateEnvironments(ClusterCtlConfig config, Set<String> clusterNames) {
    Set<String> names = new HashSet<>();
    for (ClusterCtlConfig.Environment environment : config.remote.environments) {
      requireName(environment.name, "environment");
      if (!names.add(environment.name)) {
        throw new ConfigException("Duplicate environment: " + environment.name);
      }
      if (!clusterNames.contains(environment.cluster)) {
        throw new ConfigException(
            "Environment " + environment.name + " refers to unknown cluster " + environment.cluster
        );
      }
    }
  }

  private void validateHosts(ClusterCtlConfig config, Set<String> clusterNames) {
    Set<String> keys = new HashSet<>();
    for (ClusterCtlConfig.Host host : config.remote.hosts) {
      requireName(host.name, "host");
      if (!clusterNames.contains(host.cluster)) {
        throw new ConfigException("Host " + host.name + " refers to unknown cluster " + host.cluster);
      }
      addUnique(keys, host.cluster, host.name, "host or host alias");
      for (String alias : host.aliases) {
        addUnique(keys, host.cluster, alias, "host or host alias");
      }
    }
  }

  private void validateGroups(ClusterCtlConfig config, Set<String> clusterNames) {
    Set<String> keys = new HashSet<>();
    for (ClusterCtlConfig.Group group : config.remote.groups) {
      requireName(group.name, "group");
      if (!clusterNames.contains(group.cluster)) {
        throw new ConfigException("Group " + group.name + " refers to unknown cluster " + group.cluster);
      }
      addUnique(keys, group.cluster, group.name, "group or group alias");
      for (String alias : group.aliases) {
        addUnique(keys, group.cluster, alias, "group or group alias");
      }
    }
  }

  private void addUnique(Set<String> keys, String cluster, String value, String kind) {
    if (value == null || value.isBlank()) {
      throw new ConfigException("Blank " + kind + " in cluster " + cluster);
    }
    if (!keys.add(cluster + "\u0000" + value)) {
      throw new ConfigException("Duplicate " + kind + " " + value + " in cluster " + cluster);
    }
  }

  private void requireName(String value, String kind) {
    if (value == null || value.isBlank()) {
      throw new ConfigException("A " + kind + " is missing its name.");
    }
  }
}
