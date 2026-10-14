package com.dropchop.systems.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Runs external tools without routing user values through a shell.
 */
@ApplicationScoped
public class ProcessRunner {
  private ProcessResult run(ProcessRequest request, boolean attachedOutput) {
    if (request.command() == null || request.command().isEmpty()) {
      throw new CliException("Cannot execute an empty command.");
    }

    if (request.dryRun()) {
      System.out.println("$ " + render(request.command()));
      return new ProcessResult(0, "");
    }

    try {
      ProcessBuilder builder = new ProcessBuilder(request.command());

      // Important: keep stderr attached to our terminal.
      builder.redirectError(ProcessBuilder.Redirect.INHERIT);

      if (attachedOutput) {
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      }

      // If we're not explicitly providing stdin, let the child use our terminal.
      if (request.input() == null) {
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
      }

      if (request.directory() != null) {
        builder.directory(request.directory().toFile());
      }

      Process process = builder.start();

      if (request.input() != null) {
        try (var stdin = process.getOutputStream()) {
          stdin.write(request.input().getBytes(StandardCharsets.UTF_8));
        }
      }

      String output = attachedOutput ? "" : new String(
          process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      int exitCode = process.waitFor();

      if (request.printOutput() && !output.isEmpty()) {
        System.out.print(output);
      }

      return new ProcessResult(exitCode, output);

    } catch (IOException exception) {
      throw new CliException(
          "Unable to execute " + request.command().getFirst()
              + ": " + exception.getMessage(),
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new CliException(
          "Interrupted while executing "
              + request.command().getFirst() + ".",
          exception);
    }
  }

  /**
   * Executes or previews a process request, capturing standard output.
   *
   * @param request process definition
   * @return status and captured output
   */
  public ProcessResult run(ProcessRequest request) {
    return run(request, false);
  }

  /**
   * Executes with stdout and stderr attached directly to the invoking terminal.
   * This preserves live output and the child process's terminal color detection.
   *
   * @param request process definition; printOutput is unused in this mode
   * @return exit status and empty output because no output is captured
   */
  public ProcessResult runAttached(ProcessRequest request) {
    return run(request, true);
  }

  /**
   * Renders an argument list for diagnostics only.
   *
   * @param command command arguments
   * @return shell-readable representation
   */
  public String render(List<String> command) {
    return command.stream()
        .map(this::quote)
        .reduce((left, right) -> left + " " + right)
        .orElse("");
  }

  private String quote(String value) {
    if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) {
      return value;
    }
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
