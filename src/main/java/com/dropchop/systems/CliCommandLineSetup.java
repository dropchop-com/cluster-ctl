package com.dropchop.systems;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.cli.CliUsageException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.config.ConfigException;
import com.dropchop.systems.config.ConfigLoader;
import com.dropchop.systems.service.HelpService;

import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * Integrates configuration-aware help with Picocli without invoking command operations.
 */
@ApplicationScoped
public class CliCommandLineSetup {
  private final ConfigLoader configLoader;
  private final HelpService helpService;

  /**
   * Creates the help-aware command-line setup.
   *
   * @param configLoader external configuration loader
   * @param helpService command help formatter
   */
  public CliCommandLineSetup(ConfigLoader configLoader, HelpService helpService) {
    this.configLoader = configLoader;
    this.helpService = helpService;
  }

  private int printHelp(CommandLine root, CommandLine command) {
    if (command.getCommandName().equals("self-upgrade")) {
      helpService.print(null, root, command);
      return 0;
    }
    ClusterCtlCommand application = root.getCommand();
    ClusterCtlConfig config = null;
    try {
      if (configLoader.isConfigured(application.configPath)) {
        config = configLoader.load(application.configPath);
      }
    } catch (ConfigException exception) {
      helpService.print(null, root, command);
      command.getErr().println("Error: " + exception.getMessage());
      return 2;
    }
    try {
      helpService.print(config, root, command);
      return 0;
    } catch (CliException exception) {
      command.getErr().println("Error: " + exception.getMessage());
      return 2;
    }
  }

  private int execute(ParseResult result, CommandLine root, CommandLine.IExecutionStrategy delegate) {
    for (CommandLine command : result.asCommandLineList()) {
      if (command.isUsageHelpRequested()) {
        return printHelp(root, command);
      }
    }
    if (!result.hasSubcommand() && !root.isVersionHelpRequested()) {
      return printHelp(root, root);
    }
    return delegate.execute(result);
  }

  private int invalidArguments(CommandLine root, CommandLine command, String message) {
    command.getErr().println("Error: " + message);
    command.getErr().flush();
    printHelp(root, command);
    return command.getCommandSpec().exitCodeOnInvalidInput();
  }

  /**
   * Produces the CDI-created command tree with help flags on every command.
   *
   * @param factory Quarkus command factory preserving CDI-managed command implementations
   * @return configured command line
   */
  @Produces
  @Singleton
  public CommandLine commandLine(PicocliCommandLineFactory factory) {
    CommandLine root = factory.create();
    root.setCommandName(helpService.invocationName(root.getCommandName()));
    for (CommandLine command : root.getSubcommands().values().stream().distinct().toList()) {
      if (command.getCommandSpec().findOption("--help") == null) {
        command.getCommandSpec().addOption(OptionSpec.builder("-h", "--help")
            .type(boolean.class).usageHelp(true).description("Show this help message and exit.").build());
      }
    }
    CommandLine.IExecutionStrategy delegate = root.getExecutionStrategy();
    root.setExecutionStrategy(result -> execute(result, root, delegate));
    root.setParameterExceptionHandler((exception, arguments) ->
        invalidArguments(root, exception.getCommandLine(), exception.getMessage()));
    CommandLine.IExecutionExceptionHandler exceptionHandler = root.getExecutionExceptionHandler();
    root.setExecutionExceptionHandler((exception, command, result) -> {
      if (exception instanceof CliUsageException) {
        return invalidArguments(root, command, exception.getMessage());
      }
      return exceptionHandler.handleExecutionException(exception, command, result);
    });
    return root;
  }
}
