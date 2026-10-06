package net.modtale.simview.service;

import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;

public record SimViewTrackerSettings(int perSecond, int perTick, int minLoadedRadius, int maxHotRadius) {
  public static SimViewTrackerSettings capture(ChunkTracker tracker) {
    return new SimViewTrackerSettings(tracker.getMaxSectionsPerSecond(), tracker.getMaxSectionsPerTick(),
        tracker.getMinLoadedRadius(), tracker.getMaxHotLoadedRadius());
  }

  public void restore(ChunkTracker tracker) {
    tracker.setMaxSectionsPerSecond(perSecond);
    tracker.setMaxSectionsPerTick(perTick);
    tracker.setMinLoadedRadius(minLoadedRadius);
    tracker.setMaxHotLoadedRadius(maxHotRadius);
  }
}
