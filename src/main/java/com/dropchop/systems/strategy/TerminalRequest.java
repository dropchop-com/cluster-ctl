package com.dropchop.systems.strategy;

import java.util.List;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Inputs required by a terminal tab strategy.
 *
 * @param cluster cluster name
 * @param group host group selector
 * @param hosts resolved hosts
 * @param dryRun whether to preview commands
 * @param sshUser optional SSH login override for every selected host
 */
public record TerminalRequest(
    String cluster,
    String group,
    List<ClusterCtlConfig.Host> hosts,
    boolean dryRun,
    String sshUser) {
  /**
   * Creates a request using each host's configured SSH login.
   *
   * @param cluster cluster name
   * @param group host group selector
   * @param hosts resolved hosts
   * @param dryRun whether to preview commands
   */
  public TerminalRequest(String cluster, String group, List<ClusterCtlConfig.Host> hosts, boolean dryRun) {
    this(cluster, group, hosts, dryRun, null);
  }

  /**
   * Returns the override or the selected host's configured login.
   *
   * @param host selected host
   * @return effective SSH user
   */
  public String sshUser(ClusterCtlConfig.Host host) {
    return sshUser == null ? host.sshUser : sshUser;
  }
}
