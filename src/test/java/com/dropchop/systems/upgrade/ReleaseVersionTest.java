package com.dropchop.systems.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dropchop.systems.cli.CliException;

/**
 * Prevents lexicographic upgrades, downgrades, and unsafe version-derived paths.
 */
class ReleaseVersionTest {
  @Test
  void comparesNumericComponentsAndNormalizesTags() {
    assertEquals("0.1.10", new ReleaseVersion("v0.1.10").value());
    assertTrue(new ReleaseVersion("0.1.10").compareTo(new ReleaseVersion("0.1.9")) > 0);
    assertTrue(new ReleaseVersion("1.0.0").compareTo(new ReleaseVersion("0.99.999")) > 0);
    assertEquals(0, new ReleaseVersion("v0.1.2").compareTo(new ReleaseVersion("0.1.2")));
  }

  @Test
  void stableVersionsFollowDevelopmentVersionsButNeverDowngradeTheirCore() {
    assertFalse(new ReleaseVersion("0.1.2-SNAPSHOT").stable());
    assertTrue(new ReleaseVersion("0.1.2").compareTo(new ReleaseVersion("0.1.2-SNAPSHOT")) > 0);
    assertTrue(new ReleaseVersion("0.2.0-SNAPSHOT").compareTo(new ReleaseVersion("0.1.9")) > 0);
    assertTrue(new ReleaseVersion("1.0.0-rc.10").compareTo(new ReleaseVersion("1.0.0-rc.2")) > 0);
  }

  @Test
  void rejectsMalformedOrPathLikeVersions() {
    for (String version : List.of("", "latest", "1.2", "01.2.3", "v1.2.3/../../other", "1.2.3\n")) {
      assertThrows(CliException.class, () -> new ReleaseVersion(version), version);
    }
  }
}
