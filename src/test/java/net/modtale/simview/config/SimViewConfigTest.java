package net.modtale.simview.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimViewConfigTest {
  @TempDir Path directory;

  @Test void nativeIsDefaultAndDiskRoundTripsWithoutChangingUnitsOrBudgets() {
    var nativeConfig = TestConfigs.config("{}");
    assertEquals(SimViewStreamingMode.NATIVE, nativeConfig.streamingMode());
    assertEquals(1024, nativeConfig.nativeLoadingDistance(128, 1024));
    var disk = TestConfigs.config("{\"section-streaming\":{\"mode\":\"disk\"}}");
    assertEquals(128, disk.nativeLoadingDistance(128, 1024));
    assertEquals(1024, disk.effectiveExtendedViewDistance(128, 1024));
    assertEquals(nativeConfig.targetViewDistanceBlocks(), disk.targetViewDistanceBlocks());
    assertEquals(nativeConfig.maxSectionSendsPerSecond(), disk.maxSectionSendsPerSecond());
    SimViewConfig.save(directory, disk);
    assertEquals(disk, SimViewConfig.load(directory, SimViewConfig.defaults(8)));
    assertThrows(IllegalArgumentException.class, () -> TestConfigs.config(
        "{\"section-streaming\":{\"mode\":\"unknown\"}}"));
  }

  @Test void legacyDistancesMigrateOnceWithoutChangingWorldDistancesOrExplicitBudgets() throws Exception {
    String oldJson = """
        {"core":{"target":{"view-distance-chunks":64,"simulation-distance-chunks":-1},
          "limits":{"minimum":{"view-distance-chunks":2,"simulation-distance-chunks":1},
          "maximum":{"view-distance-chunks":64,"simulation-distance-chunks":8}}},
         "section-streaming":{"budget":{"section-sends-per-second":96,"section-sends-per-tick":8}}}
        """;
    Path file = directory.resolve("config/simview.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, oldJson);
    var loaded = SimViewConfig.load(directory, SimViewConfig.defaults(8));
    assertEquals(2048, loaded.targetViewDistanceBlocks());
    assertEquals(-1, loaded.targetSimulationDistanceBlocks());
    assertEquals(64, loaded.minimumTargetViewDistanceBlocks());
    assertEquals(32, loaded.minimumTargetSimulationDistanceBlocks());
    assertEquals(2048, loaded.maximumTargetViewDistanceBlocks());
    assertEquals(256, loaded.maximumTargetSimulationDistanceBlocks());
    assertEquals(96, loaded.maxSectionSendsPerSecond());
    assertEquals(8, loaded.maxSectionSendsPerTick());
    assertEquals(oldJson, Files.readString(file.resolveSibling("simview.v1.json")));
    var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    assertEquals(2, saved.get("schema-version").getAsInt());
    assertFalse(Files.readString(file).contains("distance-chunks"));
    assertEquals(loaded, SimViewConfig.load(directory, SimViewConfig.defaults(8)));
    assertEquals(oldJson, Files.readString(file.resolveSibling("simview.v1.json")));
  }

  @Test void mixedUnitsAndUnknownSchemasAreRejectedRatherThanReinterpreted() {
    assertThrows(IllegalArgumentException.class, () -> TestConfigs.config("""
        {"schema-version":2,"core":{"target":{"view-distance-chunks":64}}}
        """));
    assertThrows(IllegalArgumentException.class, () -> TestConfigs.config("""
        {"core":{"target":{"view-distance-blocks":1024}}}
        """));
    assertThrows(IllegalArgumentException.class, () -> TestConfigs.config("{\"schema-version\":3}"));
    assertThrows(IllegalArgumentException.class, () -> TestConfigs.config("{\"schema-version\":2.5}"));
  }

  @Test void reloadFailurePreservesLastGoodConfiguration() throws Exception {
    SimViewConfigStore store = new SimViewConfigStore(directory, SimViewConfig.defaults(8));
    SimViewConfig previous = store.current();
    Files.writeString(directory.resolve("config/simview.json"), "{");
    assertThrows(IllegalStateException.class, store::reload);
    assertSame(previous, store.current());
  }

  @Test void saveRoundTripsAndLeavesNoTemporaryFiles() throws Exception {
    SimViewConfig config = TestConfigs.config("{\"core\":{\"target\":{\"view-distance-chunks\":48}}}");
    SimViewConfig.save(directory, config);
    assertEquals(config, SimViewConfig.load(directory, SimViewConfig.defaults(8)));
    try (var files = Files.list(directory.resolve("config"))) {
      assertEquals(1, files.count());
    }
  }

  @Test void reloadUsesOriginalDefaults() throws Exception {
    SimViewConfigStore store = new SimViewConfigStore(directory, SimViewConfig.defaults(8));
    Files.writeString(directory.resolve("config/simview.json"), "{}");
    assertEquals(256, store.reload().targetSimulationDistanceBlocks());
  }

  @Test void unsafeResourceValuesAreBounded() {
    SimViewConfig config = TestConfigs.config("""
        {"core":{"target":{"view-distance-chunks":2147483647},
         "limits":{"maximum":{"view-distance-chunks":2147483647}}},
         "auto-adjustment":{"reactive":{"mspt-collection-period-ticks":2147483647}},
         "section-streaming":{"budget":{"section-sends-per-tick":2147483647}}}
        """);
    assertEquals(2048, config.targetViewDistanceBlocks());
    assertEquals(2048, config.maximumTargetViewDistanceBlocks());
    assertEquals(128, config.maxSectionSendsPerTick());
    assertEquals(6_000, config.reactiveMsptCollectionPeriodTicks());
  }

  @Test void nonFiniteNumbersFallBackAndModesIgnoreSystemLocale() {
    SimViewConfig config = TestConfigs.config("{\"auto-adjustment\":{\"reactive\":{\"increase-mspt-threshold\":\"NaN\"}}}");
    assertEquals(40, config.reactiveIncreaseMsptThreshold());
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(SimViewAdjustmentMode.MIXED, SimViewAdjustmentMode.fromProperty("mixed", null));
    } finally {
      Locale.setDefault(original);
    }
  }
}
