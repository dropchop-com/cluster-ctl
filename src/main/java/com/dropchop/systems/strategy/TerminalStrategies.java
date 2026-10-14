package com.dropchop.systems.strategy;

import java.util.ArrayList;
import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Shared helpers for concrete terminal strategies.
 */
final class TerminalSupport {
  private TerminalSupport() {
  }

  static String title(ClusterCtlConfig.Host host) {
    return host.aliases.isEmpty() ? host.name : host.aliases.getFirst();
  }

  static List<String> ssh(ClusterCtlConfig.Host host, String sshUser) {
    return List.of("ssh", sshUser + "@" + host.name);
  }

  static void requireSuccess(ProcessResult result, String terminal) {
    if (result.unsuccessful()) {
      throw new CliException(terminal + " failed with exit code " + result.exitCode() + ".");
    }
  }
}

/**
 * Opens tabs through Kitty remote control.
 */
@ApplicationScoped
class KittyTerminalStrategy implements TerminalStrategy {
  private final ProcessRunner processRunner;

  KittyTerminalStrategy(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  /** {@inheritDoc} */
  @Override
  public String type() {
    return "kitty";
  }

  /** {@inheritDoc} */
  @Override
  public void open(TerminalRequest request) {
    for (ClusterCtlConfig.Host host : request.hosts()) {
      List<String> command = new ArrayList<>(
          List.of(
              "kitty", "@", "launch", "--type=tab", "--tab-title=" + TerminalSupport.title(host), "--keep-focus", "--"
          )
      );
      command.addAll(TerminalSupport.ssh(host, request.sshUser(host)));
      TerminalSupport.requireSuccess(
          processRunner.run(new ProcessRequest(command, null, null, request.dryRun(), true)),
          "Kitty"
      );
    }
  }
}

/**
 * Opens tabs in tmux, creating a detached session when outside tmux.
 */
@ApplicationScoped
class TmuxTerminalStrategy implements TerminalStrategy {
  private final ProcessRunner processRunner;

  TmuxTerminalStrategy(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  /** {@inheritDoc} */
  @Override
  public String type() {
    return "tmux";
  }

  /** {@inheritDoc} */
  @Override
  public void open(TerminalRequest request) {
    String session = ("cluster-ctl-" + request.cluster() + "-" + request.group()).replaceAll("[^A-Za-z0-9_-]", "-");
    boolean attached = System.getenv("TMUX") != null;
    for (int index = 0; index < request.hosts().size(); index++) {
      ClusterCtlConfig.Host host = request.hosts().get(index);
      List<String> command = new ArrayList<>();
      if (attached || index > 0) {
        command.addAll(List.of("tmux", "new-window"));
        if (!attached) {
          command.addAll(List.of("-t", session));
        }
        command.addAll(List.of("-n", TerminalSupport.title(host)));
      } else {
        command.addAll(List.of("tmux", "new-session", "-d", "-s", session, "-n", TerminalSupport.title(host)));
      }
      command.addAll(TerminalSupport.ssh(host, request.sshUser(host)));
      TerminalSupport.requireSuccess(
          processRunner.run(new ProcessRequest(command, null, null, request.dryRun(), true)),
          "tmux"
      );
    }
    if (!attached) {
      System.out.println("Attach with: tmux attach -t " + session);
    }
  }
}

/**
 * Opens tabs in iTerm2 through AppleScript.
 */
@ApplicationScoped
class ItermTerminalStrategy implements TerminalStrategy {
  private final ProcessRunner processRunner;

  ItermTerminalStrategy(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  /** {@inheritDoc} */
  @Override
  public String type() {
    return "iterm";
  }

  /** {@inheritDoc} */
  @Override
  public void open(TerminalRequest request) {
    for (ClusterCtlConfig.Host host : request.hosts()) {
      String ssh = processRunner.render(TerminalSupport.ssh(host, request.sshUser(host)));
      String escaped = ssh.replace("\\", "\\\\").replace("\"", "\\\"");
      String script = "tell application \"iTerm\" to tell current window to create tab "
          + "with default profile command \"" + escaped + "\"";
      TerminalSupport.requireSuccess(
          processRunner.run(new ProcessRequest(List.of("osascript", "-e", script), null, null, request.dryRun(), true)),
          "iTerm"
      );
    }
  }
}
