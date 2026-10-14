package com.dropchop.systems.config;

/**
 * Reports a configuration error suitable for display by the CLI.
 */
public class ConfigException extends RuntimeException {
  /**
   * Creates an error with a user-facing message.
   *
   * @param message error description
   */
  public ConfigException(String message) {
    super(message);
  }

  /**
   * Creates an error with its underlying cause.
   *
   * @param message error description
   * @param cause underlying failure
   */
  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
