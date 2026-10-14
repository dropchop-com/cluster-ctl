package com.dropchop.systems.service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.dropchop.systems.config.ClusterCtlConfig;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Formats human-readable command execution boundaries with ANSI colors.
 */
@ApplicationScoped
public class ConsoleOutput {
  private static final String CYAN = "\u001B[96m";
  private static final String GREEN = "\u001B[92m";
  private static final String MAGENTA = "\u001B[95m";
  private static final String YELLOW = "\u001B[93m";
  private static final String RESET = "\u001B[0m";
  private static final String BLOCK_DELIMITER = "=".repeat(112);
  private static final String OUTPUT_DELIMITER = "-".repeat(48) + " output " + "-".repeat(56);
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

  /**
   * Prints the timestamped header immediately before remote command output.
   *
   * @param host target host
   * @param command remote command
   */
  public void execHeader(ClusterCtlConfig.Host host, String command) {
    String timestamp = TIMESTAMP.format(ZonedDateTime.now());
    String displayCommand = command.replace('\r', ' ').replace('\n', ' ');
    System.out.println(YELLOW + BLOCK_DELIMITER + RESET);
    System.out.println(CYAN + "[" + timestamp + "]" + RESET
        + " Executing " + GREEN + displayCommand + RESET
        + " on " + MAGENTA + host.sshUser + "@" + host.name + RESET);
    System.out.println(YELLOW + OUTPUT_DELIMITER + RESET);
  }

  /**
   * Prints a closing delimiter after one host's remote output.
   */
  public void execFooter() {
    System.out.println(YELLOW + BLOCK_DELIMITER + RESET);
  }
}
