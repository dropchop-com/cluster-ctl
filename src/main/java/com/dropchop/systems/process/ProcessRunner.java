package com.dropchop.systems.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Runs external tools without routing user values through a shell.
 */
@ApplicationScoped
public class ProcessRunner {
  private static final String ORANGE = "\u001B[38;5;208m";
  private static final String RESET = "\u001B[0m";

  private void forwardOrangeStderr(InputStream stderr) throws IOException {
    try (var reader = new InputStreamReader(stderr, StandardCharsets.UTF_8)) {
      char[] buffer = new char[1024];
      int length;
      // Read chunks rather than lines so prompts and progress appear immediately.
      while ((length = reader.read(buffer)) != -1) {
        System.err.print(ORANGE + new String(buffer, 0, length) + RESET);
        System.err.flush();
      }
    }
  }

  private ProcessResult run(ProcessRequest request, boolean attachedOutput, boolean orangeStderr) {
    if (request.command() == null || request.command().isEmpty()) {
      throw new CliException("Cannot execute an empty command.");
    }

    if (request.dryRun()) {
      System.out.println("$ " + render(request.command()));
      return new ProcessResult(0, "");
    }

    try {
      ProcessBuilder builder = new ProcessBuilder(request.command());

      // Only colored stderr needs a pipe; other tools retain terminal detection.
      builder.redirectError(orangeStderr
          ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.INHERIT);

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

      try (var errors = orangeStderr ? Executors.newVirtualThreadPerTaskExecutor() : null) {
        // Drain stderr concurrently with stdout and stdin to avoid pipe deadlocks.
        var errorOutput = errors == null ? null : errors.submit(() -> {
          forwardOrangeStderr(process.getErrorStream());
          return null;
        });
        try {
          if (request.input() != null) {
            try (var stdin = process.getOutputStream()) {
              stdin.write(request.input().getBytes(StandardCharsets.UTF_8));
            }
          }

          String output = attachedOutput ? "" : new String(
              process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

          int exitCode = process.waitFor();
          if (errorOutput != null) {
            errorOutput.get();
          }

          if (request.printOutput() && !output.isEmpty()) {
            System.out.print(output);
          }

          return new ProcessResult(exitCode, output);
        } finally {
          if (process.isAlive()) {
            process.destroyForcibly();
          }
        }
      }

    } catch (IOException | ExecutionException exception) {
      Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
      throw new CliException(
          "Unable to execute " + request.command().getFirst() + ": " + cause.getMessage(), cause
      );
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new CliException("Interrupted while executing " + request.command().getFirst() + ".", exception);
    }
  }

  /**
   * Executes or previews a process request, capturing standard output.
   *
   * @param request process definition
   * @return status and captured output
   */
  public ProcessResult run(ProcessRequest request) {
    return run(request, false, false);
  }

  /**
   * Executes with live orange stderr while preserving standard output capture.
   * Color resets after each chunk; stderr is never mixed into captured stdout.
   *
   * @param request process definition
   * @return exit status and unmodified captured stdout
   */
  public ProcessResult runWithOrangeStderr(ProcessRequest request) {
    return run(request, false, true);
  }

  /**
   * Executes with stdout and stderr attached directly to the invoking terminal.
   * This preserves live output and the child process's terminal color detection.
   *
   * @param request process definition; printOutput is unused in this mode
   * @return exit status and empty output because no output is captured
   */
  public ProcessResult runAttached(ProcessRequest request) {
    return run(request, true, false);
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
