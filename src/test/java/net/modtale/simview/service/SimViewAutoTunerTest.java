package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import net.modtale.simview.config.TestConfigs;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimViewAutoTunerTest {
  @Test void sectionEstimatesMatchThreeDimensionalSphere() {
    assertEquals(1, SimViewAutoTuner.sphereRadiusArea(0));
    assertEquals(7, SimViewAutoTuner.sphereRadiusArea(1));
    assertEquals(33, SimViewAutoTuner.sphereRadiusArea(2));
  }

  @Test void multipleWorldsDoNotMultiplyAdjustmentCadence() {
    var config = TestConfigs.config("{}");
    var tuner = new SimViewAutoTuner(256, config);
    UUID playerA = UUID.randomUUID(), playerB = UUID.randomUUID();
    UUID worldA = UUID.randomUUID(), worldB = UUID.randomUUID();
    for (int tick = 1; tick <= 100; tick++) {
      tuner.observe(playerA, worldA, tick, .05f, config, 1024);
      tuner.observe(playerB, worldB, tick, .05f, config, 1024);
    }
    assertTrue(tuner.snapshot(config).observedTicks() <= 101);
  }

  @Test void loweringBothTargetsOnReloadUsesNewSimulationFloor() {
    var before = TestConfigs.config("""
        {"core":{"target":{"view-distance-chunks":32,"simulation-distance-chunks":16},
         "limits":{"maximum":{"simulation-distance-chunks":32}}}}
        """);
    var after = TestConfigs.config("{\"core\":{\"target\":{\"view-distance-chunks\":8,\"simulation-distance-chunks\":4}}}");
    var tuner = new SimViewAutoTuner(256, before);
    tuner.updateConfig(after);
    assertEquals(256, tuner.activeTargetViewDistanceBlocks());
    assertEquals(128, tuner.activeTargetSimulationDistanceBlocks());
  }
  @Test void proactiveModeCanIncreaseBothTargets() {
    var config = TestConfigs.config("""
        {"core":{"target":{"view-distance-chunks":10,"simulation-distance-chunks":4},
        "limits":{"maximum":{"simulation-distance-chunks":8}}},
        "auto-adjustment":{"mode":{"view":"proactive","simulation":"proactive"},
        "cadence":{"ticks-per-check":1,"startup-delay-ticks":0},
        "checks":{"view":{"for-increase":1},"simulation":{"for-increase":1}},
        "proactive":{"global-cold-section-count-target":10000,"global-ticking-section-count-target":10000}}}
        """);
    var tuner = new SimViewAutoTuner(256, config);
    tuner.observe(UUID.randomUUID(), UUID.randomUUID(), 1, .02f, config, 1024);
    assertEquals(352, tuner.activeTargetViewDistanceBlocks());
    assertEquals(160, tuner.activeTargetSimulationDistanceBlocks());
  }

  @Test void reactiveModeCanIncreaseAndDisabledModeDoesNotAdjust() {
    var config = TestConfigs.config("""
        {"core":{"target":{"view-distance-chunks":10}},
        "auto-adjustment":{"mode":{"view":"reactive"},
        "cadence":{"ticks-per-check":1,"startup-delay-ticks":0},
        "checks":{"view":{"for-increase":1}},"reactive":{"use-mspt-prediction":false}}}
        """);
    var tuner = new SimViewAutoTuner(256, config);
    tuner.observe(UUID.randomUUID(), UUID.randomUUID(), 1, .02f, config, 1024);
    assertEquals(352, tuner.activeTargetViewDistanceBlocks());
    var disabled = TestConfigs.config("""
        {"core":{"enabled":false,"target":{"view-distance-chunks":10}},
        "auto-adjustment":{"mode":{"view":"proactive"},
        "cadence":{"ticks-per-check":1,"startup-delay-ticks":0},
        "checks":{"view":{"for-increase":1}}}}
        """);
    tuner = new SimViewAutoTuner(256, disabled);
    tuner.observe(UUID.randomUUID(), UUID.randomUUID(), 1, .02f, disabled, 1024);
    assertEquals(320, tuner.activeTargetViewDistanceBlocks());
  }

  @Test void disconnectImmediatelyRemovesLoadEstimate() {
    var config = TestConfigs.config("{}");
    var tuner = new SimViewAutoTuner(256, config);
    UUID player = UUID.randomUUID();
    tuner.observe(player, UUID.randomUUID(), 1, .05f, config, 1024);
    assertTrue(tuner.snapshot(config).estimatedColdSections() > 0);
    tuner.removePlayer(player);
    assertEquals(0, tuner.snapshot(config).estimatedColdSections());
  }
}
