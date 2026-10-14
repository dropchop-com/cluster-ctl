package com.dropchop.systems.strategy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Opens SSH tabs in the invoking Konsole window through its D-Bus interfaces.
 */
@ApplicationScoped
public class KonsoleTerminalStrategy implements TerminalStrategy {
  private static final String WINDOW_INTERFACE = "org.kde.konsole.Window";
  private static final String SESSION_INTERFACE = "org.kde.konsole.Session";

  private final ProcessRunner processRunner;
  private final Map<String, String> environment;
  private final Predicate<String> executableAvailable;

  private static boolean isExecutableAvailable(String command) {
    String searchPath = System.getenv("PATH");
    if (searchPath == null) {
      return false;
    }
    for (String directory : searchPath.split(Pattern.quote(File.pathSeparator), -1)) {
      Path executable = Path.of(directory).resolve(command);
      if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Creates the strategy using the invoking terminal's environment.
   *
   * @param processRunner external process runner
   */
  @Inject
  public KonsoleTerminalStrategy(ProcessRunner processRunner) {
    this(processRunner, System.getenv(), KonsoleTerminalStrategy::isExecutableAvailable);
  }

  KonsoleTerminalStrategy(
      ProcessRunner processRunner,
      Map<String, String> environment,
      Predicate<String> executableAvailable) {
    this.processRunner = processRunner;
    this.environment = environment;
    this.executableAvailable = executableAvailable;
  }

  private String terminalEnvironment(String name, boolean dryRun) {
    String value = environment.get(name);
    if (value != null && !value.isBlank()) {
      return value;
    }
    if (dryRun) {
      return "<" + name + ">";
    }
    throw new CliException("Konsole D-Bus requires " + name
        + ". Run cluster-ctl from a Konsole window.");
  }

  private String dbusTool(boolean dryRun) {
    if (executableAvailable.test("busctl")) {
      return "busctl";
    }
    if (executableAvailable.test("qdbus6")) {
      return "qdbus6";
    }
    if (dryRun) {
      return "busctl";
    }
    throw new CliException("Konsole D-Bus requires busctl or qdbus6 on PATH.");
  }

  private String call(String tool, String service, String objectPath, String dbusInterface, String method,
                      String signature, List<String> arguments, boolean dryRun) {
    List<String> command = new ArrayList<>(List.of(tool));
    if (tool.equals("busctl")) {
      command.addAll(List.of("--user", "call", service, objectPath, dbusInterface, method));
      if (signature != null) {
        command.add(signature);
      }
    } else {
      command.addAll(List.of(service, objectPath, method));
    }
    command.addAll(arguments);
    ProcessResult result = processRunner.run(new ProcessRequest(
        command, null, null, dryRun, false));
    TerminalSupport.requireSuccess(result, "Konsole D-Bus " + method);
    return result.output().strip();
  }

  private String sessionPath(String tool, String output) {
    String session = output;
    if (tool.equals("busctl") && output.startsWith("i ")) {
      session = output.substring(2).strip();
    }
    if (!session.matches("[1-9][0-9]*")) {
      throw new CliException("Konsole D-Bus newSession returned an invalid session ID: "
          + output);
    }
    return "/Sessions/" + session;
  }

  private void pause(long milliseconds, boolean dryRun) {
    if (dryRun) {
      return;
    }
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new CliException("Interrupted while opening Konsole tabs.", exception);
    }
  }

  /** {@inheritDoc} */
  @Override
  public String type() {
    return "konsole";
  }

  /**
   * Creates sessions, assigns their titles, and sends SSH commands followed by Enter.
   *
   * @param request resolved hosts and dry-run setting
   */
  @Override
  public void open(TerminalRequest request) {
    String service = terminalEnvironment("KONSOLE_DBUS_SERVICE", request.dryRun());
    String window = terminalEnvironment("KONSOLE_DBUS_WINDOW", request.dryRun());
    String tool = dbusTool(request.dryRun());
    int index = 0;
    for (ClusterCtlConfig.Host host : request.hosts()) {
      String output = call(
          tool, service, window, WINDOW_INTERFACE, "newSession", null,
          List.of(),
          request.dryRun()
      );
      String session = request.dryRun()
          ? "/Sessions/<new-session-" + (++index) + ">"
          : sessionPath(tool, output);
      call(
          tool, service, session, SESSION_INTERFACE, "setTitle", "is",
          List.of("1", TerminalSupport.title(host)),
          request.dryRun()
      );
      pause(100, request.dryRun());
      call(
          tool, service, session, SESSION_INTERFACE, "sendText", "s",
          List.of(processRunner.render(TerminalSupport.ssh(host, request.sshUser(host)))),
          request.dryRun()
      );
      call(
          tool, service, session, SESSION_INTERFACE, "sendText", "s",
          List.of("\n"),
          request.dryRun()
      );
      pause(500, request.dryRun());
    }
  }
}
