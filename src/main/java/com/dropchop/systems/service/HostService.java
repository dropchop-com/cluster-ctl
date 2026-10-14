package com.dropchop.systems.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.remote.RemoteControlStrategy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

/**
 * Implements host-group operations independently of the configured control transport.
 */
@ApplicationScoped
public class HostService {
  private static final Pattern USERNAME = Pattern.compile(
      "[A-Za-z_][A-Za-z0-9_.-]*\\$?");

  private final ClusterCatalog catalog;
  private final Instance<RemoteControlStrategy> controls;
  private final PromptService prompts;
  private final ConsoleOutput consoleOutput;

  /**
   * Creates the host operation service.
   *
   * @param catalog configuration catalog
   * @param controls available remote control implementations
   * @param prompts confirmation service
   * @param consoleOutput colored console formatter
   */
  public HostService(ClusterCatalog catalog, Instance<RemoteControlStrategy> controls, PromptService prompts,
                     ConsoleOutput consoleOutput) {
    this.catalog = catalog;
    this.controls = controls;
    this.prompts = prompts;
    this.consoleOutput = consoleOutput;
  }

  /**
   * Executes one remote command on every distinct group host.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param group group name or alias
   * @param command remote command
   * @param dryRun whether to preview execution
   */
  public void execute(ClusterCtlConfig config, String cluster, String group, String command, boolean dryRun) {
    if (command == null || command.isBlank()) {
      throw new CliException("Remote command must not be blank.");
    }
    RemoteControlStrategy control = control(config, cluster);
    for (ClusterCtlConfig.Host host : catalog.hosts(config, cluster, group)) {
      consoleOutput.execHeader(host, command);
      control.execute(host, command, dryRun);
      consoleOutput.execFooter();
    }
  }

  /**
   * Lists authorized keys on every distinct group host.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param group group name or alias
   * @param user target user
   * @param dryRun whether to preview execution
   */
  public void listKeys(ClusterCtlConfig config, String cluster, String group, String user, boolean dryRun) {
    validateUser(user);
    RemoteControlStrategy control = control(config, cluster);
    for (ClusterCtlConfig.Host host : catalog.hosts(config, cluster, group)) {
      System.out.println("Authorized keys for " + user + " on " + host.name);
      control.listKeys(host, user, dryRun);
    }
  }

  /**
   * Adds one public key on every distinct group host.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param group group name or alias
   * @param keyPath local public key path
   * @param user target user
   * @param verifyRequired whether to enforce verify-required
   * @param dryRun whether to preview execution
   */
  public void addKey(ClusterCtlConfig config, String cluster, String group, Path keyPath, String user,
                     boolean verifyRequired, boolean dryRun) {
    validateUser(user);
    String key = readPublicKey(keyPath);
    RemoteControlStrategy control = control(config, cluster);
    for (ClusterCtlConfig.Host host : catalog.hosts(config, cluster, group)) {
      System.out.println("Adding authorized key for " + user + " on " + host.name);
      control.addKey(host, user, key, verifyRequired, dryRun);
    }
  }

  /**
   * Finds and interactively removes matching authorized keys on group hosts.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param group group name or alias
   * @param matchingString fixed search string
   * @param user target user
   * @param assumedYes whether removal is pre-authorized
   * @param dryRun whether to preview execution
   */
  public void removeKeys(ClusterCtlConfig config, String cluster, String group, String matchingString, String user,
                         boolean assumedYes, boolean dryRun) {
    validateUser(user);
    if (matchingString == null || matchingString.isBlank()
        || matchingString.contains("\n") || matchingString.contains("\r")) {
      throw new CliException("Matching string must be non-empty and single-line.");
    }
    RemoteControlStrategy control = control(config, cluster);
    for (ClusterCtlConfig.Host host : catalog.hosts(config, cluster, group)) {
      String matches = control.findKeys(host, user, matchingString, dryRun);
      if (dryRun) {
        continue;
      }
      if (matches.isBlank()) {
        System.out.println("No matching authorized keys for " + user + " on " + host.name + ".");
        continue;
      }
      System.out.println("Matching authorized keys for " + user + " on " + host.name + ":");
      System.out.print(matches);
      if (prompts.confirm("Remove matching key(s) from " + host.name + "?", assumedYes)) {
        control.removeKeys(host, user, matchingString, false);
      } else {
        System.out.println("Skipped " + host.name + ".");
      }
    }
  }

  private RemoteControlStrategy control(ClusterCtlConfig config, String clusterName) {
    String type = catalog.cluster(config, clusterName).control;
    for (RemoteControlStrategy strategy : controls) {
      if (strategy.type().equalsIgnoreCase(type)) {
        return strategy;
      }
    }
    throw new CliException("No remote control strategy is installed for type: " + type);
  }

  private void validateUser(String user) {
    if (user == null || !USERNAME.matcher(user).matches()) {
      throw new CliException("Invalid SSH user: " + user);
    }
  }

  private String readPublicKey(Path path) {
    try {
      if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
        throw new CliException("Public key is not a readable file: " + path);
      }
      List<String> lines = Files.readAllLines(path).stream()
          .filter(line -> !line.isBlank())
          .toList();
      if (lines.size() != 1) {
        throw new CliException("Public key file must contain exactly one non-empty line.");
      }
      String key = lines.getFirst().stripTrailing();
      String[] parts = key.split("\\s+", 3);
      if (parts.length < 2) {
        throw new CliException("Invalid SSH public key: " + path);
      }
      Base64.getDecoder().decode(parts[1]);
      return key;
    } catch (IllegalArgumentException exception) {
      throw new CliException("Invalid SSH public key: " + path, exception);
    } catch (IOException exception) {
      throw new CliException("Unable to read public key " + path
          + ": " + exception.getMessage(), exception);
    }
  }
}
