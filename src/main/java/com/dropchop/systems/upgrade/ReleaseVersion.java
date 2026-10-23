package com.dropchop.systems.upgrade;

import java.math.BigInteger;

import com.dropchop.systems.cli.CliException;

/**
 * Compares semantic release versions numerically, including development prereleases.
 *
 * @param value version without a leading tag prefix
 */
public record ReleaseVersion(String value) implements Comparable<ReleaseVersion> {
  /**
   * Validates a Maven version or GitHub release tag and normalizes its prefix.
   *
   * @param value version or v-prefixed tag
   */
  public ReleaseVersion {
    if (value == null || value.length() > 128
        || !value.matches("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?")) {
      throw new CliException("Unsupported release version; expected MAJOR.MINOR.PATCH with an optional prerelease.");
    }
    value = value.startsWith("v") ? value.substring(1) : value;
  }

  /**
   * Identifies a full release rather than a prerelease or development snapshot.
   *
   * @return whether this version is stable
   */
  public boolean stable() {
    return !value.contains("-");
  }

  /** {@inheritDoc} */
  @Override
  public int compareTo(ReleaseVersion other) {
    String[] left = value.split("-", 2);
    String[] right = other.value.split("-", 2);
    String[] leftCore = left[0].split("\\.");
    String[] rightCore = right[0].split("\\.");
    for (int index = 0; index < 3; index++) {
      int result = new BigInteger(leftCore[index]).compareTo(new BigInteger(rightCore[index]));
      if (result != 0) {
        return result;
      }
    }
    if (stable() || other.stable()) {
      return Boolean.compare(stable(), other.stable());
    }
    String[] leftPrerelease = left[1].split("\\.");
    String[] rightPrerelease = right[1].split("\\.");
    for (int index = 0; index < Math.min(leftPrerelease.length, rightPrerelease.length); index++) {
      String first = leftPrerelease[index];
      String second = rightPrerelease[index];
      boolean firstNumeric = first.matches("[0-9]+");
      boolean secondNumeric = second.matches("[0-9]+");
      int result = firstNumeric && secondNumeric ? new BigInteger(first).compareTo(new BigInteger(second))
          : firstNumeric != secondNumeric ? (firstNumeric ? -1 : 1) : first.compareTo(second);
      if (result != 0) {
        return result;
      }
    }
    return Integer.compare(leftPrerelease.length, rightPrerelease.length);
  }
}
