package net.modtale.simview.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class SimViewConfigDirectory {
  private SimViewConfigDirectory() {}

  public static boolean migrateLegacy(Path nativeDirectory) {
    Path parent = nativeDirectory.getParent();
    Path legacyDirectory = (parent == null ? nativeDirectory : parent).resolve("SimView");
    if (nativeDirectory.toAbsolutePath().normalize().equals(legacyDirectory.toAbsolutePath().normalize())) {
      return false;
    }
    Path source = legacyDirectory.resolve("config/simview.json");
    Path target = nativeDirectory.resolve("config/simview.json");
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(source)) {
      return false;
    }
    try {
      Files.createDirectories(target.getParent());
      Path backup = source.resolveSibling("simview.v1.json");
      if (Files.isRegularFile(backup)) {
        copyIfAbsent(backup, target.resolveSibling("simview.v1.json"));
      }
      return copyIfAbsent(source, target);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to migrate existing SimView config to " + target, exception);
    }
  }

  private static boolean copyIfAbsent(Path source, Path target) throws IOException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Path temporary = Files.createTempFile(target.getParent(), "simview-migration-", ".tmp");
    try {
      Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temporary, target);
        return true;
      } catch (FileAlreadyExistsException exception) {
        return false;
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
