package com.dropchop.systems.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Handles destructive-operation confirmation consistently.
 */
@ApplicationScoped
public class PromptService {
  private final BufferedReader input = new BufferedReader(
      new InputStreamReader(System.in, StandardCharsets.UTF_8));

  /**
   * Confirms an action or accepts the global assume-yes flag.
   *
   * @param question user-facing prompt
   * @param assumedYes whether confirmation was pre-authorized
   * @return true when the action should proceed
   */
  public boolean confirm(String question, boolean assumedYes) {
    if (assumedYes) {
      return true;
    }
    System.out.print(question + " [y/N] ");
    try {
      String answer = input.readLine();
      return answer != null && answer.matches("(?i)y(es)?");
    } catch (IOException exception) {
      throw new CliException("Unable to read confirmation: " + exception.getMessage(), exception);
    }
  }
}
