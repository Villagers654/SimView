package net.modtale.simview.service;

import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public record SimViewTrackerSettings(int perSecond, int perTick, int minLoadedRadius, int maxHotRadius) {
  public static SimViewTrackerSettings captureForTuning(ChunkTracker tracker, PlayerRef playerRef) {
    if (tracker.getMaxSectionsPerSecond() <= 0) {
      tracker.setDefaultMaxSectionsPerSecond(playerRef);
    }
    return capture(tracker);
  }

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
