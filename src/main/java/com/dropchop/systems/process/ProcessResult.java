package com.dropchop.systems.process;

/**
 * Completed process status and captured standard output.
 *
 * @param exitCode process exit status
 * @param output captured stdout, or empty when output is attached to the terminal
 */
public record ProcessResult(int exitCode, String output) {
  /**
   * Reports whether the process completed successfully.
   *
   * @return true for exit status zero
   */
  public boolean unsuccessful() {
    return exitCode != 0;
  }
}
