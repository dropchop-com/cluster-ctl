package com.dropchop.systems.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Root representation of a cluster-ctl YAML file.
 */
@RegisterForReflection(targets = {
    ClusterCtlConfig.class,
    ClusterCtlConfig.Local.class,
    ClusterCtlConfig.Build.class,
    ClusterCtlConfig.BuildProject.class,
    ClusterCtlConfig.Docker.class,
    ClusterCtlConfig.Image.class,
    ClusterCtlConfig.Remote.class,
    ClusterCtlConfig.Cluster.class,
    ClusterCtlConfig.Environment.class,
    ClusterCtlConfig.Deploy.class,
    ClusterCtlConfig.Host.class,
    ClusterCtlConfig.Group.class,
    ClusterCtlConfig.Apps.class,
    ClusterCtlConfig.Artifact.class
})
public class ClusterCtlConfig {
  public List<String> commands = new ArrayList<>();
  public Local local = new Local();
  public Remote remote = new Remote();
  public Apps apps = new Apps();

  @JsonIgnore
  public Path sourceDirectory;

  /**
   * Local workstation settings.
   */
  public static class Local {
    public Build build = new Build();
    public Docker docker = new Docker();
  }

  /**
   * Build strategy and project definitions.
   */
  public static class Build {
    public String type = "maven";
    public String command = "mvn";
    public List<BuildProject> projects = new ArrayList<>();
  }

  /**
   * One independently buildable project.
   */
  public static class BuildProject {
    public String name;
    public List<String> aliases = new ArrayList<>();
    public String path;
    public List<String> arguments = new ArrayList<>();
  }

  /**
   * Local Docker Compose and registry settings.
   */
  public static class Docker {
    public String compose;

    @JsonProperty("compose-command")
    public List<String> composeCommand = new ArrayList<>(List.of("docker", "compose"));

    public String registry;
    public String username;

    @JsonProperty("repo-token")
    public String repoToken;

    public List<Image> images = new ArrayList<>();
  }

  /**
   * One pullable development image and its short names.
   */
  public static class Image {
    public String url;
    public List<String> aliases = new ArrayList<>();
  }

  /**
   * Remote cluster, environment, host, and group settings.
   */
  public static class Remote {
    public List<Cluster> clusters = new ArrayList<>();

    @JsonProperty("default-cluster")
    public String defaultCluster;

    public List<Environment> environments = new ArrayList<>();
    public List<Host> hosts = new ArrayList<>();
    public List<Group> groups = new ArrayList<>();
  }

  /**
   * Named cluster and its remote control strategy.
   */
  public static class Cluster {
    public String name;
    public String control = "ssh";
  }

  /**
   * Deployable runtime environment.
   */
  public static class Environment {
    public String name;
    public String namespace;  //if maybe kubernetes
    public String cluster;

    @JsonProperty("build-environment")
    public String buildEnvironment;

    public Deploy deploy = new Deploy();
  }

  /**
   * Deployment target and strategy settings.
   */
  public static class Deploy {
    public String type = "scp";
    public String host;
    public String user;
    public String path;
    public String checksum;
  }

  /**
   * Host address, aliases, and SSH metadata.
   */
  public static class Host {
    public String name;
    public String cluster;
    public List<String> aliases = new ArrayList<>();

    @JsonProperty("ssh-user")
    public String sshUser = "root";

    public List<String> users = new ArrayList<>();
  }

  /**
   * Cluster-scoped host group, optionally composed from other groups.
   */
  public static class Group {
    public String name;
    public String cluster;
    public List<String> aliases = new ArrayList<>();
    public List<String> hosts = new ArrayList<>();
    public List<String> groups = new ArrayList<>();
  }

  /**
   * Application source roots and deployable artifacts.
   */
  public static class Apps {
    @JsonProperty("project-roots")
    public List<String> projectRoots = new ArrayList<>();

    public List<Artifact> artifacts = new ArrayList<>();
  }

  /**
   * One deployable artifact or member of an artifact group.
   */
  public static class Artifact {
    public String name;
    public String group;
    public List<String> aliases = new ArrayList<>();
    public List<String> clusters = new ArrayList<>();

    @JsonProperty("build-output-path")
    public String buildOutputPath;

    @JsonProperty("file-name")
    public String fileName;
  }
}
