package com.dropchop.systems.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dropchop.systems.config.ClusterCtlConfig;

/**
 * Verifies raw execution formatting before terminal-test ANSI sanitization.
 */
class ConsoleOutputTest {
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private PrintStream originalOutput;

  @BeforeEach
  void captureOutput() {
    originalOutput = System.out;
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreOutput() {
    System.setOut(originalOutput);
  }

  @Test
  void printsTimestampColorsHostCommandAndDelimiters() {
    ClusterCtlConfig.Host host = new ClusterCtlConfig.Host();
    host.name = "alpha-node-1.example.test";
    host.sshUser = "test-admin";
    ConsoleOutput formatter = new ConsoleOutput();

    formatter.execHeader(host, "uptime -p");
    formatter.execFooter();

    String rendered = output.toString(StandardCharsets.UTF_8);
    assertTrue(rendered.contains("\u001B[93m"), rendered);
    assertTrue(rendered.contains("\u001B[96m"), rendered);
    assertTrue(rendered.contains("Executing \u001B[92muptime -p"), rendered);
    assertTrue(rendered.contains("on \u001B[95mtest-admin@alpha-node-1.example.test"), rendered);
    assertTrue(rendered.contains(" output "), rendered);
    assertTrue(rendered.matches("(?s).*\\[\\d{4}-\\d{2}-\\d{2} "
        + "\\d{2}:\\d{2}:\\d{2} (Z|[+-]\\d{2}:\\d{2})].*"), rendered);
  }
}
