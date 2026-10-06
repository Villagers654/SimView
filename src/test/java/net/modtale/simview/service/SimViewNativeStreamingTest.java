package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.math.iterator.SphereOffsets;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker.ChunkVisibility;
import net.modtale.simview.config.TestConfigs;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SimViewNativeStreamingTest {
  @Test void nativeColdLoadsDoNotRequestTickingButHotLoadsDo() throws Exception {
    var tracker = new ChunkTracker(() -> SphereOffsets.build(8));
    tracker.setMinLoadedRadius(8);
    tracker.setMaxHotLoadedRadius(2);
    // Section loading runs after native column tracking has been initialized.
    var columnsField = ChunkTracker.class.getDeclaredField("trackedColumns");
    columnsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var columns = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<Object>) columnsField.get(tracker);
    var columnConstructor = Class.forName(ChunkTracker.class.getName() + "$TrackedColumn").getDeclaredConstructor();
    columnConstructor.setAccessible(true);
    for (int x : new int[] {1, 4}) {
      columns.put(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(x, 0), columnConstructor.newInstance());
    }
    var chunks = mock(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.class);
    when(chunks.getChunkSectionReferenceAsync(anyInt(), anyInt(), anyInt(), anyInt()))
        .thenAnswer(invocation -> new java.util.concurrent.CompletableFuture<>());
    var entities = mock(com.hypixel.hytale.server.core.universe.world.storage.EntityStore.class);
    when(entities.getWorld()).thenReturn(mock(com.hypixel.hytale.server.core.universe.world.World.class));
    @SuppressWarnings("unchecked")
    var accessor = (com.hypixel.hytale.component.ComponentAccessor<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>)
        mock(com.hypixel.hytale.component.ComponentAccessor.class);
    when(accessor.getExternalData()).thenReturn(entities);
    var player = mock(com.hypixel.hytale.server.core.universe.PlayerRef.class);
    var transform = mock(TransformComponent.class);
    when(transform.getPosition()).thenReturn(new Vector3d());
    tracker.tryLoadSectionAsync(chunks, player, 4, 0, 0, transform, accessor);
    verify(chunks).getChunkSectionReferenceAsync(4, 0, 0,
        com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags.NO_SET_TICKING_SYNC
            | com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags.POLL_STILL_NEEDED);
    tracker.tryLoadSectionAsync(chunks, player, 1, 0, 0, transform, accessor);
    verify(chunks).getChunkSectionReferenceAsync(1, 0, 0,
        com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags.NO_SET_TICKING_SYNC
            | com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags.POLL_STILL_NEEDED
            | com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags.SET_TICKING);
  }

  @Test void nativeTickingSystemsExcludeNonTickingArchetypes() {
    var registry = new com.hypixel.hytale.component.ComponentRegistry<Object>();
    var nonTicking = registry.getNonTickingComponentType();
    var system = new com.hypixel.hytale.component.system.tick.EntityTickingSystem<Object>() {
      @Override public com.hypixel.hytale.component.query.Query<Object> getQuery() {
        return com.hypixel.hytale.component.query.Query.any();
      }
      @Override public void tick(float dt, int index, com.hypixel.hytale.component.ArchetypeChunk<Object> chunk,
          com.hypixel.hytale.component.Store<Object> store, com.hypixel.hytale.component.CommandBuffer<Object> commands) {
        fail("Query verification should not execute ticks");
      }
    };
    assertTrue(system.test(registry, com.hypixel.hytale.component.Archetype.empty()));
    assertFalse(system.test(registry, com.hypixel.hytale.component.Archetype.of(nonTicking)));
  }

  @Test void nativeTrackerSeparatesHotAndColdSectionsInThreeDimensions() throws Exception {
    ChunkTracker tracker = new ChunkTracker(() -> SphereOffsets.build(8));
    TransformComponent transform = mock(TransformComponent.class);
    when(transform.getPosition()).thenReturn(new Vector3d());
    var field = ChunkTracker.class.getDeclaredField("transformComponent");
    field.setAccessible(true);
    field.set(tracker, transform);
    var iteratorField = ChunkTracker.class.getDeclaredField("spiralIterator");
    iteratorField.setAccessible(true);
    ((com.hypixel.hytale.math.iterator.SphereSpiralIterator) iteratorField.get(tracker)).configure(8);
    tracker.setMinLoadedRadius(8);
    tracker.setMaxHotLoadedRadius(2);
    assertEquals(ChunkVisibility.HOT, tracker.getSectionVisibility(0, 1, 0));
    assertEquals(ChunkVisibility.COLD, tracker.getSectionVisibility(0, 4, 0));
    // Legacy world-chunk ticking is column based, even when section streaming is 3D.
    assertEquals(ChunkVisibility.HOT,
        tracker.getChunkVisibility(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(0, 0)));
    assertEquals(ChunkVisibility.COLD, tracker.getSectionVisibility(4, 0, 0));
    assertEquals(ChunkVisibility.COLD,
        tracker.getChunkVisibility(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(4, 0)));
    assertEquals(ChunkVisibility.NONE, tracker.getSectionVisibility(0, 9, 0));
  }

  @Test void capturedTrackerSettingsRestoreEveryChangedValue() {
    ChunkTracker tracker = new ChunkTracker(() -> SphereOffsets.build(8));
    tracker.setMaxSectionsPerSecond(300);
    tracker.setMaxSectionsPerTick(20);
    var original = SimViewTrackerSettings.capture(tracker);
    tracker.setMaxSectionsPerSecond(24);
    tracker.setMaxSectionsPerTick(2);
    tracker.setMinLoadedRadius(16);
    tracker.setMaxHotLoadedRadius(3);
    original.restore(tracker);
    assertEquals(original, SimViewTrackerSettings.capture(tracker));
  }

  @Test void verticalMovementUsesSpeedingBudgetAndRecovers() {
    var config = TestConfigs.config("""
        {"section-streaming":{"budget":{"section-sends-per-second":96,"section-sends-per-tick":8}},
         "speeding-adjustments":{"cooldown-ticks":2,
           "budget":{"section-sends-per-second":24,"section-sends-per-tick":2}}}
        """);
    var budgets = new SimViewStreamingBudget();
    var nativeBudget = new SimViewStreamingBudget.Budget(360, 40);
    assertEquals(config.maxSectionSendsPerTick(), budgets.update(new Vector3d(), config, nativeBudget).perTick());
    assertEquals(config.speedingSectionSendsPerTick(), budgets.update(new Vector3d(0, 10, 0), config, nativeBudget).perTick());
    budgets.update(new Vector3d(0, 10, 0), config, nativeBudget);
    assertEquals(config.maxSectionSendsPerTick(), budgets.update(new Vector3d(0, 10, 0), config, nativeBudget).perTick());
  }

  @Test void defaultsPreserveNativeConnectionBudgetsEvenDuringFastMovement() {
    var config = TestConfigs.config("{}");
    for (int perSecond : new int[] {ChunkTracker.MAX_SECTIONS_PER_SECOND_LOCAL,
        ChunkTracker.MAX_SECTIONS_PER_SECOND_LAN, ChunkTracker.MAX_SECTIONS_PER_SECOND}) {
      var nativeBudget = new SimViewStreamingBudget.Budget(perSecond, ChunkTracker.MAX_SECTIONS_PER_TICK);
      var budgets = new SimViewStreamingBudget();
      for (int tick = 0; tick < 100; tick++) {
        assertEquals(nativeBudget, budgets.update(new Vector3d(tick * 10, tick * 10, 0), config, nativeBudget));
      }
    }
  }

  @Test void captureInitializesNativeBudgetsWithoutReplacingExistingOverrides() {
    int[] rates = {ChunkTracker.MAX_SECTIONS_PER_SECOND_LOCAL,
        ChunkTracker.MAX_SECTIONS_PER_SECOND_LAN, ChunkTracker.MAX_SECTIONS_PER_SECOND};
    for (int connection = 0; connection < rates.length; connection++) {
      var handler = mock(com.hypixel.hytale.server.core.io.PacketHandler.class);
      when(handler.isLocalConnection()).thenReturn(connection == 0);
      when(handler.isLANConnection()).thenReturn(connection == 1);
      var player = mock(com.hypixel.hytale.server.core.universe.PlayerRef.class);
      when(player.getPacketHandler()).thenReturn(handler);
      var tracker = new ChunkTracker(() -> SphereOffsets.build(8));
      var original = SimViewTrackerSettings.captureForTuning(tracker, player);
      assertEquals(rates[connection], original.perSecond());
      assertEquals(ChunkTracker.MAX_SECTIONS_PER_TICK, original.perTick());
      tracker.setMaxSectionsPerSecond(96);
      assertEquals(96, SimViewTrackerSettings.captureForTuning(tracker, player).perSecond());
    }
  }
}
