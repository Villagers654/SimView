package net.modtale.simview.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerChunkTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import net.modtale.simview.config.SimViewDistances;
import net.modtale.simview.config.SimViewStreamingMode;
import net.modtale.simview.service.SimViewDiskStreamer;
import net.modtale.simview.service.SimViewDistanceService;
import net.modtale.simview.service.SimViewStreamingBudget;

public final class SimViewDiskStreamingSystem extends EntityTickingSystem<EntityStore> {
  private final SimViewDistanceService distances;
  private final SimViewDiskStreamer streamer;

  public SimViewDiskStreamingSystem(SimViewDistanceService distances, SimViewDiskStreamer streamer) {
    this.distances = distances;
    this.streamer = streamer;
  }

  @Override public Query<EntityStore> getQuery() {
    return Query.and(Player.getComponentType(), PlayerRef.getComponentType(), ChunkTracker.getComponentType(),
        TransformComponent.getComponentType());
  }

  @Override public boolean isParallel(int entityCount, int chunkCount) { return false; }

  @Override public Set<Dependency<EntityStore>> getDependencies() {
    // Native unload packets must be flushed before disk terrain replaces those columns.
    return Set.of(new SystemDependency<>(Order.AFTER, PlayerChunkTrackerSystems.UpdateSystem.class),
        new SystemDependency<>(Order.AFTER, SimViewTuningSystem.class));
  }

  @Override public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk,
      Store<EntityStore> store, CommandBuffer<EntityStore> commands) {
    var config = distances.current();
    if (!config.enabled() || config.streamingMode() != SimViewStreamingMode.DISK) { return; }
    Player player = chunk.getComponent(index, Player.getComponentType());
    PlayerRef ref = chunk.getComponent(index, PlayerRef.getComponentType());
    ChunkTracker tracker = chunk.getComponent(index, ChunkTracker.getComponentType());
    TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
    int requested = SimViewDistances.sectionsToBlocks(player.getClientViewRadius());
    int cap = distances.activeSimulationDistanceCap();
    streamer.tick(store.getExternalData().getWorld(), ref, tracker, transform.getPosition(), dt,
        config.effectiveSimulationDistance(cap, requested),
        config.effectiveExtendedViewDistance(cap, requested, distances.activeTargetViewDistanceBlocks()),
        new SimViewStreamingBudget.Budget(tracker.getMaxSectionsPerSecond(), tracker.getMaxSectionsPerTick()));
  }
}
