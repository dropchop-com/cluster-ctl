package com.dropchop.systems.strategy;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Inputs required by a build strategy.
 *
 * @param config active configuration
 * @param cluster cluster name
 * @param environment target environment
 * @param projectSelector project name, alias, or all
 * @param dryRun whether to preview commands
 */
public record BuildRequest(
    ClusterCtlConfig config,
    String cluster,
    ClusterCtlConfig.Environment environment,
    String projectSelector,
    boolean dryRun) {
}
