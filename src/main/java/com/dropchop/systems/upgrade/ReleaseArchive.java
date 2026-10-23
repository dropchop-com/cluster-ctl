package com.dropchop.systems.upgrade;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import com.dropchop.systems.cli.CliException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Verifies SHA-256 and extracts only the expected regular executable, never archive paths.
 */
@ApplicationScoped
public class ReleaseArchive {
  /**
   * Limits total expanded TAR data, including discarded entries and metadata.
   */
  private static class LimitedInput extends FilterInputStream {
    private long remaining = 512L * 1024 * 1024;

    LimitedInput(InputStream input) {
      super(input);
    }

    private void count(long amount) throws IOException {
      remaining -= Math.max(0, amount);
      if (remaining < 0) {
        throw new IOException("Expanded release archive exceeds its size limit.");
      }
    }

    /** {@inheritDoc} */
    @Override
    public int read() throws IOException {
      int value = in.read();
      count(value == -1 ? 0 : 1);
      return value;
    }

    /** {@inheritDoc} */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int amount = in.read(buffer, offset, length);
      count(amount);
      return amount;
    }

    /** {@inheritDoc} */
    @Override
    public long skip(long length) throws IOException {
      long amount = in.skip(Math.min(length, Math.max(1, remaining + 1)));
      count(amount);
      return amount;
    }
  }

  private void verifyChecksum(Path archive, Path checksum) throws IOException {
    List<String> lines = Files.readAllLines(checksum);
    if (lines.size() != 1 || !lines.getFirst().matches("[a-fA-F0-9]{64}([ \\t]+[^\\r\\n]+)?")) {
      throw new CliException("Invalid SHA-256 checksum file; current executable was not changed.");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = new DigestInputStream(Files.newInputStream(archive), digest)) {
        input.transferTo(OutputStream.nullOutputStream());
      }
      byte[] expected = HexFormat.of().parseHex(lines.getFirst().substring(0, 64));
      if (!MessageDigest.isEqual(expected, digest.digest())) {
        throw new CliException("SHA-256 checksum mismatch; current executable was not changed.");
      }
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  /**
   * Verifies a downloaded archive before writing its expected executable to a private stage file.
   *
   * @param archive downloaded tar.gz archive
   * @param checksum downloaded SHA-256 sidecar
   * @param bundle expected archive base directory
   * @param staged existing temporary file beside the installed executable
   * @throws IOException when the archive cannot be read or safely extracted
   */
  public void extract(Path archive, Path checksum, String bundle, Path staged) throws IOException {
    verifyChecksum(archive, checksum);
    boolean found = false;
    try (InputStream gzip = new GZIPInputStream(Files.newInputStream(archive));
        LimitedInput expanded = new LimitedInput(gzip);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        Path name = Path.of(entry.getName());
        if (!entry.isCheckSumOK() || entry.getName().contains("\\") || name.isAbsolute()
            || name.normalize().startsWith("..") || entry.isSymbolicLink() || entry.isLink()
            || entry.isSparse() || (!entry.isFile() && !entry.isDirectory())) {
          throw new CliException("Release archive contains an unsafe entry.");
        }
        if (!entry.getName().equals(bundle + "/cluster-ctl")) {
          continue;
        }
        if (found || !entry.isFile() || entry.getSize() <= 0 || entry.getSize() > 256L * 1024 * 1024) {
          throw new CliException("Release archive contains an invalid or duplicate executable.");
        }
        try (OutputStream output = Files.newOutputStream(staged)) {
          if (tar.transferTo(output) != entry.getSize()) {
            throw new IOException("Release executable is truncated.");
          }
        }
        found = true;
      }
      // Consume padding and the gzip trailer so truncated/compressed-corrupt archives fail too.
      expanded.transferTo(OutputStream.nullOutputStream());
    } catch (InvalidPathException exception) {
      throw new CliException("Release archive contains an unsafe entry.", exception);
    }
    if (!found) {
      throw new CliException("Release archive does not contain " + bundle + "/cluster-ctl.");
    }
  }
}
