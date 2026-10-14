package com.dropchop.systems.strategy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Deploys artifacts with SCP and optional adjacent SHA-1 checksum files.
 */
@ApplicationScoped
public class ScpDeploymentStrategy implements DeploymentStrategy {
  private final ProcessRunner processRunner;

  /**
   * Creates an SCP deployment strategy.
   *
   * @param processRunner external process runner
   */
  public ScpDeploymentStrategy(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String type() {
    return "scp";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void deploy(DeploymentRequest request) {
    ClusterCtlConfig.Deploy deploy = request.environment().deploy;
    String target = target(deploy);
    for (Path artifact : request.artifacts()) {
      if ("sha1".equalsIgnoreCase(deploy.checksum)) {
        Path checksum = artifact.resolveSibling(artifact.getFileName() + ".sha1");
        createChecksum(artifact, checksum, request.dryRun());
        copy(checksum, target, request.dryRun());
      }
      copy(artifact, target, request.dryRun());
    }
  }

  private String target(ClusterCtlConfig.Deploy deploy) {
    if (deploy.host == null || deploy.host.isBlank()
        || deploy.path == null || deploy.path.isBlank()) {
      throw new CliException("SCP deployment requires deploy.host and deploy.path.");
    }
    String login = deploy.user == null || deploy.user.isBlank()
        ? deploy.host
        : deploy.user + "@" + deploy.host;
    return login + ":" + deploy.path;
  }

  private void createChecksum(Path artifact, Path checksum, boolean dryRun) {
    if (dryRun) {
      System.out.println("$ sha1sum " + artifact + " > " + checksum);
      return;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      try (InputStream input = new DigestInputStream(
          Files.newInputStream(artifact), digest)) {
        input.transferTo(OutputStream.nullOutputStream());
      }
      byte[] hash = digest.digest();
      String line = HexFormat.of().formatHex(hash) + "  " + artifact.getFileName()
          + System.lineSeparator();
      Files.writeString(checksum, line);
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new CliException("Unable to create checksum for " + artifact
          + ": " + exception.getMessage(), exception);
    }
  }

  private void copy(Path artifact, String target, boolean dryRun) {
    ProcessResult result = processRunner.run(
        new ProcessRequest(List.of("scp", artifact.toString(), target), null, null, dryRun, true)
    );
    if (result.unsuccessful()) {
      throw new CliException("SCP failed for " + artifact
          + " with exit code " + result.exitCode() + ".");
    }
  }
}
