package com.dropchop.systems.service;

import java.util.Map;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Selects an explicit terminal override or detects the invoking tmux, Kitty, or Konsole session.
 */
@ApplicationScoped
public class TerminalDetector {
  public static final String TERMINAL_ENV = "CLUSTER_CTL_LOCAL_TERMINAL";

  private final Map<String, String> environment;

  /**
   * Creates a detector using the invoking process's environment.
   */
  @Inject
  public TerminalDetector() {
    this(System.getenv());
  }

  TerminalDetector(Map<String, String> environment) {
    this.environment = environment;
  }

  private boolean isSet(String name) {
    String value = environment.get(name);
    return value != null && !value.isBlank();
  }

  /**
   * Resolves the terminal, giving the environment override precedence over detection.
   *
   * @return terminal strategy discriminator
   * @throws CliException when neither a terminal override nor a supported session is found
   */
  public String resolve() {
    if (isSet(TERMINAL_ENV)) {
      return environment.get(TERMINAL_ENV).strip();
    }
    if (isSet("TMUX")) {
      return "tmux";
    }
    if (isSet("KITTY_WINDOW_ID") || isSet("KITTY_PID") || isSet("KITTY_LISTEN_ON")
        || "xterm-kitty".equals(environment.get("TERM"))
        || "kitty".equalsIgnoreCase(environment.get("TERM_PROGRAM"))) {
      return "kitty";
    }
    if (isSet("KONSOLE_DBUS_SERVICE") && isSet("KONSOLE_DBUS_WINDOW")) {
      return "konsole";
    }
    throw new CliException(
        "Cannot detect tmux, Kitty, or Konsole. Set " + TERMINAL_ENV
            + " to select a terminal strategy (konsole, kitty, tmux, or iterm)."
    );
  }
}
