package net.modtale.simview.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimViewConfigTest {
  @TempDir Path directory;

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
    assertEquals(8, store.reload().targetSimulationDistanceChunks());
  }

  @Test void unsafeResourceValuesAreBounded() {
    SimViewConfig config = TestConfigs.config("""
        {"core":{"target":{"view-distance-chunks":2147483647},
         "limits":{"maximum":{"view-distance-chunks":2147483647}}},
         "auto-adjustment":{"reactive":{"mspt-collection-period-ticks":2147483647}},
         "section-streaming":{"budget":{"section-sends-per-tick":2147483647}}}
        """);
    assertEquals(64, config.targetViewDistanceChunks());
    assertEquals(64, config.maximumTargetViewDistanceChunks());
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
