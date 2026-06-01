package club.simview.simview;

import club.simview.simview.command.SimViewGuiCommand;
import club.simview.simview.command.SimViewReloadCommand;
import club.simview.simview.config.SimViewConfig;
import club.simview.simview.config.SimViewConfigStore;
import club.simview.simview.permission.SimViewAccessControl;
import club.simview.simview.service.SimViewColdChunkStreamer;
import club.simview.simview.service.SimViewDistanceService;
import club.simview.simview.system.SimViewTuningSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
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
    this.getEntityStoreRegistry().registerSystem(new SimViewTuningSystem(distanceService, coldChunkStreamer));
    this.getCommandRegistry().registerCommand(new SimViewGuiCommand(distanceService));
    this.getCommandRegistry().registerCommand(new SimViewReloadCommand(distanceService));
    this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::handleAddPlayerToWorld);

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
    }
    hintedPlayers.clear();

    super.shutdown();
  }

  private void handleAddPlayerToWorld(AddPlayerToWorldEvent event) {
    PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
    if (playerRef == null) {
      return;
    }

    if (!hintedPlayers.add(playerRef.getUuid())) {
      return;
    }

    boolean shouldHint = SimViewAccessControl.isSingleplayerOwner(playerRef) || SimViewAccessControl.isAdmin(playerRef);
    if (!shouldHint) {
      return;
    }

    playerRef.sendMessage(
        Message.raw("Tip: Use /simview to customize your view and simulation distances.").color("yellow"));
  }

}
