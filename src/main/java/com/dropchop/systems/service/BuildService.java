package com.dropchop.systems.service;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.strategy.BuildRequest;
import com.dropchop.systems.strategy.BuildStrategy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

/**
 * Selects a configured build strategy and invokes it.
 */
@ApplicationScoped
public class BuildService {
  private final ClusterCatalog catalog;
  private final Instance<BuildStrategy> strategies;

  /**
   * Creates the build service.
   *
   * @param catalog configuration catalog
   * @param strategies available build implementations
   */
  public BuildService(ClusterCatalog catalog, Instance<BuildStrategy> strategies) {
    this.catalog = catalog;
    this.strategies = strategies;
  }

  /**
   * Builds a configured project selection for an environment.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param environment environment name
   * @param projects project name, alias, or all
   * @param dryRun whether to preview commands
   */
  public void build(ClusterCtlConfig config, String cluster, String environment, String projects, boolean dryRun) {
    ClusterCtlConfig.Environment target = catalog.environment(config, cluster, environment);
    String type = config.local.build.type;
    for (BuildStrategy strategy : strategies) {
      if (strategy.type().equalsIgnoreCase(type)) {
        strategy.build(new BuildRequest(config, cluster, target, projects, dryRun));
        return;
      }
    }
    throw new CliException("No build strategy is installed for type: " + type);
  }
}
