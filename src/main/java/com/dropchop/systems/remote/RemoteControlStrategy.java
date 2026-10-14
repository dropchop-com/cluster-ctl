package com.dropchop.systems.remote;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Replaceable remote host control selected by a cluster's control type.
 */
public interface RemoteControlStrategy {
  /**
   * Returns the configuration discriminator handled by this strategy.
   *
   * @return strategy type
   */
  String type();

  /**
   * Executes a command on one host.
   *
   * @param host target host
   * @param command remote command
   * @param dryRun whether to preview execution
   */
  void execute(ClusterCtlConfig.Host host, String command, boolean dryRun);

  /**
   * Lists authorized keys for a target user.
   *
   * @param host target host
   * @param user target account
   * @param dryRun whether to preview execution
   */
  void listKeys(ClusterCtlConfig.Host host, String user, boolean dryRun);

  /**
   * Adds or updates an authorized key for a target user.
   *
   * @param host target host
   * @param user target account
   * @param publicKey validated public key
   * @param verifyRequired whether the verify-required option is required
   * @param dryRun whether to preview execution
   */
  void addKey(ClusterCtlConfig.Host host, String user, String publicKey, boolean verifyRequired, boolean dryRun);

  /**
   * Finds fixed-string matches in a target user's authorized keys.
   *
   * @param host target host
   * @param user target account
   * @param matchingString fixed search value
   * @param dryRun whether to preview execution
   * @return matching numbered lines
   */
  String findKeys(ClusterCtlConfig.Host host, String user, String matchingString, boolean dryRun);

  /**
   * Removes fixed-string matches from a target user's authorized keys.
   *
   * @param host target host
   * @param user target account
   * @param matchingString fixed search value
   * @param dryRun whether to preview execution
   */
  void removeKeys(ClusterCtlConfig.Host host, String user, String matchingString, boolean dryRun);
}
