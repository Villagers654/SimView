package net.modtale.simview.service;

import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.config.SimViewConfigStore;
import net.modtale.simview.config.SimViewDistances;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class SimViewDistanceService {

  private final SimViewConfigStore configStore;
  private final HytaleServerConfig serverConfig;
  private final int originalHytaleViewRadius;
  private final int configuredHytaleViewDistanceBlocks;
  private final SimViewAutoTuner autoTuner;
  private final int configuredHytaleSimulationDistanceBlocks;
  private final AtomicLong runtimeDistanceRevision = new AtomicLong();
  private boolean closed;

  public SimViewDistanceService(SimViewConfigStore configStore) {
    this.configStore = configStore;
    this.serverConfig = HytaleServer.get().getConfig();
    this.originalHytaleViewRadius = serverConfig.getMaxViewRadius();
    this.configuredHytaleViewDistanceBlocks = SimViewDistances.sectionsToBlocks(originalHytaleViewRadius);
    this.configuredHytaleSimulationDistanceBlocks = Math.min(configuredHytaleViewDistanceBlocks,
        SimViewDistances.sectionsToBlocks(com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker.MAX_HOT_LOADED_RADIUS));
    this.autoTuner = new SimViewAutoTuner(configuredHytaleSimulationDistanceBlocks, configStore.current());
  }

  public void removePlayer(UUID playerUuid) {
    autoTuner.removePlayer(playerUuid);
  }

  public SimViewConfig current() {
    return configStore.current();
  }

  public int hytaleSimulationDistanceBlocks() {
    return configuredHytaleSimulationDistanceBlocks;
  }

  public int activeTargetViewDistanceBlocks() {
    return autoTuner.activeTargetViewDistanceBlocks();
  }

  public int activeTargetSimulationDistanceBlocks() {
    return autoTuner.activeTargetSimulationDistanceBlocks();
  }

  public int activeSimulationDistanceCap() {
    SimViewConfig config = current();
    if (!config.enabled()) { return configuredHytaleSimulationDistanceBlocks; }
    return config.simulationDistanceCap(
        configuredHytaleSimulationDistanceBlocks, autoTuner.activeTargetSimulationDistanceBlocks());
  }

  public int activeViewDistanceCap() {
    SimViewConfig config = current();
    if (!config.enabled()) { return configuredHytaleViewDistanceBlocks; }
    return config.extendedViewDistanceCap(activeSimulationDistanceCap(), autoTuner.activeTargetViewDistanceBlocks());
  }

  public SimViewAutoTuner.AutoTuneSnapshot autoTuneSnapshot() {
    return autoTuner.snapshot(current());
  }

  public long runtimeDistanceRevision() {
    return runtimeDistanceRevision.get();
  }

  public synchronized void observePlayerTick(
      UUID playerUuid,
      UUID worldUuid,
      long worldTick,
      float deltaSeconds,
      int requestedViewDistanceBlocks) {
    if (closed) {
      return;
    }
    SimViewConfig config = current();
    if (autoTuner.observe(playerUuid, worldUuid, worldTick, deltaSeconds, config, requestedViewDistanceBlocks)) {
      applyServerViewDistanceCap();
    }
  }

  public synchronized void applyServerViewDistanceCap() {
    if (closed) { return; }
    if (current().enabled()) {
      setRuntimeMaxViewRadius(activeViewDistanceCap());
    } else {
      serverConfig.setMaxViewRadius(originalHytaleViewRadius);
    }
    runtimeDistanceRevision.incrementAndGet();
  }

  public synchronized void restoreHytaleViewDistanceCap() {
    closed = true;
    serverConfig.setMaxViewRadius(originalHytaleViewRadius);
    runtimeDistanceRevision.incrementAndGet();
  }

  public synchronized SimViewConfig reload() {
    SimViewConfig config = configStore.reload();
    autoTuner.clear();
    autoTuner.updateConfig(config);
    applyServerViewDistanceCap();
    return config;
  }

  public synchronized SimViewConfig saveAndReload(SimViewConfig updatedConfig) {
    SimViewConfig config = configStore.saveAndReload(updatedConfig);
    autoTuner.clear();
    autoTuner.updateConfig(config);
    applyServerViewDistanceCap();
    return config;
  }

  private void setRuntimeMaxViewRadius(int maxViewRadius) {
    int clampedMaxViewRadius = SimViewDistances.blocksToSections(maxViewRadius);
    if (serverConfig.getMaxViewRadius() == clampedMaxViewRadius) {
      return;
    }
    serverConfig.setMaxViewRadius(clampedMaxViewRadius);
  }
}
