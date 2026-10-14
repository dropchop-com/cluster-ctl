package com.dropchop.systems.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.strategy.DeploymentRequest;
import com.dropchop.systems.strategy.DeploymentStrategy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

/**
 * Resolves application selectors and invokes a configured deployment strategy.
 */
@ApplicationScoped
public class DeploymentService {
  private final ClusterCatalog catalog;
  private final Instance<DeploymentStrategy> strategies;
  private final PromptService prompts;

  /**
   * Creates the deployment service.
   *
   * @param catalog configuration catalog
   * @param strategies available deployment implementations
   * @param prompts confirmation service
   */
  public DeploymentService(ClusterCatalog catalog, Instance<DeploymentStrategy> strategies, PromptService prompts) {
    this.catalog = catalog;
    this.strategies = strategies;
    this.prompts = prompts;
  }

  /**
   * Deploys an artifact name, alias, or group.
   *
   * @param config active configuration
   * @param cluster cluster name
   * @param environment environment name
   * @param selector artifact or artifact group
   * @param assumedYes whether deployment is pre-authorized
   * @param dryRun whether to preview commands
   */
  public void deploy(ClusterCtlConfig config, String cluster, String environment, String selector, boolean assumedYes,
                     boolean dryRun) {
    ClusterCtlConfig.Environment target = catalog.environment(config, cluster, environment);
    List<ClusterCtlConfig.Artifact> artifacts = catalog.artifacts(config, cluster, selector);
    List<Path> paths = new ArrayList<>();
    for (ClusterCtlConfig.Artifact artifact : artifacts) {
      String fileName = artifact.fileName
          .replace("{cluster}", cluster)
          .replace("{environment}", environment);
      Path path = catalog.path(config, artifact.buildOutputPath).resolve(fileName);
      if (!dryRun && !Files.isRegularFile(path)) {
        throw new CliException("Deployable artifact does not exist: " + path);
      }
      paths.add(path);
    }
    System.out.println("Artifacts selected for " + environment + " on " + cluster + ":");
    paths.forEach(path -> System.out.println("  " + path));
    if (!dryRun && !prompts.confirm("Deploy " + paths.size() + " artifact(s)?", assumedYes)) {
      throw new CliException("Deployment cancelled.");
    }
    strategy(target.deploy.type).deploy(new DeploymentRequest(target, paths, dryRun));
  }

  private DeploymentStrategy strategy(String type) {
    for (DeploymentStrategy strategy : strategies) {
      if (strategy.type().equalsIgnoreCase(type)) {
        return strategy;
      }
    }
    throw new CliException("No deployment strategy is installed for type: " + type);
  }
}
