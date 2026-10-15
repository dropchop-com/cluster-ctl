package com.dropchop.systems.cli;

/**
 * Marks invalid command arguments so the CLI can show full command help without a stack trace.
 */
public class CliUsageException extends CliException {
  /**
   * Creates an argument validation failure.
   *
   * @param message explanation of the incorrect input
   */
  public CliUsageException(String message) {
    super(message);
  }

  /**
   * Creates an argument validation failure with an underlying cause.
   *
   * @param message explanation of the incorrect input
   * @param cause original validation failure
   */
  public CliUsageException(String message, Throwable cause) {
    super(message, cause);
  }
}
