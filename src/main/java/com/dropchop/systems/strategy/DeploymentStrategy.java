package com.dropchop.systems.strategy;

/**
 * Replaceable deployment implementation selected by environment deploy type.
 */
public interface DeploymentStrategy {
  /**
   * Returns the configuration discriminator handled by this strategy.
   *
   * @return strategy type
   */
  String type();

  /**
   * Deploys local artifacts.
   *
   * @param request deployment inputs
   */
  void deploy(DeploymentRequest request);
}
