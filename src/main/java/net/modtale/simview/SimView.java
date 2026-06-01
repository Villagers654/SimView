package net.modtale.simview;

import net.modtale.simview.command.SimViewGuiCommand;
import net.modtale.simview.command.SimViewReloadCommand;
import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.config.SimViewConfigStore;
import net.modtale.simview.permission.SimViewAccessControl;
import net.modtale.simview.service.SimViewColdChunkStreamer;
import net.modtale.simview.service.SimViewDistanceService;
import net.modtale.simview.system.SimViewTuningSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimView extends JavaPlugin {

  private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

  private SimViewDistanceService distanceService;
  private SimViewColdChunkStreamer coldChunkStreamer;
  private SimViewTuningSystem tuningSystem;
  private final Set<UUID> hintedPlayers = ConcurrentHashMap.newKeySet();

  public SimView(JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    Path defaultDataDirectory = this.getDataDirectory();
    Path parentDirectory = defaultDataDirectory.getParent();
    Path configRoot = (parentDirectory == null ? defaultDataDirectory : parentDirectory).resolve("SimView");
    SimViewConfigStore configStore = new SimViewConfigStore(configRoot);

    distanceService = new SimViewDistanceService(configStore);
    coldChunkStreamer = new SimViewColdChunkStreamer();
    distanceService.applyServerViewDistanceCap();

    SimViewConfig config = distanceService.current();
    tuningSystem = new SimViewTuningSystem(distanceService, coldChunkStreamer);
    this.getEntityStoreRegistry().registerSystem(tuningSystem);
    this.getCommandRegistry().registerCommand(new SimViewGuiCommand(distanceService));
    this.getCommandRegistry().registerCommand(new SimViewReloadCommand(distanceService));
    this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::handleAddPlayerToWorld);
    this.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, this::handleRemovedPlayerFromWorld);
    this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::handlePlayerDisconnect);

    LOGGER.atInfo().log(
        "SimView setup complete: configuredSimulationDistance=%s chunks, configuredTargetSimulationDistance=%s chunks, activeTargetSimulationDistance=%s chunks, configuredViewDistance=%s chunks, activeViewDistance=%s chunks, viewMode=%s, simulationMode=%s, viewDistanceCap=%s chunks, configRoot=%s",
        distanceService.hytaleSimulationDistanceChunks(),
        config.targetSimulationDistanceChunks(),
        distanceService.activeTargetSimulationDistanceChunks(),
        config.targetViewDistanceChunks(),
        distanceService.activeTargetViewDistanceChunks(),
        config.adjustmentMode().name().toLowerCase(),
        config.simulationAdjustmentMode().name().toLowerCase(),
        distanceService.activeViewDistanceCap(),
        configRoot.toAbsolutePath());
  }

  @Override
  protected void shutdown() {
    if (distanceService != null) {
      distanceService.restoreHytaleViewDistanceCap();
    }

    if (coldChunkStreamer != null) {
      coldChunkStreamer.unloadAll();
      coldChunkStreamer.close();
    }
    hintedPlayers.clear();

    super.shutdown();
  }

  private void handleAddPlayerToWorld(AddPlayerToWorldEvent event) {
    SimViewConfig config = distanceService.current();
    if (config.disableJoinHintMessage()) {
      return;
    }

    PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
    if (playerRef == null) {
      return;
    }

    UUID playerUuid = playerRef.getUuid();
    if (playerUuid == null || !hintedPlayers.add(playerUuid)) {
      return;
    }

    boolean shouldHint = SimViewAccessControl.isSingleplayerOwner(playerRef) || SimViewAccessControl.isAdmin(playerRef);
    if (!shouldHint) {
      return;
    }

    playerRef.sendMessage(
        Message.raw("Tip: Use /simview to customize your view and simulation distances.").color("yellow"));
  }

  private void handleRemovedPlayerFromWorld(RemovedPlayerFromWorldEvent event) {
    PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
    cleanupPlayer(playerRef);
  }

  private void handlePlayerDisconnect(PlayerDisconnectEvent event) {
    cleanupPlayer(event.getPlayerRef());
  }

  private void cleanupPlayer(PlayerRef playerRef) {
    if (playerRef == null) {
      return;
    }

    if (coldChunkStreamer != null) {
      coldChunkStreamer.unload(playerRef);
    }
    if (tuningSystem != null) {
      tuningSystem.unload(playerRef);
    }

    UUID playerUuid = playerRef.getUuid();
    if (playerUuid != null) {
      hintedPlayers.remove(playerUuid);
    }
  }

}
