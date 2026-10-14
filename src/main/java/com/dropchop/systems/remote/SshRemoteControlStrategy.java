package com.dropchop.systems.remote;

import java.util.List;

import com.dropchop.systems.cli.CliException;
import com.dropchop.systems.config.ClusterCtlConfig;
import com.dropchop.systems.process.ProcessRequest;
import com.dropchop.systems.process.ProcessResult;
import com.dropchop.systems.process.ProcessRunner;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Controls hosts over OpenSSH and edits authorized_keys as the configured SSH user.
 */
@ApplicationScoped
@SuppressWarnings("UnnecessaryStringEscape")
public class SshRemoteControlStrategy implements RemoteControlStrategy {
  private static final String LIST_KEYS = """
      IFS= read -r target_user
      user_entry=$(getent passwd "$target_user") || {
        printf 'Unknown user: %s\n' "$target_user" >&2
        exit 1
      }
      user_home=$(printf '%s\n' "$user_entry" | awk -F: 'NR == 1 { print $6 }')
      authorized_keys="$user_home/.ssh/authorized_keys"
      if [ -f "$authorized_keys" ]; then
        nl -ba "$authorized_keys"
      else
        printf 'No authorized keys file at %s\n' "$authorized_keys"
      fi
      """;

  private static final String ADD_KEY = """
      IFS= read -r target_user
      IFS= read -r verify_required
      IFS= read -r public_key
      user_entry=$(getent passwd "$target_user") || exit 1
      user_home=$(printf '%s\n' "$user_entry" | awk -F: 'NR == 1 { print $6 }')
      [ -n "$user_home" ] && [ -d "$user_home" ] || exit 1
      key_type=${public_key%% *}
      key_data=${public_key#* }
      key_blob=${key_data%% *}
      key_identity="$key_type $key_blob"
      ssh_dir="$user_home/.ssh"
      authorized_keys="$ssh_dir/authorized_keys"
      umask 077
      mkdir -p "$ssh_dir" && chmod 700 "$ssh_dir" && touch "$authorized_keys" \
        && chmod 600 "$authorized_keys" || exit 1
      if [ "$verify_required" = "1" ]; then
        tmp=$(mktemp "${authorized_keys}.XXXXXX") || exit 1
        found=0
        while IFS= read -r line || [ -n "$line" ]; do
          if printf '%s\n' "$line" | grep -qF -- "$key_identity"; then
            found=1
            prefix=${line%%"$key_identity"*}
            case "$prefix" in
              *verify-required*) printf '%s\n' "$line" ;;
              "") printf 'verify-required %s\n' "$line" ;;
              *) printf 'verify-required,%s\n' "$line" ;;
            esac
          else
            printf '%s\n' "$line"
          fi
        done < "$authorized_keys" > "$tmp"
        [ "$found" -eq 1 ] || printf 'verify-required %s\n' "$public_key" >> "$tmp"
        chmod 600 "$tmp" && mv "$tmp" "$authorized_keys" || exit 1
        printf 'Key present with verify-required for %s.\n' "$target_user"
      elif grep -qF -- "$key_identity" "$authorized_keys"; then
        printf 'Key already present for %s.\n' "$target_user"
      else
        printf '%s\n' "$public_key" >> "$authorized_keys" || exit 1
        printf 'Key added for %s.\n' "$target_user"
      fi
      """;

  private static final String FIND_KEYS = """
      IFS= read -r target_user
      matching_string=$(cat)
      user_entry=$(getent passwd "$target_user") || exit 1
      user_home=$(printf '%s\n' "$user_entry" | awk -F: 'NR == 1 { print $6 }')
      authorized_keys="$user_home/.ssh/authorized_keys"
      [ -e "$authorized_keys" ] || exit 0
      [ -r "$authorized_keys" ] || exit 1
      grep -nF -- "$matching_string" "$authorized_keys"
      status=$?
      [ "$status" -le 1 ]
      """;

  private static final String REMOVE_KEYS = """
      IFS= read -r target_user
      matching_string=$(cat)
      user_entry=$(getent passwd "$target_user") || exit 1
      user_home=$(printf '%s\n' "$user_entry" | awk -F: 'NR == 1 { print $6 }')
      authorized_keys="$user_home/.ssh/authorized_keys"
      if [ ! -f "$authorized_keys" ] || ! grep -qF -- "$matching_string" "$authorized_keys"; then
        printf 'No matching authorized keys remain.\n'
        exit 0
      fi
      umask 077
      tmp=$(mktemp "${authorized_keys}.XXXXXX") || exit 1
      grep -vF -- "$matching_string" "$authorized_keys" > "$tmp"
      status=$?
      [ "$status" -le 1 ] || { rm -f "$tmp"; exit "$status"; }
      chmod 600 "$tmp" && mv "$tmp" "$authorized_keys" || exit 1
      printf 'Matching authorized key(s) removed for %s.\n' "$target_user"
      """;

  private final ProcessRunner processRunner;

  /**
   * Creates the SSH strategy.
   *
   * @param processRunner external process runner
   */
  public SshRemoteControlStrategy(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  /** {@inheritDoc} */
  @Override
  public String type() {
    return "ssh";
  }

  /** {@inheritDoc} */
  @Override
  public void execute(ClusterCtlConfig.Host host, String command, boolean dryRun) {
    run(host, command, null, dryRun, true, "execute command");
  }

  /** {@inheritDoc} */
  @Override
  public void listKeys(ClusterCtlConfig.Host host, String user, boolean dryRun) {
    run(host, LIST_KEYS, user + "\n", dryRun, true, "list authorized keys");
  }

  /** {@inheritDoc} */
  @Override
  public void addKey(ClusterCtlConfig.Host host, String user, String pubKey, boolean verifyRequired, boolean dryRun) {
    String input = user + "\n" + (verifyRequired ? "1" : "0") + "\n" + pubKey + "\n";
    run(host, ADD_KEY, input, dryRun, true, "add authorized key");
  }

  /** {@inheritDoc} */
  @Override
  public String findKeys(ClusterCtlConfig.Host host, String user, String matchingString, boolean dryRun) {
    String input = user + "\n" + matchingString;
    return run(host, FIND_KEYS, input, dryRun, false, "search authorized keys").output();
  }

  /** {@inheritDoc} */
  @Override
  public void removeKeys(ClusterCtlConfig.Host host, String user, String matchingString, boolean dryRun) {
    String input = user + "\n" + matchingString;
    run(host, REMOVE_KEYS, input, dryRun, true, "remove authorized keys");
  }

  private ProcessResult run(ClusterCtlConfig.Host host, String remoteCommand, String input, boolean dryRun,
                            boolean printOut, String operation) {
    ProcessResult result = processRunner.run(
        new ProcessRequest(
            List.of("ssh", host.sshUser + "@" + host.name, "-T", remoteCommand), null, input, dryRun, printOut
        )
    );
    if (result.unsuccessful()) {
      throw new CliException("Unable to " + operation + " on " + host.name + " (exit code " + result.exitCode() + ").");
    }
    return result;
  }
}
