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
