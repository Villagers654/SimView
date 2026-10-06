package net.modtale.simview.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimViewConfigDirectoryTest {
  @TempDir Path directory;

  @Test void migrationCopiesConfigAndBackupWithoutRemovingOriginalsAndIsIdempotent() throws Exception {
    Path legacy = directory.resolve("SimView/config");
    Path nativeRoot = directory.resolve("club.simview_SimView");
    Files.createDirectories(legacy);
    String source = "{\"schema-version\":2,\"core\":{\"target\":{\"view-distance-blocks\":1536}}}";
    Files.writeString(legacy.resolve("simview.json"), source);
    Files.writeString(legacy.resolve("simview.v1.json"), "original backup");
    assertTrue(SimViewConfigDirectory.migrateLegacy(nativeRoot));
    assertEquals(source, Files.readString(nativeRoot.resolve("config/simview.json")));
    assertEquals(source, Files.readString(legacy.resolve("simview.json")));
    assertEquals("original backup", Files.readString(nativeRoot.resolve("config/simview.v1.json")));
    assertFalse(SimViewConfigDirectory.migrateLegacy(nativeRoot));
    assertEquals(1536, SimViewConfig.load(nativeRoot, SimViewConfig.defaults(8)).targetViewDistanceBlocks());
    try (var files = Files.list(nativeRoot.resolve("config"))) {
      assertEquals(2, files.count());
    }
  }

  @Test void existingNativeConfigWinsAndIsNeverReplaced() throws Exception {
    Path legacy = directory.resolve("SimView/config");
    Path nativeRoot = directory.resolve("club.simview_SimView");
    Files.createDirectories(legacy);
    Files.createDirectories(nativeRoot.resolve("config"));
    Files.writeString(legacy.resolve("simview.json"), "legacy");
    Files.writeString(nativeRoot.resolve("config/simview.json"), "native");
    assertFalse(SimViewConfigDirectory.migrateLegacy(nativeRoot));
    assertEquals("native", Files.readString(nativeRoot.resolve("config/simview.json")));
    assertEquals("legacy", Files.readString(legacy.resolve("simview.json")));
  }

  @Test void nativeDirectoryAlreadyNamedSimViewNeedsNoMigration() throws Exception {
    Path nativeRoot = directory.resolve("SimView");
    Files.createDirectories(nativeRoot.resolve("config"));
    Files.writeString(nativeRoot.resolve("config/simview.json"), "already native");
    assertFalse(SimViewConfigDirectory.migrateLegacy(nativeRoot));
    assertEquals("already native", Files.readString(nativeRoot.resolve("config/simview.json")));
  }
}
