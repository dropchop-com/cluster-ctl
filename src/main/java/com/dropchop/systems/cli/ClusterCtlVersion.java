package com.dropchop.systems.cli;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.IVersionProvider;

/**
 * Reports the application version supplied by the Maven build.
 */
@Dependent
@Unremovable
public class ClusterCtlVersion implements IVersionProvider {
  private final String version;

  /**
   * Creates a version provider using Quarkus build metadata.
   *
   * @param version Maven project version
   */
  public ClusterCtlVersion(@ConfigProperty(name = "quarkus.application.version") String version) {
    this.version = version;
  }

  /** {@inheritDoc} */
  @Override
  public String[] getVersion() {
    return new String[] {"cluster-ctl " + version};
  }
}
