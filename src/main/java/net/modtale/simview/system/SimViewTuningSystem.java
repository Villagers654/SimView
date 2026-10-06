package net.modtale.simview.system;

import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.service.SimViewDistanceService;
import net.modtale.simview.service.SimViewStreamingBudget;
import net.modtale.simview.service.SimViewTrackerSettings;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
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
  private final ConcurrentHashMap<UUID, PlayerTuningState> tuningStates = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public SimViewTuningSystem(SimViewDistanceService distanceService) {
    this.distanceService = distanceService;
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
    return Set.of(
        new SystemDependency<>(Order.BEFORE, PlayerChunkTrackerSystems.UpdateSystem.class),
        new SystemDependency<>(Order.BEFORE, EntityTrackerSystems.CollectVisible.class));
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
      tuningState.originalSettings = SimViewTrackerSettings.capture(chunkTracker);
    }
    tuningState.world = world;
    tuningState.playerRef = playerRef;
    tuningState.player = player;
    tuningState.chunkTracker = chunkTracker;
    tuningState.entityViewer = entityViewer;

    int rawRequestedViewDistance = Math.max(0, player.getClientViewRadius());
    int activeSimulationTarget = distanceService.activeTargetSimulationDistanceChunks();
    int activeViewTarget = distanceService.activeTargetViewDistanceChunks();
    int activeSimulationDistance =
        config.simulationDistanceCap(distanceService.hytaleSimulationDistanceChunks(), activeSimulationTarget);
    int requestedViewDistance = rawRequestedViewDistance;
    invalidateAdvertisedViewDistanceOnRuntimeChange(tuningState);

    int serverLimitedViewDistance = Math.max(0, player.getViewRadius());
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
      advertiseViewDistance(playerRef, tuningState, serverLimitedViewDistance);
      tuningState.restore();
      return;
    }

    tuningState.modified = true;
    setIfChangedMinLoadedRadius(chunkTracker, effectiveViewDistance);
    setIfChangedMaxHotLoadedRadius(chunkTracker, effectiveSimulationDistance);
    SimViewStreamingBudget.Budget budget = tuningState.streamingBudget.update(
        transformComponent.getPosition(), config);
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
      tuningStates.remove(playerUuid);
    }
  }

  public synchronized void restoreBeforeTransfer(PlayerRef playerRef) {
    PlayerTuningState state = tuningStates.remove(playerRef.getUuid());
    if (state != null && state.modified && state.originalSettings != null) {
      state.originalSettings.restore(state.chunkTracker);
      setEntityViewRadiusBlocks(state.entityViewer, state.player.getViewRadius());
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
    if (chunkTracker.getMinLoadedRadius() != minLoadedRadius) {
      chunkTracker.setMinLoadedRadius(minLoadedRadius);
    }
  }

  private static void setIfChangedMaxHotLoadedRadius(ChunkTracker chunkTracker, int maxHotLoadedRadius) {
    if (chunkTracker.getMaxHotLoadedRadius() != maxHotLoadedRadius) {
      chunkTracker.setMaxHotLoadedRadius(maxHotLoadedRadius);
    }
  }

  private static void setEntityViewRadiusBlocks(EntityViewer entityViewer, int viewRadiusChunks) {
    int viewRadiusBlocks = viewRadiusBlocks(viewRadiusChunks);
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

  private static int viewRadiusBlocks(int viewRadiusChunks) {
    long viewRadiusBlocks = (long) Math.max(0, viewRadiusChunks) * SimViewConfig.CHUNK_SIZE_BLOCKS;
    return viewRadiusBlocks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) viewRadiusBlocks;
  }

  private boolean advertiseViewDistance(
      PlayerRef playerRef, PlayerTuningState tuningState, int viewRadiusChunks) {
    if (!playerRef.isValid()) {
      unload(playerRef);
      return false;
    }

    int viewRadiusBlocks = viewRadiusBlocks(viewRadiusChunks);
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
        setEntityViewRadiusBlocks(entityViewer, player.getViewRadius());
        playerRef.getPacketHandler().writeNoCache(new ViewRadius(viewRadiusBlocks(player.getViewRadius())));
      }
    }
  }
}
