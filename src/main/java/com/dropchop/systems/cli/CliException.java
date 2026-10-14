package com.dropchop.systems.cli;

/**
 * User-facing command failure without an implementation stack trace.
 */
public class CliException extends RuntimeException {
  /**
   * Creates a command failure.
   *
   * @param message error description
   */
  public CliException(String message) {
    super(message);
  }

  /**
   * Creates a command failure with an underlying cause.
   *
   * @param message error description
   * @param cause underlying failure
   */
  public CliException(String message, Throwable cause) {
    super(message, cause);
  }
}
