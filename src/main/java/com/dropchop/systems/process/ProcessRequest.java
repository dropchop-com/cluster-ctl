package com.dropchop.systems.process;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable external process invocation.
 *
 * @param command argument-safe command line
 * @param directory optional working directory
 * @param input optional standard input
 * @param dryRun whether to print without executing
 * @param printOutput whether captured output is copied to the console
 */
public record ProcessRequest(
    List<String> command,
    Path directory,
    String input,
    boolean dryRun,
    boolean printOutput) {
}
