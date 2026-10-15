package com.dropchop.systems.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.cli.CliUsageException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Implements local Docker commands from the legacy scripts.
 */
@ApplicationScoped
public class DockerService {
  private final ClusterCatalog catalog;
  private final ProcessRunner processRunner;

  /**
   * Creates the Docker service.
   *
   * @param catalog configuration catalog
   * @param processRunner external process runner
   */
  public DockerService(ClusterCatalog catalog, ProcessRunner processRunner) {
    this.catalog = catalog;
    this.processRunner = processRunner;
  }

  /**
   * Dispatches a Docker action and its optional service/image selector.
   *
   * @param config active configuration
   * @param action pull, start, stop, or log and their aliases
   * @param selector optional image or container selector
   * @param dryRun whether to preview commands
   */
  public void execute(ClusterCtlConfig config, String action, String selector, boolean dryRun) {
    switch (action) {
      case "pull", "get" -> pull(config, selector, dryRun);
      case "start", "run" -> compose(config, List.of("up", "-d"), dryRun);
      case "stop", "kill" -> compose(config, List.of("down"), dryRun);
      case "log", "tail" -> logs(selector, dryRun);
      default -> throw new CliUsageException("Unknown Docker action: " + action);
    }
  }

  private void pull(ClusterCtlConfig config, String selector, boolean dryRun) {
    ClusterCtlConfig.Docker docker = config.local.docker;
    List<ClusterCtlConfig.Image> images = docker.images.stream()
        .filter(
            image -> selector == null || selector.isBlank()
                || image.url.equals(selector) || image.aliases.contains(selector)
        )
        .toList();
    if (images.isEmpty()) {
      throw new CliUsageException("Unknown Docker image or alias: " + selector);
    }
    if (docker.repoToken != null && !docker.repoToken.isBlank()) {
      requireSuccess(
          processRunner.run(
              new ProcessRequest(
                  List.of("docker", "login", docker.registry, "-u", docker.username, "--password-stdin"),
                  null,
                  docker.repoToken + "\n",
                  dryRun,
                  true
              )
          ),
          "Docker login"
      );
    }
    for (ClusterCtlConfig.Image image : images) {
      requireSuccess(
          processRunner.runAttached(
              new ProcessRequest(List.of("docker", "pull", image.url), null, null, dryRun, true)
          ),
          "Docker pull"
      );
    }
  }

  private void compose(ClusterCtlConfig config, List<String> arguments, boolean dryRun) {
    ClusterCtlConfig.Docker docker = config.local.docker;
    Path compose = catalog.path(config, docker.compose);
    List<String> command = new ArrayList<>(docker.composeCommand);
    command.addAll(List.of("-f", compose.toString()));
    command.addAll(arguments);
    requireSuccess(
        processRunner.run(new ProcessRequest(command, null, null, dryRun, true)),
        "Docker Compose"
    );
  }

  private void logs(String service, boolean dryRun) {
    if (service == null || service.isBlank()) {
      throw new CliUsageException("Docker log requires a container name or ID.");
    }
    requireSuccess(
        processRunner.run(new ProcessRequest(List.of("docker", "logs", service, "-f"), null, null, dryRun, true)),
        "Docker logs"
    );
  }

  private void requireSuccess(ProcessResult result, String operation) {
    if (result.unsuccessful()) {
      throw new CliException(operation + " failed with exit code " + result.exitCode() + ".");
    }
  }
}
