package com.dropchop.systems.service;

import java.util.List;
import java.util.regex.Pattern;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.strategy.TerminalRequest;
import com.dropchop.systems.strategy.TerminalStrategy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

/**
 * Resolves a host group and invokes the overridden or detected terminal implementation.
 */
@ApplicationScoped
public class TerminalService {
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*\\$?");
  private final ClusterCatalog catalog;
  private final Instance<TerminalStrategy> strategies;
  private final TerminalDetector terminalDetector;

  /**
   * Creates the terminal service.
   *
   * @param catalog configuration catalog
   * @param strategies available terminal implementations
   * @param detector terminal environment resolver
   */
  public TerminalService(ClusterCatalog catalog, Instance<TerminalStrategy> strategies, TerminalDetector detector) {
    this.catalog = catalog;
    this.strategies = strategies;
    this.terminalDetector = detector;
  }

  /**
   * Opens one terminal tab per resolved host.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param group group or host selector, optionally prefixed by USER@
   * @param dryRun whether to preview commands
   */
  public void open(ClusterCtlConfig config, String cluster, String group, boolean dryRun) {
    String sshUser = null;
    int separator = group.indexOf('@');
    if (separator >= 0) {
      sshUser = group.substring(0, separator);
      group = group.substring(separator + 1);
      if (!USERNAME.matcher(sshUser).matches()) {
        throw new CliException("Invalid SSH user: " + sshUser);
      }
      if (group.isBlank() || group.contains("@")) {
        throw new CliException("Invalid terminal target. Use [USER@]HOST_GROUP_OR_HOST.");
      }
    }
    List<ClusterCtlConfig.Host> hosts = catalog.hosts(config, cluster, group);
    String type = terminalDetector.resolve();
    for (TerminalStrategy strategy : strategies) {
      if (strategy.type().equalsIgnoreCase(type)) {
        strategy.open(new TerminalRequest(cluster, group, hosts, dryRun, sshUser));
        return;
      }
    }
    throw new CliException("No terminal strategy is installed for type: " + type);
  }
}
