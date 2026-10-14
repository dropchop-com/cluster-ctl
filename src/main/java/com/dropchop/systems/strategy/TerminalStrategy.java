package com.dropchop.systems.strategy;

/**
 * Replaceable terminal tab implementation selected by local terminal type.
 */
public interface TerminalStrategy {
  /**
   * Returns the configuration discriminator handled by this strategy.
   *
   * @return strategy type
   */
  String type();

  /**
   * Opens one SSH tab for each resolved host.
   *
   * @param request terminal inputs
   */
  void open(TerminalRequest request);
}
