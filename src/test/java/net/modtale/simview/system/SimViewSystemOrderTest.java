package net.modtale.simview.system;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.DependencyGraph;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerChunkTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.modtale.simview.service.SimViewDiskStreamer;
import net.modtale.simview.service.SimViewDistanceService;
import org.junit.jupiter.api.Test;

class SimViewSystemOrderTest {
  /** Stands in for native systems without ordering constraints, such as the interaction tick that applies damage. */
  private static class UnorderedSystem extends EntityTickingSystem<EntityStore> {
    private final String name;
    UnorderedSystem(String name) { this.name = name; }
    @Override public String toString() { return name; }
    @Override public Query<EntityStore> getQuery() { return Query.any(); }
    @Override public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
        CommandBuffer<EntityStore> commands) {}
  }

  /** Stands in for the spatial index that CollectVisible queries. */
  private static final class SpatialSystem extends UnorderedSystem { SpatialSystem() { super("spatial"); } }

  @Test void entityViewersAreNeverEmptyWhileUnorderedSystemsTick() throws Exception {
    List<ISystem<EntityStore>> sorted = sort(true);
    int clear = indexOf(sorted, EntityTrackerSystems.ClearEntityViewers.class);
    int collect = indexOf(sorted, EntityTrackerSystems.CollectVisible.class);
    // Damage queues tracker updates for the attacker; between these two systems every viewer is empty and
    // EntityViewer#queueUpdate throws "Entity is not visible!", which kicks the player.
    for (int index = clear + 1; index < collect; index++) {
      assertFalse(sorted.get(index) instanceof UnorderedSystem && !(sorted.get(index) instanceof SpatialSystem),
          "Unordered system ticks with empty entity viewers: " + sorted);
    }
  }

  @Test void nativeSystemsKeepTheirVanillaOrder() throws Exception {
    // SimView deliberately orders itself before the chunk tracker update, so only that system may move.
    assertEquals(nativeOrder(sort(false)), nativeOrder(sort(true)));
  }

  @Test void tuningStillRunsBeforeNativeTrackers() throws Exception {
    List<ISystem<EntityStore>> sorted = sort(true);
    int tuning = indexOf(sorted, SimViewTuningSystem.class);
    assertTrue(tuning < indexOf(sorted, EntityTrackerSystems.CollectVisible.class));
    assertTrue(tuning < indexOf(sorted, PlayerChunkTrackerSystems.UpdateSystem.class));
    assertTrue(indexOf(sorted, PlayerChunkTrackerSystems.UpdateSystem.class)
        < indexOf(sorted, SimViewDiskStreamingSystem.class));
  }

  private static List<String> nativeOrder(List<ISystem<EntityStore>> sorted) {
    return sorted.stream()
        .filter(system -> !(system instanceof SimViewTuningSystem) && !(system instanceof SimViewDiskStreamingSystem)
            && !(system instanceof PlayerChunkTrackerSystems.UpdateSystem))
        .map(system -> system instanceof UnorderedSystem ? system.toString() : system.getClass().getSimpleName())
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<ISystem<EntityStore>> sort(boolean withSimView) throws Exception {
    try (var entityModule = mockStatic(EntityModule.class); var universe = mockStatic(Universe.class)) {
      entityModule.when(EntityModule::get).thenReturn(mock(EntityModule.class));
      universe.when(Universe::get).thenReturn(mock(Universe.class));
      var clear = allocate(EntityTrackerSystems.ClearEntityViewers.class);
      var collect = allocate(EntityTrackerSystems.CollectVisible.class);
      var dependencies = EntityTrackerSystems.CollectVisible.class.getDeclaredField("dependencies");
      dependencies.setAccessible(true);
      dependencies.set(collect, Set.<Dependency<EntityStore>>of(
          new SystemDependency<>(Order.AFTER, SpatialSystem.class)));
      var chunkTrackerUpdate = allocate(PlayerChunkTrackerSystems.UpdateSystem.class);
      List<ISystem<EntityStore>> systems = new java.util.ArrayList<>(List.of(
          new UnorderedSystem("unordered1"), new SpatialSystem(), new UnorderedSystem("unordered2"), clear, new UnorderedSystem("unordered3"), collect,
          new UnorderedSystem("unordered4"), chunkTrackerUpdate, new UnorderedSystem("unordered5")));
      if (withSimView) {
        // Plugins register after native modules, so SimView's systems come last.
        systems.add(new SimViewTuningSystem(mock(SimViewDistanceService.class), mock(SimViewDiskStreamer.class)));
        systems.add(new SimViewDiskStreamingSystem(mock(SimViewDistanceService.class), mock(SimViewDiskStreamer.class)));
      }
      var graph = new DependencyGraph<>(systems.toArray(ISystem[]::new));
      graph.resolveEdges(new ComponentRegistry<>());
      ISystem<EntityStore>[] sorted = new ISystem[systems.size()];
      graph.sort(sorted);
      return Arrays.asList(sorted);
    }
  }

  /** Builds native systems without their constructors, which need a running server. */
  private static <T> T allocate(Class<T> type) throws Exception {
    var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type));
  }

  private static int indexOf(List<ISystem<EntityStore>> systems, Class<?> type) {
    for (int index = 0; index < systems.size(); index++) {
      if (systems.get(index).getClass() == type) { return index; }
    }
    throw new AssertionError(type + " missing from " + systems);
  }
}
