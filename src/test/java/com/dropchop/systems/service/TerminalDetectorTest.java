package com.dropchop.systems.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.cli.CliException;

/**
 * Verifies terminal markers, explicit overrides, and headless-session behavior.
 */
class TerminalDetectorTest {
  private static final Map<String, String> KONSOLE = Map.of(
      "KONSOLE_DBUS_SERVICE", ":1.42", "KONSOLE_DBUS_WINDOW", "/Windows/2");

  @Test
  void forcesOverridesEvenWhenAnotherTerminalIsDetected() {
    for (String terminal : List.of("konsole", "kitty", "tmux", "iterm", "custom")) {
      Map<String, String> environment = new HashMap<>(KONSOLE);
      environment.put("KITTY_WINDOW_ID", "7");
      environment.put("TMUX", "/tmp/tmux-1000/default,123,0");
      environment.put(TerminalDetector.TERMINAL_ENV, " " + terminal + " ");

      assertEquals(terminal, new TerminalDetector(environment).resolve());
    }
  }

  @Test
  void detectsTmuxFromItsSessionMarker() {
    assertEquals("tmux", new TerminalDetector(
        Map.of("TMUX", "/tmp/tmux-1000/default,123,0")).resolve());
  }

  @Test
  void prioritizesTmuxOverTheEnclosingTerminal() {
    Map<String, String> environment = new HashMap<>(KONSOLE);
    environment.put("KITTY_WINDOW_ID", "7");
    environment.put("TMUX", "/tmp/tmux-1000/default,123,0");

    assertEquals("tmux", new TerminalDetector(environment).resolve());
  }

  @Test
  void detectsKittyFromItsSessionMarkers() {
    for (Map<String, String> environment : List.of(
        Map.of("KITTY_WINDOW_ID", "7"), Map.of("KITTY_PID", "123"),
        Map.of("KITTY_LISTEN_ON", "unix:/tmp/kitty-test"),
        Map.of("TERM", "xterm-kitty"), Map.of("TERM_PROGRAM", "kitty"))) {
      assertEquals("kitty", new TerminalDetector(environment).resolve());
    }
  }

  @Test
  void detectsKonsoleFromItsDbusWindow() {
    assertEquals("konsole", new TerminalDetector(KONSOLE).resolve());
  }

  @Test
  void prioritizesKittyMarkersWhenBothTerminalsHaveMarkers() {
    Map<String, String> environment = new HashMap<>(KONSOLE);
    environment.put("KITTY_WINDOW_ID", "7");

    assertEquals("kitty", new TerminalDetector(environment).resolve());
  }

  @Test
  void treatsEmptyOrWhitespaceOverridesAsAutomaticDetection() {
    for (String value : List.of("", " ", "\t")) {
      Map<String, String> environment = new HashMap<>(KONSOLE);
      environment.put(TerminalDetector.TERMINAL_ENV, value);

      assertEquals("konsole", new TerminalDetector(environment).resolve());
    }
  }

  @Test
  void requiresAnOverrideWhenNoUsableTerminalIsDetected() {
    for (Map<String, String> environment : List.of(
        Map.<String, String>of(), Map.of("TERM", "xterm-256color"),
        Map.of("KONSOLE_DBUS_SERVICE", ":1.42"),
        Map.of("KONSOLE_DBUS_WINDOW", "/Windows/2"),
        Map.of("TMUX", ""), Map.of("TMUX", " "),
        Map.of("KITTY_PID", " "), Map.of("TERM_PROGRAM", "unknown"))) {
      CliException exception = assertThrows(CliException.class,
          () -> new TerminalDetector(environment).resolve());
      assertTrue(exception.getMessage().contains(TerminalDetector.TERMINAL_ENV));
    }
    assertEquals("tmux", new TerminalDetector(
        Map.of(TerminalDetector.TERMINAL_ENV, "tmux")).resolve());
  }
}
