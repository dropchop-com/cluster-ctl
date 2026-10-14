package com.dropchop.systems.strategy;

/**
 * Replaceable local build implementation selected by configuration type.
 */
public interface BuildStrategy {
  /**
   * Returns the configuration discriminator handled by this strategy.
   *
   * @return strategy type
   */
  String type();

  /**
   * Builds the selected projects.
   *
   * @param request build inputs
   */
  void build(BuildRequest request);
}
