package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class SimViewDiskGenerationTest {
  static org.mockito.MockedStatic<com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect> effects;
  @BeforeAll static void assets() throws Exception {
    var type = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.class;
    effects = mockStatic(type);
    Object map = mock(type.getMethod("getAssetMap").getReturnType());
    effects.when(() -> type.getMethod("getAssetMap").invoke(null)).thenReturn(map);
  }
  @AfterAll static void closeAssets() { effects.close(); }

  @SuppressWarnings("unchecked")
  static final class NativeWorld {
    final World world = mock(World.class);
    final WorldConfig config = mock(WorldConfig.class);
    final ChunkStore chunks = mock(ChunkStore.class);
    final Store<ChunkStore> store = mock(Store.class);
    final Ref<ChunkStore> column = mock(Ref.class), section = mock(Ref.class);
    final ChunkSavingSystems.Data saves = mock(ChunkSavingSystems.Data.class);
    final ArrayDeque<Runnable> retries = new ArrayDeque<>();
    final AtomicBoolean cancelled = new AtomicBoolean(), stopping = new AtomicBoolean();
    NativeWorld() {
      when(world.isAlive()).thenReturn(true);
      when(world.getWorldConfig()).thenReturn(config);
      when(config.canSaveChunks()).thenReturn(true);
      when(config.shouldSaveNewChunks()).thenReturn(true);
      when(config.canUnloadChunks()).thenReturn(true);
      when(world.getChunkStore()).thenReturn(chunks);
      when(chunks.getStore()).thenReturn(store);
      when(store.getResource(ChunkStore.SAVE_RESOURCE)).thenReturn(saves);
      when(column.isValid()).thenReturn(true); when(section.isValid()).thenReturn(true);
      when(chunks.getChunkReference(anyLong())).thenReturn(column);
      doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(world).execute(any());
    }
    SimViewDiskGeneration batch(IChunkLoader loader, int x, int z) {
      return new SimViewDiskGeneration(world, loader, x, z, cancelled::get, stopping::get, retries::addLast);
    }
  }

  @Test void coldGenerationHoldsAdmissionThroughFailedPersistenceAndClientCancellation() throws Exception {
    try (var stored = new SimViewDiskSnapshotReaderTest.Fixture()) {
      var nativeWorld = new NativeWorld();
      var loader = mock(IChunkLoader.class);
      var reads = new AtomicInteger();
      when(loader.loadHolder(3, 4)).thenAnswer(call -> switch (reads.getAndIncrement()) {
        case 0 -> CompletableFuture.completedFuture(null);
        case 1 -> CompletableFuture.failedFuture(new java.io.IOException("Storage unavailable"));
        case 2 -> CompletableFuture.completedFuture(null);
        default -> CompletableFuture.completedFuture(stored.root);
      });
      var shared = new CompletableFuture<Ref<ChunkStore>>();
      when(nativeWorld.chunks.getChunkReferenceAsync(ChunkUtil.indexChunk(3, 4), GetChunkFlags.NO_SET_TICKING_SYNC))
          .thenReturn(shared);
      when(nativeWorld.store.copySerializableEntity(nativeWorld.column)).thenReturn(stored.root);
      var batch = nativeWorld.batch(loader, 3, 4);
      var result = batch.finish(SimViewDiskSnapshotReader.load(loader, 3, 4, new int[] {0},
          Runnable::run, nativeWorld.cancelled::get, batch));
      assertFalse(result.isDone());
      shared.complete(nativeWorld.column);
      verify(nativeWorld.saves).push(nativeWorld.column);
      assertFalse(result.isDone()); assertEquals(1, nativeWorld.retries.size());
      nativeWorld.cancelled.set(true);
      nativeWorld.retries.removeFirst().run();
      assertFalse(result.isDone()); // A cancelled client cannot free a dirty generation slot.
      nativeWorld.retries.removeFirst().run();
      assertTrue(result.join().isEmpty()); assertTrue(nativeWorld.retries.isEmpty());
      assertFalse(shared.isCancelled());
      verify(nativeWorld.chunks).getChunkReferenceAsync(ChunkUtil.indexChunk(3, 4), GetChunkFlags.NO_SET_TICKING_SYNC);
      verify(nativeWorld.chunks, never()).getSaver();
      verify(nativeWorld.chunks, never()).remove(any(), any());
    }
  }

  @Test void cubicMissingSectionUsesNativeOwnershipAndWaitsForItsOwnSavedData() throws Exception {
    try (var stored = new SimViewDiskSnapshotReaderTest.Fixture()) {
      var nativeWorld = new NativeWorld();
      var loader = mock(IChunkLoader.Cubic.class);
      when(loader.loadHolder(-2, 4)).thenReturn(CompletableFuture.completedFuture(stored.root));
      when(loader.loadSectionHolder(-2, -3, 4)).thenReturn(CompletableFuture.completedFuture(null),
          CompletableFuture.completedFuture(stored.section));
      when(nativeWorld.chunks.getChunkSectionReferenceAsync(-2, -3, 4, GetChunkFlags.NO_SET_TICKING_SYNC))
          .thenReturn(CompletableFuture.completedFuture(nativeWorld.section));
      when(nativeWorld.store.copySerializableEntity(nativeWorld.section)).thenReturn(stored.section);
      var batch = nativeWorld.batch(loader, -2, 4);
      var packets = batch.finish(SimViewDiskSnapshotReader.load(loader, -2, 4, new int[] {-3}, Runnable::run,
          () -> false, batch)).join();
      var section = packets.stream().filter(SetChunk.class::isInstance).map(SetChunk.class::cast).findFirst().orElseThrow();
      assertEquals(-3, section.y);
      verify(nativeWorld.saves).push(nativeWorld.column);
      verify(nativeWorld.chunks).getChunkSectionReferenceAsync(-2, -3, 4, GetChunkFlags.NO_SET_TICKING_SYNC);
      verify(nativeWorld.chunks, never()).getChunkReferenceAsync(anyLong(), anyInt());
    }
  }

  @Test void failedSnapshotStillKeepsItsMaterializedTerrainAdmittedUntilSaved() {
    var nativeWorld = new NativeWorld();
    var loader = mock(IChunkLoader.class);
    var saved = new AtomicBoolean();
    when(loader.loadHolder(0, 0)).thenAnswer(call -> CompletableFuture.completedFuture(
        saved.get() ? mock(com.hypixel.hytale.component.Holder.class) : null));
    when(nativeWorld.chunks.getChunkReferenceAsync(anyLong(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(nativeWorld.column));
    when(nativeWorld.store.copySerializableEntity(nativeWorld.column))
        .thenThrow(new IllegalStateException("Snapshot failed"));
    var batch = nativeWorld.batch(loader, 0, 0);
    var result = batch.finish(batch.column().thenApply(holder -> List.<ToClientPacket>of()));
    assertFalse(result.isDone());
    verify(nativeWorld.saves).push(nativeWorld.column);
    saved.set(true);
    nativeWorld.retries.removeFirst().run();
    var failure = assertThrows(java.util.concurrent.CompletionException.class, result::join);
    assertEquals("Snapshot failed", failure.getCause().getMessage());
  }

  @Test void savingRestrictionsShutdownAndCancelledConfigRefuseNewGeneration() {
    for (int guard = 0; guard < 6; guard++) {
      var nativeWorld = new NativeWorld();
      if (guard == 0) { when(nativeWorld.config.canSaveChunks()).thenReturn(false); }
      if (guard == 1) { when(nativeWorld.config.shouldSaveNewChunks()).thenReturn(false); }
      if (guard == 2) { when(nativeWorld.world.isSavingLocked()).thenReturn(true); }
      if (guard == 3) { when(nativeWorld.world.isAlive()).thenReturn(false); }
      if (guard == 4) { nativeWorld.cancelled.set(true); }
      if (guard == 5) { when(nativeWorld.config.canUnloadChunks()).thenReturn(false); }
      assertNull(nativeWorld.batch(mock(IChunkLoader.class), 0, 0).column().join());
      verify(nativeWorld.chunks, never()).getChunkReferenceAsync(anyLong(), anyInt());
      verify(nativeWorld.store, never()).copySerializableEntity(any());
    }
  }

  @Test void savedTerrainBypassesGenerationAndOffLeavesMissingTerrainAlone() throws Exception {
    try (var stored = new SimViewDiskSnapshotReaderTest.Fixture()) {
      var nativeWorld = new NativeWorld();
      var loader = mock(IChunkLoader.class);
      when(nativeWorld.chunks.getLoader()).thenReturn(loader);
      when(loader.loadHolder(0, 0)).thenReturn(CompletableFuture.completedFuture(null));
      assertTrue(SimViewDiskGeneration.load(nativeWorld.world, 0, 0, new int[] {0}, false,
          Runnable::run, () -> false, () -> false).join().isEmpty());
      when(loader.loadHolder(0, 0)).thenReturn(CompletableFuture.completedFuture(stored.root));
      when(nativeWorld.config.canSaveChunks()).thenReturn(false);
      var packets = SimViewDiskGeneration.load(nativeWorld.world, 0, 0, new int[] {0}, true,
          Runnable::run, () -> false, () -> false).join();
      assertTrue(packets.stream().anyMatch(SetChunk.class::isInstance));
      verify(nativeWorld.chunks, never()).getChunkReferenceAsync(anyLong(), anyInt());
      verify(nativeWorld.chunks, never()).getChunkSectionReferenceAsync(anyInt(), anyInt(), anyInt(), anyInt());
      verifyNoInteractions(nativeWorld.saves);
    }
  }

  @Test void worldOrPluginShutdownStopsPersistencePollingWithoutRemovingNativeTerrain() {
    for (boolean pluginStop : new boolean[] {false, true}) {
      var nativeWorld = new NativeWorld();
      var loader = mock(IChunkLoader.class);
      when(loader.loadHolder(0, 0)).thenReturn(CompletableFuture.completedFuture(null));
      when(nativeWorld.chunks.getChunkReferenceAsync(anyLong(), anyInt()))
          .thenReturn(CompletableFuture.completedFuture(nativeWorld.column));
      var batch = nativeWorld.batch(loader, 0, 0);
      batch.column().join(); // Copy is absent, but the materialized native reference must still be saved.
      var result = batch.finish(CompletableFuture.completedFuture(List.<ToClientPacket>of(new SetChunk())));
      assertFalse(result.isDone());
      for (int poll = 0; poll < 2000; poll++) {
        assertEquals(1, nativeWorld.retries.size());
        nativeWorld.retries.removeFirst().run();
        assertFalse(result.isDone());
      }
      if (pluginStop) { nativeWorld.stopping.set(true); }
      else { when(nativeWorld.world.isAlive()).thenReturn(false); }
      nativeWorld.retries.removeFirst().run();
      assertTrue(result.join().isEmpty()); assertTrue(nativeWorld.retries.isEmpty());
      verify(nativeWorld.chunks, never()).remove(any(), any());
    }
  }
}
