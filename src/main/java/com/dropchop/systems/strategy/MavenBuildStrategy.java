package com.dropchop.systems.strategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCatalog;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Builds configured projects with Maven and live terminal output.
 */
@ApplicationScoped
public class MavenBuildStrategy implements BuildStrategy {
  private final ClusterCatalog catalog;
  private final ProcessRunner processRunner;

  /**
   * Creates the Maven strategy.
   *
   * @param catalog configuration catalog
   * @param processRunner external process runner
   */
  public MavenBuildStrategy(ClusterCatalog catalog, ProcessRunner processRunner) {
    this.catalog = catalog;
    this.processRunner = processRunner;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String type() {
    return "maven";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void build(BuildRequest request) {
    List<ClusterCtlConfig.BuildProject> projects = selectProjects(request);
    for (ClusterCtlConfig.BuildProject project : projects) {
      Path directory = catalog.path(request.config(), project.path);
      if (!request.dryRun() && !Files.isDirectory(directory)) {
        throw new CliException("Build project directory does not exist: " + directory);
      }
      List<String> command = new ArrayList<>();
      command.add(request.config().local.build.command);
      for (String argument : project.arguments) {
        command.add(expand(argument, request));
      }
      System.out.println("Building " + project.name + " in " + directory);
      ProcessResult result = processRunner.runAttached(new ProcessRequest(
          command, directory, null, request.dryRun(), true));
      if (result.unsuccessful()) {
        throw new CliException("Maven build failed for " + project.name
            + " with exit code " + result.exitCode() + ".");
      }
    }
  }

  private List<ClusterCtlConfig.BuildProject> selectProjects(BuildRequest request) {
    String selector = request.projectSelector();
    List<ClusterCtlConfig.BuildProject> projects = request.config().local.build.projects;
    if (selector == null || selector.isBlank() || selector.equals("all")) {
      if (projects.isEmpty()) {
        throw new CliException("No build projects are configured.");
      }
      return projects;
    }
    List<ClusterCtlConfig.BuildProject> selected = projects.stream()
        .filter(project -> project.name.equals(selector) || project.aliases.contains(selector))
        .toList();
    if (selected.isEmpty()) {
      throw new CliException("Unknown build project or alias: " + selector);
    }
    return selected;
  }

  private String expand(String value, BuildRequest request) {
    return value
        .replace("{cluster}", request.cluster())
        .replace("{environment}", request.environment().name)
        .replace("{build-environment}", request.environment().buildEnvironment);
  }
}
