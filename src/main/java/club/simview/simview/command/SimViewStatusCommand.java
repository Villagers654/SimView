package club.simview.simview.command;

import club.simview.simview.config.SimViewConfig;
import club.simview.simview.service.SimViewAutoTuner;
import club.simview.simview.service.SimViewDistanceService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class SimViewStatusCommand extends AbstractPlayerCommand {

  private final SimViewDistanceService distanceService;

  public SimViewStatusCommand(SimViewDistanceService distanceService) {
    super("simview", "Shows SimView chunk visibility status");
    this.distanceService = distanceService;
    this.setPermissionGroups("hytale:None");
  }

  @Override
  protected void execute(
      CommandContext commandContext,
      Store<EntityStore> store,
      Ref<EntityStore> ref,
      PlayerRef playerRef,
      World world) {
    SimViewConfig config = distanceService.current();
    Player player = store.getComponent(ref, Player.getComponentType());
    ChunkTracker chunkTracker = playerRef.getChunkTracker();
    EntityViewer entityViewer = store.getComponent(ref, EntityViewer.getComponentType());

    int requestedViewDistance = player == null ? 0 : player.getClientViewRadius();
    int serverLimitedViewDistance = player == null ? 0 : player.getViewRadius();
    int configuredHytaleViewDistance = distanceService.hytaleSimulationDistanceChunks();
    int activeSimulationDistance = distanceService.activeSimulationDistanceCap();
    int effectiveViewDistance =
        config.effectiveExtendedViewDistance(
            activeSimulationDistance,
            requestedViewDistance,
            distanceService.activeTargetViewDistanceChunks());
    int effectiveSimulationDistance =
        config.effectiveSimulationDistance(
            activeSimulationDistance, requestedViewDistance, serverLimitedViewDistance);
    int viewOnlyDistance = Math.max(0, effectiveViewDistance - effectiveSimulationDistance);
    int entityViewBlocks = entityViewer == null ? 0 : entityViewer.viewRadiusBlocks;
    SimViewAutoTuner.AutoTuneSnapshot autoTuneSnapshot = distanceService.autoTuneSnapshot();

    String status =
        "SimView "
            + (config.enabled() ? "enabled" : "disabled")
            + "\nConfigured simulation distance (Hytale): "
            + formatDistance(configuredHytaleViewDistance)
            + "\nActive simulation distance (Hytale): "
            + formatDistance(activeSimulationDistance)
            + "\nConfigured simulation distance (SimView): "
            + formatDistance(config.targetSimulationDistanceChunks())
            + "\nActive simulation distance (SimView): "
            + formatDistance(distanceService.activeTargetSimulationDistanceChunks())
            + "\nConfigured view distance (SimView): "
            + formatDistance(config.targetViewDistanceChunks())
            + "\nActive view distance (SimView): "
            + formatDistance(distanceService.activeTargetViewDistanceChunks())
            + "\nPlayer effective simulation distance: "
            + formatDistance(serverLimitedViewDistance)
            + "\nView distance cap (SimView): "
            + formatDistance(distanceService.activeViewDistanceCap())
            + "\nplayer requested view: "
            + formatDistance(requestedViewDistance)
            + "\nEffective view distance: "
            + formatDistance(effectiveViewDistance)
            + "\nview-only cold ring: "
            + formatDistance(viewOnlyDistance)
            + "\nentity visibility: "
            + entityViewBlocks
            + " blocks"
            + "\nloaded/loading chunks: "
            + chunkTracker.getLoadedChunksCount()
            + "/"
            + chunkTracker.getLoadingChunksCount()
            + "\nchunk send budget: "
            + chunkTracker.getMaxChunksPerSecond()
            + "/sec, "
            + chunkTracker.getMaxChunksPerTick()
            + "/tick"
            + "\ncold chunk budget: "
            + config.maxChunkSendsPerSecond()
            + "/sec, "
            + config.maxChunkSendsPerTick()
            + "/tick, "
            + config.maxColdChunkLoadsInFlight()
            + " in flight"
            + "\nview auto adjust mode: "
            + config.adjustmentMode().name().toLowerCase()
            + ", last check: "
            + autoTuneSnapshot.lastViewCandidate().name().toLowerCase()
            + "\nsimulation auto adjust mode: "
            + config.simulationAdjustmentMode().name().toLowerCase()
            + ", last check: "
            + autoTuneSnapshot.lastSimulationCandidate().name().toLowerCase()
            + ", mspt: "
            + String.format("%.2f", autoTuneSnapshot.mspt())
            + "\nview auto adjust cold chunks: "
            + autoTuneSnapshot.estimatedColdChunks()
            + "/"
            + autoTuneSnapshot.proactiveColdChunkTarget()
            + " target"
            + "\nsimulation auto adjust ticking chunks: "
            + autoTuneSnapshot.estimatedTickingChunks()
            + "/"
            + autoTuneSnapshot.proactiveTickingChunkTarget()
            + " target"
            + "\nview auto adjust counters: +"
            + autoTuneSnapshot.viewConsecutiveIncreaseChecks()
            + " / -"
            + autoTuneSnapshot.viewConsecutiveDecreaseChecks()
            + "\nsimulation auto adjust counters: +"
            + autoTuneSnapshot.simulationConsecutiveIncreaseChecks()
            + " / -"
            + autoTuneSnapshot.simulationConsecutiveDecreaseChecks();

    commandContext.sendMessage(Message.raw(status).color("yellow"));
  }

  private static String formatDistance(int chunks) {
    return chunks + " chunks (" + chunks * SimViewConfig.CHUNK_SIZE_BLOCKS + " blocks)";
  }
}
