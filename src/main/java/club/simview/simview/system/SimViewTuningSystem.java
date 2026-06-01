package club.simview.simview.system;

import club.simview.simview.config.SimViewConfig;
import club.simview.simview.service.SimViewColdChunkStreamer;
import club.simview.simview.service.SimViewDistanceService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.packets.setup.ViewRadius;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;

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
  private final SimViewColdChunkStreamer coldChunkStreamer;
  private final ConcurrentHashMap<UUID, Integer> advertisedViewDistanceBlocks = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Integer> preferredRequestedViewDistanceChunks =
      new ConcurrentHashMap<>();

  public SimViewTuningSystem(SimViewDistanceService distanceService, SimViewColdChunkStreamer coldChunkStreamer) {
    this.distanceService = distanceService;
    this.coldChunkStreamer = coldChunkStreamer;
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
    return maybeUseParallel(entityCount, chunkCount);
  }

  @Override
  public void tick(
      float deltaSeconds,
      int index,
      ArchetypeChunk<EntityStore> chunk,
      Store<EntityStore> store,
      CommandBuffer<EntityStore> commandBuffer) {
    SimViewConfig config = distanceService.current();
    Player player = chunk.getComponent(index, PLAYER_COMPONENT_TYPE);
    PlayerRef playerRef = chunk.getComponent(index, PLAYER_REF_COMPONENT_TYPE);
    ChunkTracker chunkTracker = chunk.getComponent(index, CHUNK_TRACKER_COMPONENT_TYPE);
    EntityViewer entityViewer = chunk.getComponent(index, ENTITY_VIEWER_COMPONENT_TYPE);
    TransformComponent transformComponent = chunk.getComponent(index, TRANSFORM_COMPONENT_TYPE);
    World world = store.getExternalData().getWorld();

    int rawRequestedViewDistance = Math.max(0, player.getClientViewRadius());
    int serverLimitedViewDistance = Math.max(0, player.getViewRadius());
    int activeSimulationTarget = distanceService.activeTargetSimulationDistanceChunks();
    int activeViewTarget = distanceService.activeTargetViewDistanceChunks();
    int activeSimulationDistance =
        config.simulationDistanceCap(distanceService.hytaleSimulationDistanceChunks(), activeSimulationTarget);
    int requestedViewDistance =
        resolveRequestedViewDistance(
            playerRef.getUuid(), rawRequestedViewDistance, activeSimulationDistance, activeViewTarget);
    int effectiveViewDistance =
        config.effectiveExtendedViewDistance(activeSimulationDistance, requestedViewDistance, activeViewTarget);
    int effectiveSimulationDistance =
        config.effectiveSimulationDistance(activeSimulationDistance, requestedViewDistance);

    distanceService.observePlayerTick(
        playerRef.getUuid(),
        playerRef.getWorldUuid(),
        world.getTick(),
        deltaSeconds,
        requestedViewDistance);

    if (!config.enabled() || effectiveViewDistance <= effectiveSimulationDistance) {
      advertiseViewDistance(playerRef, serverLimitedViewDistance);
      coldChunkStreamer.unload(playerRef);
      restoreVanillaTuning(playerRef, chunkTracker, entityViewer, serverLimitedViewDistance);
      return;
    }

    setIfChangedMinLoadedChunksRadius(chunkTracker, 0);
    setIfChangedMaxHotLoadedChunksRadius(chunkTracker, effectiveSimulationDistance);
    setIfChangedDefaultMaxChunksPerSecond(playerRef, chunkTracker);
    setIfChangedMaxChunksPerTick(chunkTracker, ChunkTracker.MAX_CHUNKS_PER_TICK);

    int entityViewDistance =
        config.despawnEntitiesInColdChunks() ? effectiveSimulationDistance : effectiveViewDistance;
    setEntityViewRadiusBlocks(entityViewer, entityViewDistance);
    if (!chunkTracker.isReadyForChunks()
        || !playerRef.getPacketHandler().getChannel(NetworkChannel.Chunks).isWritable()) {
      advertisedViewDistanceBlocks.remove(playerRef.getUuid());
      return;
    }
    if (advertiseViewDistance(playerRef, effectiveViewDistance)) {
      return;
    }

    Vector3d position = transformComponent.getPosition();
    coldChunkStreamer.tick(
        world, playerRef, position, deltaSeconds, config, effectiveSimulationDistance, effectiveViewDistance);
  }

  private static void restoreVanillaTuning(
      PlayerRef playerRef, ChunkTracker chunkTracker, EntityViewer entityViewer, int serverLimitedViewDistance) {
    setIfChangedDefaultMaxChunksPerSecond(playerRef, chunkTracker);
    setIfChangedMaxChunksPerTick(chunkTracker, ChunkTracker.MAX_CHUNKS_PER_TICK);
    setIfChangedMinLoadedChunksRadius(chunkTracker, ChunkTracker.MIN_LOADED_CHUNKS_RADIUS);
    setIfChangedMaxHotLoadedChunksRadius(chunkTracker, serverLimitedViewDistance);
    setEntityViewRadiusBlocks(entityViewer, serverLimitedViewDistance);
  }

  private static void setIfChangedDefaultMaxChunksPerSecond(PlayerRef playerRef, ChunkTracker chunkTracker) {
    if (chunkTracker.getMaxChunksPerSecond() != defaultMaxChunksPerSecond(playerRef)) {
      chunkTracker.setDefaultMaxChunksPerSecond(playerRef);
    }
  }

  private static int defaultMaxChunksPerSecond(PlayerRef playerRef) {
    if (playerRef.getPacketHandler().isLocalConnection()) {
      return ChunkTracker.MAX_CHUNKS_PER_SECOND_LOCAL;
    }
    if (playerRef.getPacketHandler().isLANConnection()) {
      return ChunkTracker.MAX_CHUNKS_PER_SECOND_LAN;
    }
    return ChunkTracker.MAX_CHUNKS_PER_SECOND;
  }

  private static void setIfChangedMaxChunksPerSecond(ChunkTracker chunkTracker, int maxChunksPerSecond) {
    if (chunkTracker.getMaxChunksPerSecond() != maxChunksPerSecond) {
      chunkTracker.setMaxChunksPerSecond(maxChunksPerSecond);
    }
  }

  private static void setIfChangedMaxChunksPerTick(ChunkTracker chunkTracker, int maxChunksPerTick) {
    if (chunkTracker.getMaxChunksPerTick() != maxChunksPerTick) {
      chunkTracker.setMaxChunksPerTick(maxChunksPerTick);
    }
  }

  private static void setIfChangedMinLoadedChunksRadius(ChunkTracker chunkTracker, int minLoadedChunksRadius) {
    if (chunkTracker.getMinLoadedChunksRadius() != minLoadedChunksRadius) {
      chunkTracker.setMinLoadedChunksRadius(minLoadedChunksRadius);
    }
  }

  private static void setIfChangedMaxHotLoadedChunksRadius(ChunkTracker chunkTracker, int maxHotLoadedChunksRadius) {
    if (chunkTracker.getMaxHotLoadedChunksRadius() != maxHotLoadedChunksRadius) {
      chunkTracker.setMaxHotLoadedChunksRadius(maxHotLoadedChunksRadius);
    }
  }

  private static void setEntityViewRadiusBlocks(EntityViewer entityViewer, int viewRadiusChunks) {
    int viewRadiusBlocks = viewRadiusBlocks(viewRadiusChunks);
    if (entityViewer.viewRadiusBlocks != viewRadiusBlocks) {
      entityViewer.viewRadiusBlocks = viewRadiusBlocks;
    }
  }

  private static int viewRadiusBlocks(int viewRadiusChunks) {
    long viewRadiusBlocks = (long) Math.max(0, viewRadiusChunks) * SimViewConfig.CHUNK_SIZE_BLOCKS;
    return viewRadiusBlocks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) viewRadiusBlocks;
  }

  private int resolveRequestedViewDistance(
      UUID playerUuid,
      int rawRequestedViewDistance,
      int activeSimulationDistance,
      int activeViewTarget) {
    if (playerUuid == null) {
      return Math.max(0, rawRequestedViewDistance);
    }

    int rawRequested = Math.max(0, rawRequestedViewDistance);
    int simulationDistance = Math.max(0, activeSimulationDistance);

    // Runtime simulation-cap updates can temporarily force the reported client radius down to the
    // hot radius. Keep the last known preferred radius unless the player explicitly picks a lower
    // value that still sits above the simulation distance.
    return preferredRequestedViewDistanceChunks.compute(
        playerUuid,
        (uuid, previous) -> {
          if (previous == null || rawRequested >= previous) {
            return rawRequested;
          }
          if (rawRequested > simulationDistance) {
            return rawRequested;
          }
          if (activeViewTarget <= simulationDistance) {
            return rawRequested;
          }
          return previous;
        });
  }

  private boolean advertiseViewDistance(PlayerRef playerRef, int viewRadiusChunks) {
    if (!playerRef.isValid()) {
      UUID playerUuid = playerRef.getUuid();
      advertisedViewDistanceBlocks.remove(playerUuid);
      preferredRequestedViewDistanceChunks.remove(playerUuid);
      return false;
    }

    UUID playerUuid = playerRef.getUuid();
    int viewRadiusBlocks = viewRadiusBlocks(viewRadiusChunks);
    Integer previous = advertisedViewDistanceBlocks.get(playerUuid);
    if (previous != null && previous == viewRadiusBlocks) {
      return false;
    }

    advertisedViewDistanceBlocks.put(playerUuid, viewRadiusBlocks);
    playerRef.getPacketHandler().writeNoCache(new ViewRadius(viewRadiusBlocks));
    return true;
  }
}
