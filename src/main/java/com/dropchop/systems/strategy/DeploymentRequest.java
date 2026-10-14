package com.dropchop.systems.strategy;

import java.nio.file.Path;
import java.util.List;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Inputs required by a deployment strategy.
 *
 * @param environment deployment environment
 * @param artifacts local artifact paths
 * @param dryRun whether to preview commands
 */
public record DeploymentRequest(
    ClusterCtlConfig.Environment environment,
    List<Path> artifacts,
    boolean dryRun) {
}
