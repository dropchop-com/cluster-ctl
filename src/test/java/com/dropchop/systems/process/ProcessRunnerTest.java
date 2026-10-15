package com.dropchop.systems.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Verifies live process output, ANSI preservation, and captured-output compatibility.
 */
class ProcessRunnerTest {
  private static final String READY = "\u001B[32mready\u001B[0m";
  private static final String ORANGE = "\u001B[38;5;208m";
  private static final String RESET = "\u001B[0m";
  private static final String ERROR_PROMPT = "warning: caf\u00e9> ";

  private static List<String> javaCommand(Class<?> entryPoint) {
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), entryPoint.getName());
  }

  @Test
  void streamsAnsiBeforeExitAndPreservesSeparateStderrAndExitStatus() throws Exception {
    Process process = new ProcessBuilder(javaCommand(AttachedInvocation.class)).start();
    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var stdout = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8))) {
      try {
        // The child waits for stdin, so buffering until exit would time out here.
        String firstLine = readers.submit(stdout::readLine).get(10, TimeUnit.SECONDS);
        assertEquals(READY, firstLine);
        assertTrue(process.isAlive());
        process.getOutputStream().write("continue\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(7, process.exitValue());
        assertEquals("done", stdout.readLine());
        assertNull(stdout.readLine());
        assertEquals("diagnostic\n", new String(
            process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
      } finally {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }

  @Test
  void streamsOrangeStderrWithoutWaitingForANewlineAndKeepsStdoutUnchanged() throws Exception {
    Process process = new ProcessBuilder(javaCommand(OrangeErrorInvocation.class)).start();
    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var stderr = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
      try {
        String prompt = readers.submit(() -> {
          StringBuilder output = new StringBuilder();
          int character;
          while ((character = stderr.read()) != -1) {
            output.append((char) character);
            String text = output.toString();
            if (text.replace(ORANGE, "").replace(RESET, "").equals(ERROR_PROMPT)
                && text.endsWith(RESET)) {
              return text;
            }
          }
          return output.toString();
        }).get(10, TimeUnit.SECONDS);

        assertTrue(prompt.startsWith(ORANGE), prompt);
        assertTrue(prompt.endsWith(RESET), prompt);
        assertEquals(ERROR_PROMPT, prompt.replace(ORANGE, "").replace(RESET, ""));
        assertTrue(process.isAlive());
        process.getOutputStream().write("continue\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(7, process.exitValue());
        StringBuilder remaining = new StringBuilder();
        int character;
        while ((character = stderr.read()) != -1) {
          remaining.append((char) character);
        }
        assertEquals("finished\n", remaining.toString().replace(ORANGE, "").replace(RESET, ""));
        assertTrue(remaining.toString().endsWith(RESET), remaining.toString());
        assertEquals(READY + "\ndone\n", new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } finally {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }

  @Test
  void orangeStderrDryRunDoesNotLaunchTheProgram() {
    ProcessResult result = new ProcessRunner().runWithOrangeStderr(new ProcessRequest(
        List.of("cluster-ctl-nonexistent-test-program"), null, null, true, true));

    assertEquals(0, result.exitCode());
    assertEquals("", result.output());
  }

  @Test
  void captureModeStillReturnsCompleteAnsiOutput() {
    ProcessResult result = new ProcessRunner().run(new ProcessRequest(
        javaCommand(OutputChild.class), null, "continue\n", false, false));

    assertEquals(7, result.exitCode());
    assertEquals(READY + "\ndone\n", result.output());
  }

  @Test
  void attachedDryRunDoesNotLaunchTheProgram() {
    ProcessResult result = new ProcessRunner().runAttached(new ProcessRequest(
        List.of("cluster-ctl-nonexistent-test-program"), null, null, true, true));

    assertEquals(0, result.exitCode());
    assertEquals("", result.output());
  }

  /**
   * Launches the runner in a separate JVM so inherited file descriptors are observable.
   */
  public static class AttachedInvocation {
    /**
     * Runs the child with inherited stdout, stderr, and stdin.
     *
     * @param arguments unused
     */
    public static void main(String[] arguments) {
      ProcessResult result = new ProcessRunner().runAttached(new ProcessRequest(
          javaCommand(OutputChild.class), null, null, false, true));
      if (!result.output().isEmpty()) {
        throw new IllegalStateException("Attached output must not be captured.");
      }
      System.exit(result.exitCode());
    }
  }

  /**
   * Launches the colored-stderr runner without changing inherited stdin.
   */
  public static class OrangeErrorInvocation {
    /**
     * Runs a child and checks that coloring does not modify captured stdout.
     *
     * @param arguments unused
     */
    public static void main(String[] arguments) {
      ProcessResult result = new ProcessRunner().runWithOrangeStderr(new ProcessRequest(
          javaCommand(OrangeErrorChild.class), null, null, false, true));
      if (!result.output().equals(READY + "\ndone\n")) {
        throw new IllegalStateException("Captured stdout must remain unchanged.");
      }
      System.exit(result.exitCode());
    }
  }

  /**
   * Emits a UTF-8 stderr prompt without a newline, then waits for input.
   */
  public static class OrangeErrorChild {
    /**
     * Produces independent stdout and stderr before and after the input gate.
     *
     * @param arguments unused
     * @throws Exception when stdin cannot be read
     */
    public static void main(String[] arguments) throws Exception {
      System.out.print(READY + "\n");
      System.out.flush();
      System.err.print(ERROR_PROMPT);
      System.err.flush();
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      System.err.print("finished\n");
      System.err.flush();
      System.out.print("done\n");
      System.out.flush();
      System.exit(7);
    }
  }

  /**
   * Emits colored output and waits for input before finishing with a nonzero status.
   */
  public static class OutputChild {
    /**
     * Produces stdout and stderr with deterministic line endings.
     *
     * @param arguments unused
     * @throws Exception when stdin cannot be read
     */
    public static void main(String[] arguments) throws Exception {
      System.out.print(READY + "\n");
      System.out.flush();
      System.err.print("diagnostic\n");
      System.err.flush();
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      System.out.print("done\n");
      System.out.flush();
      System.exit(7);
    }
  }
}
