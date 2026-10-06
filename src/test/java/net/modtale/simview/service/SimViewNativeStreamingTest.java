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
    assertEquals(ChunkVisibility.COLD, tracker.getSectionVisibility(4, 0, 0));
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
    var config = TestConfigs.config("{\"speeding-adjustments\":{\"cooldown-ticks\":2}}");
    var budgets = new SimViewStreamingBudget();
    assertEquals(config.maxSectionSendsPerTick(), budgets.update(new Vector3d(), config).perTick());
    assertEquals(config.speedingSectionSendsPerTick(), budgets.update(new Vector3d(0, 10, 0), config).perTick());
    budgets.update(new Vector3d(0, 10, 0), config);
    assertEquals(config.maxSectionSendsPerTick(), budgets.update(new Vector3d(0, 10, 0), config).perTick());
  }
}
