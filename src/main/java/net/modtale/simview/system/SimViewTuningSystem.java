package net.modtale.simview.system;

import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.config.SimViewDistances;
import net.modtale.simview.service.SimViewDistanceService;
import net.modtale.simview.service.SimViewStreamingBudget;
import net.modtale.simview.service.SimViewTrackerSettings;
import net.modtale.simview.service.SimViewDiskStreamer;
import net.modtale.simview.config.SimViewStreamingMode;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.OrderPriority;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.setup.ViewRadius;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerChunkTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SimViewTuningSystem extends EntityTickingSystem<EntityStore> {

  private static final ComponentType<EntityStore, Player> PLAYER_COMPONENT_TYPE = Player.getComponentType();
  private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF_COMPONENT_TYPE = PlayerRef.getComponentType();
  private static final ComponentType<EntityStore, ChunkTracker> CHUNK_TRACKER_COMPONENT_TYPE =
      ChunkTracker.getComponentType();
  private static final ComponentType<EntityStore, EntityViewer> ENTITY_VIEWER_COMPONENT_TYPE =
      EntityViewer.getComponentType();
  private static final ComponentType<EntityStore, TransformComponent> TRANSFORM_COMPONENT_TYPE =
      TransformComponent.getComponentType();

  private final SimViewDistanceService distanceService;
  private final SimViewDiskStreamer diskStreamer;
  private final ConcurrentHashMap<UUID, PlayerTuningState> tuningStates = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public SimViewTuningSystem(SimViewDistanceService distanceService, SimViewDiskStreamer diskStreamer) {
    this.distanceService = distanceService;
    this.diskStreamer = diskStreamer;
  }

  @Override
  public Query<EntityStore> getQuery() {
    return Query.and(
        PLAYER_COMPONENT_TYPE,
        PLAYER_REF_COMPONENT_TYPE,
        CHUNK_TRACKER_COMPONENT_TYPE,
        ENTITY_VIEWER_COMPONENT_TYPE,
        TRANSFORM_COMPONENT_TYPE);
  }

  @Override
  public boolean isParallel(int entityCount, int chunkCount) {
    return false;
  }

  @Override
  public Set<Dependency<EntityStore>> getDependencies() {
    // FURTHEST schedules this system at the start of the tick. With NORMAL priority the native trackers wait for
    // this late-registered plugin system, letting unrelated systems (like damage) tick between ClearEntityViewers
    // and CollectVisible while every entity viewer is empty, which kicks attacking players.
    return Set.of(
        new SystemDependency<>(Order.BEFORE, PlayerChunkTrackerSystems.UpdateSystem.class, OrderPriority.FURTHEST),
        new SystemDependency<>(Order.BEFORE, EntityTrackerSystems.CollectVisible.class, OrderPriority.FURTHEST));
  }

  @Override
  public synchronized void tick(
      float deltaSeconds,
      int index,
      ArchetypeChunk<EntityStore> chunk,
      Store<EntityStore> store,
      CommandBuffer<EntityStore> commandBuffer) {
    if (closed) {
      return;
    }
    SimViewConfig config = distanceService.current();
    Player player = chunk.getComponent(index, PLAYER_COMPONENT_TYPE);
    PlayerRef playerRef = chunk.getComponent(index, PLAYER_REF_COMPONENT_TYPE);
    ChunkTracker chunkTracker = chunk.getComponent(index, CHUNK_TRACKER_COMPONENT_TYPE);
    EntityViewer entityViewer = chunk.getComponent(index, ENTITY_VIEWER_COMPONENT_TYPE);
    TransformComponent transformComponent = chunk.getComponent(index, TRANSFORM_COMPONENT_TYPE);
    World world = store.getExternalData().getWorld();
    UUID playerUuid = playerRef.getUuid();
    PlayerTuningState tuningState = stateFor(playerUuid);
    if (tuningState == null) {
      return;
    }
    if (config.enabled() && !tuningState.modified) {
      tuningState.originalSettings = SimViewTrackerSettings.captureForTuning(chunkTracker, playerRef);
    }
    tuningState.world = world;
    tuningState.playerRef = playerRef;
    tuningState.player = player;
    tuningState.chunkTracker = chunkTracker;
    tuningState.entityViewer = entityViewer;

    int rawRequestedViewDistance = SimViewDistances.sectionsToBlocks(player.getClientViewRadius());
    int activeSimulationTarget = distanceService.activeTargetSimulationDistanceBlocks();
    int activeViewTarget = distanceService.activeTargetViewDistanceBlocks();
    int activeSimulationDistance =
        config.simulationDistanceCap(distanceService.hytaleSimulationDistanceBlocks(), activeSimulationTarget);
    int requestedViewDistance = rawRequestedViewDistance;
    invalidateAdvertisedViewDistanceOnRuntimeChange(tuningState);

    int serverLimitedViewDistance = SimViewDistances.sectionsToBlocks(player.getViewRadius());
    int effectiveViewDistance =
        config.effectiveExtendedViewDistance(activeSimulationDistance, requestedViewDistance, activeViewTarget);
    int effectiveSimulationDistance =
        config.effectiveSimulationDistance(activeSimulationDistance, requestedViewDistance);

    distanceService.observePlayerTick(
        playerUuid,
        playerRef.getWorldUuid(),
        world.getTick(),
        deltaSeconds,
        requestedViewDistance);

    if (!config.enabled()) {
      diskStreamer.leave(playerRef);
      advertiseViewDistance(playerRef, tuningState, serverLimitedViewDistance);
      tuningState.restore();
      return;
    }

    tuningState.modified = true;
    if (config.streamingMode() != SimViewStreamingMode.DISK) { diskStreamer.leave(playerRef); }
    setIfChangedMinLoadedRadius(chunkTracker,
        config.nativeLoadingDistance(effectiveSimulationDistance, effectiveViewDistance));
    setIfChangedMaxHotLoadedRadius(chunkTracker, effectiveSimulationDistance);
    SimViewStreamingBudget.Budget budget = tuningState.streamingBudget.update(
        transformComponent.getPosition(), config,
        new SimViewStreamingBudget.Budget(tuningState.originalSettings.perSecond(), tuningState.originalSettings.perTick()));
    setIfChangedMaxSectionsPerSecond(chunkTracker, budget.perSecond());
    setIfChangedMaxSectionsPerTick(chunkTracker, budget.perTick());

    int entityViewDistance =
        config.despawnEntitiesInColdChunks() ? effectiveSimulationDistance : effectiveViewDistance;
    setEntityViewRadiusBlocks(entityViewer, entityViewDistance);
    if (!chunkTracker.isReadyForChunks()
        || !playerRef.getPacketHandler().getChannel(StreamType.Game).isWritable()) {
      if (tuningState != null) {
        tuningState.advertisedViewDistanceBlocks = -1;
      }
      return;
    }
    if (advertiseViewDistance(playerRef, tuningState, effectiveViewDistance)) {
      return;
    }

  }

  public synchronized void unload(PlayerRef playerRef) {
    UUID playerUuid = playerRef.getUuid();
    if (playerUuid != null) {
      diskStreamer.discard(playerUuid);
      tuningStates.remove(playerUuid);
    }
  }

  public synchronized void restoreBeforeTransfer(PlayerRef playerRef) {
    diskStreamer.leave(playerRef);
    PlayerTuningState state = tuningStates.remove(playerRef.getUuid());
    if (state != null && state.modified && state.originalSettings != null) {
      state.originalSettings.restore(state.chunkTracker);
      setEntityViewRadiusBlocks(state.entityViewer, SimViewDistances.sectionsToBlocks(state.player.getViewRadius()));
      state.modified = false;
    }
  }

  public synchronized void restoreAll() {
    closed = true;
    for (PlayerTuningState state : tuningStates.values()) {
      if (state.world != null && state.world.isAlive()) {
        try {
          state.world.execute(state::restore);
        } catch (RuntimeException exception) {
          state.world.getLogger().atWarning().withCause(exception)
              .log("SimView could not schedule tracker restoration while the world is stopping.");
        }
      }
    }
    tuningStates.clear();
  }

  private static void setIfChangedMaxSectionsPerSecond(ChunkTracker chunkTracker, int maxSectionsPerSecond) {
    if (chunkTracker.getMaxSectionsPerSecond() != maxSectionsPerSecond) {
      chunkTracker.setMaxSectionsPerSecond(maxSectionsPerSecond);
    }
  }

  private static void setIfChangedMaxSectionsPerTick(ChunkTracker chunkTracker, int maxSectionsPerTick) {
    if (chunkTracker.getMaxSectionsPerTick() != maxSectionsPerTick) {
      chunkTracker.setMaxSectionsPerTick(maxSectionsPerTick);
    }
  }

  private static void setIfChangedMinLoadedRadius(ChunkTracker chunkTracker, int minLoadedRadius) {
    if (chunkTracker.getMinLoadedRadius() != SimViewDistances.blocksToSections(minLoadedRadius)) {
      chunkTracker.setMinLoadedRadius(SimViewDistances.blocksToSections(minLoadedRadius));
    }
  }

  private static void setIfChangedMaxHotLoadedRadius(ChunkTracker chunkTracker, int maxHotLoadedRadius) {
    if (chunkTracker.getMaxHotLoadedRadius() != SimViewDistances.blocksToSections(maxHotLoadedRadius)) {
      chunkTracker.setMaxHotLoadedRadius(SimViewDistances.blocksToSections(maxHotLoadedRadius));
    }
  }

  private static void setEntityViewRadiusBlocks(EntityViewer entityViewer, int distanceBlocks) {
    int viewRadiusBlocks = viewRadiusBlocks(distanceBlocks);
    if (entityViewer.viewRadiusBlocks != viewRadiusBlocks) {
      entityViewer.viewRadiusBlocks = viewRadiusBlocks;
    }
  }

  private PlayerTuningState stateFor(UUID playerUuid) {
    if (playerUuid == null) {
      return null;
    }
    return tuningStates.computeIfAbsent(playerUuid, ignored -> new PlayerTuningState());
  }

  private void invalidateAdvertisedViewDistanceOnRuntimeChange(PlayerTuningState tuningState) {
    if (tuningState == null) {
      return;
    }

    long revision = distanceService.runtimeDistanceRevision();
    if (tuningState.runtimeDistanceRevision != revision) {
      tuningState.runtimeDistanceRevision = revision;
      tuningState.advertisedViewDistanceBlocks = -1;
    }
  }

  private static int viewRadiusBlocks(int distanceBlocks) {
    return Math.max(0, distanceBlocks);
  }

  private boolean advertiseViewDistance(
      PlayerRef playerRef, PlayerTuningState tuningState, int distanceBlocks) {
    if (!playerRef.isValid()) {
      unload(playerRef);
      return false;
    }

    int viewRadiusBlocks = viewRadiusBlocks(distanceBlocks);
    if (tuningState != null && tuningState.advertisedViewDistanceBlocks == viewRadiusBlocks) {
      return false;
    }

    if (tuningState != null) {
      tuningState.advertisedViewDistanceBlocks = viewRadiusBlocks;
    }
    playerRef.getPacketHandler().writeNoCache(new ViewRadius(viewRadiusBlocks));
    return true;
  }

  private static final class PlayerTuningState {
    private final SimViewStreamingBudget streamingBudget = new SimViewStreamingBudget();
    private boolean modified;
    private SimViewTrackerSettings originalSettings;
    private World world;
    private PlayerRef playerRef;
    private Player player;
    private ChunkTracker chunkTracker;
    private EntityViewer entityViewer;
    private int advertisedViewDistanceBlocks = -1;
    private long runtimeDistanceRevision = Long.MIN_VALUE;

    private void restore() {
      if (modified && originalSettings != null && playerRef.isValid()
          && world.getWorldConfig().getUuid().equals(playerRef.getWorldUuid())) {
        originalSettings.restore(chunkTracker);
        modified = false;
        setEntityViewRadiusBlocks(entityViewer, SimViewDistances.sectionsToBlocks(player.getViewRadius()));
        playerRef.getPacketHandler().writeNoCache(new ViewRadius(viewRadiusBlocks(SimViewDistances.sectionsToBlocks(player.getViewRadius()))));
      }
    }
  }
}
