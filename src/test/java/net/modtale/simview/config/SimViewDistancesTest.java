package net.modtale.simview.config;

import static org.junit.jupiter.api.Assertions.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.junit.jupiter.api.Test;

class SimViewDistancesTest {
  @Test void conversionsUseActualApiSizeAndCannotOverflow() {
    assertEquals(32, ChunkUtil.SIZE);
    assertEquals(2048, SimViewDistances.sectionsToBlocks(64));
    assertEquals(32, SimViewDistances.blocksToSections(1024));
    assertEquals(64, SimViewDistances.blocksToSections(2048));
    assertEquals(Integer.MAX_VALUE, SimViewDistances.sectionsToBlocks(Integer.MAX_VALUE));
    assertEquals(0, SimViewDistances.sectionsToBlocks(-1));
  }

  @Test void configuredDistancesRoundDownAndStayWithinResourceBounds() {
    assertEquals(0, SimViewDistances.normalizeBlocks(-1));
    assertEquals(0, SimViewDistances.normalizeBlocks(31));
    assertEquals(32, SimViewDistances.normalizeBlocks(33));
    assertEquals(2048, SimViewDistances.normalizeBlocks(2049));
    var config = TestConfigs.config("""
        {"schema-version":2,"core":{"target":{"view-distance-blocks":2147483648,
        "simulation-distance-blocks":-1},"limits":{"minimum":{"view-distance-blocks":33},
        "maximum":{"view-distance-blocks":2147483648}}}}
        """);
    assertEquals(2048, config.targetViewDistanceBlocks());
    assertEquals(-1, config.targetSimulationDistanceBlocks());
    assertEquals(32, config.minimumTargetViewDistanceBlocks());
    assertEquals(2048, config.maximumTargetViewDistanceBlocks());
  }
}
