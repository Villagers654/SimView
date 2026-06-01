package club.simview.simview.service;

import club.simview.simview.config.SimViewConfig;
import club.simview.simview.config.SimViewConfigStore;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import java.util.UUID;

public final class SimViewDistanceService {

  private final SimViewConfigStore configStore;
  private final HytaleServerConfig serverConfig;
  private final int configuredHytaleViewDistanceChunks;
  private final SimViewAutoTuner autoTuner;

  public SimViewDistanceService(SimViewConfigStore configStore) {
    this.configStore = configStore;
    this.serverConfig = HytaleServer.get().getConfig();
    this.configuredHytaleViewDistanceChunks = Math.max(0, serverConfig.getMaxViewRadius());
    this.autoTuner = new SimViewAutoTuner(configuredHytaleViewDistanceChunks, configStore.current());
  }

  public SimViewConfig current() {
    return configStore.current();
  }

  public int hytaleSimulationDistanceChunks() {
    return configuredHytaleViewDistanceChunks;
  }

  public int activeTargetViewDistanceChunks() {
    return autoTuner.activeTargetViewDistanceChunks();
  }

  public int activeTargetSimulationDistanceChunks() {
    return autoTuner.activeTargetSimulationDistanceChunks();
  }

  public int activeSimulationDistanceCap() {
    SimViewConfig config = current();
    return config.simulationDistanceCap(
        configuredHytaleViewDistanceChunks, autoTuner.activeTargetSimulationDistanceChunks());
  }

  public int activeViewDistanceCap() {
    SimViewConfig config = current();
    return config.extendedViewDistanceCap(activeSimulationDistanceCap(), autoTuner.activeTargetViewDistanceChunks());
  }

  public SimViewAutoTuner.AutoTuneSnapshot autoTuneSnapshot() {
    return autoTuner.snapshot(current());
  }

  public void observePlayerTick(
      UUID playerUuid,
      UUID worldUuid,
      long worldTick,
      float deltaSeconds,
      int requestedViewDistanceChunks) {
    SimViewConfig config = current();
    if (autoTuner.observe(playerUuid, worldUuid, worldTick, deltaSeconds, config, requestedViewDistanceChunks)) {
      applyServerViewDistanceCap();
    }
  }

  public void applyServerViewDistanceCap() {
    setRuntimeMaxViewRadius(activeSimulationDistanceCap());
  }

  public void restoreHytaleViewDistanceCap() {
    setRuntimeMaxViewRadius(configuredHytaleViewDistanceChunks);
  }

  public SimViewConfig reload() {
    SimViewConfig config = configStore.reload();
    autoTuner.clear();
    autoTuner.updateConfig(config);
    applyServerViewDistanceCap();
    return config;
  }

  public SimViewConfig saveAndReload(SimViewConfig updatedConfig) {
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
