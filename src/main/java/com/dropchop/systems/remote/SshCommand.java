package com.dropchop.systems.remote;

import java.util.ArrayList;
import java.util.List;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Builds SSH invocations shared by remote control and terminal strategies.
 */
public final class SshCommand {
  private SshCommand() {
  }

  /**
   * Creates an SSH command using the host's optional ProxyJump route.
   *
   * @param host destination host and optional comma-separated jump hosts
   * @param sshUser destination login, which may override the configured user
   * @param arguments optional arguments after the destination
   * @return argument-safe command line
   */
  public static List<String> command(ClusterCtlConfig.Host host, String sshUser, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("ssh");
    if (host.sshProxy != null && !host.sshProxy.isBlank()) {
      command.addAll(List.of("-J", host.sshProxy.strip()));
    }
    command.add(sshUser + "@" + host.name);
    command.addAll(List.of(arguments));
    return List.copyOf(command);
  }
}
