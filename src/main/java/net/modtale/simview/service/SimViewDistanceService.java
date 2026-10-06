package net.modtale.simview.service;

import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.config.SimViewConfigStore;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class SimViewDistanceService {

  private final SimViewConfigStore configStore;
  private final HytaleServerConfig serverConfig;
  private final int configuredHytaleViewDistanceChunks;
  private final SimViewAutoTuner autoTuner;
  private final int configuredHytaleSimulationDistanceChunks;
  private final AtomicLong runtimeDistanceRevision = new AtomicLong();
  private boolean closed;

  public SimViewDistanceService(SimViewConfigStore configStore) {
    this.configStore = configStore;
    this.serverConfig = HytaleServer.get().getConfig();
    this.configuredHytaleViewDistanceChunks = Math.max(0, serverConfig.getMaxViewRadius());
    this.configuredHytaleSimulationDistanceChunks = Math.min(configuredHytaleViewDistanceChunks,
        com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker.MAX_HOT_LOADED_RADIUS);
    this.autoTuner = new SimViewAutoTuner(configuredHytaleSimulationDistanceChunks, configStore.current());
  }

  public void removePlayer(UUID playerUuid) {
    autoTuner.removePlayer(playerUuid);
  }

  public SimViewConfig current() {
    return configStore.current();
  }

  public int hytaleSimulationDistanceChunks() {
    return configuredHytaleSimulationDistanceChunks;
  }

  public int activeTargetViewDistanceChunks() {
    return autoTuner.activeTargetViewDistanceChunks();
  }

  public int activeTargetSimulationDistanceChunks() {
    return autoTuner.activeTargetSimulationDistanceChunks();
  }

  public int activeSimulationDistanceCap() {
    SimViewConfig config = current();
    if (!config.enabled()) { return configuredHytaleSimulationDistanceChunks; }
    return config.simulationDistanceCap(
        configuredHytaleSimulationDistanceChunks, autoTuner.activeTargetSimulationDistanceChunks());
  }

  public int activeViewDistanceCap() {
    SimViewConfig config = current();
    if (!config.enabled()) { return configuredHytaleViewDistanceChunks; }
    return config.extendedViewDistanceCap(activeSimulationDistanceCap(), autoTuner.activeTargetViewDistanceChunks());
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
      int requestedViewDistanceChunks) {
    if (closed) {
      return;
    }
    SimViewConfig config = current();
    if (autoTuner.observe(playerUuid, worldUuid, worldTick, deltaSeconds, config, requestedViewDistanceChunks)) {
      applyServerViewDistanceCap();
    }
  }

  public synchronized void applyServerViewDistanceCap() {
    if (closed) { return; }
    setRuntimeMaxViewRadius(current().enabled() ? activeViewDistanceCap() : configuredHytaleViewDistanceChunks);
    runtimeDistanceRevision.incrementAndGet();
  }

  public synchronized void restoreHytaleViewDistanceCap() {
    closed = true;
    setRuntimeMaxViewRadius(configuredHytaleViewDistanceChunks);
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
    int clampedMaxViewRadius = Math.max(0, maxViewRadius);
    if (serverConfig.getMaxViewRadius() == clampedMaxViewRadius) {
      return;
    }
    serverConfig.setMaxViewRadius(clampedMaxViewRadius);
  }
}
