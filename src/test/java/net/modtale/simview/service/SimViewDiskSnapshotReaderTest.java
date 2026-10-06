package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.ShortBytePalette;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SimViewDiskSnapshotReaderTest {
  @SuppressWarnings({"unchecked", "rawtypes"})
  static final class Fixture implements AutoCloseable {
    final List<MockedStatic<?>> statics = new ArrayList<>();
    final Holder<ChunkStore> root = mock(Holder.class);
    final Holder<ChunkStore> section = mock(Holder.class);
    final BlockSection blocks = new BlockSection();
    Fixture() throws Exception {
      var blockType = (ComponentType<ChunkStore, BlockChunk>) mock(ComponentType.class);
      var columnType = (ComponentType<ChunkStore, ChunkColumn>) mock(ComponentType.class);
      var sectionType = (ComponentType<ChunkStore, BlockSection>) mock(ComponentType.class);
      var fluidType = (ComponentType<ChunkStore, FluidSection>) mock(ComponentType.class);
      var blockStatic = mockStatic(BlockChunk.class); statics.add(blockStatic);
      var columnStatic = mockStatic(ChunkColumn.class); statics.add(columnStatic);
      var sectionStatic = mockStatic(BlockSection.class); statics.add(sectionStatic);
      var fluidStatic = mockStatic(FluidSection.class); statics.add(fluidStatic);
      blockStatic.when(BlockChunk::getComponentType).thenReturn(blockType);
      columnStatic.when(ChunkColumn::getComponentType).thenReturn(columnType);
      sectionStatic.when(BlockSection::getComponentType).thenReturn(sectionType);
      fluidStatic.when(FluidSection::getComponentType).thenReturn(fluidType);
      var column = mock(ChunkColumn.class);
      when(column.getSectionHolders()).thenReturn(new Holder[] {section, section, section});
      when(root.getComponent(columnType)).thenReturn(column);
      var metadata = mock(BlockChunk.class);
      when(root.getComponent(blockType)).thenReturn(metadata);
      when(section.getComponent(sectionType)).thenReturn(blocks);
      if (SetColumn.class.getField("heightmap").getType() == int[].class) {
        mockType("com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn");
        mockType("com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection");
        when(BlockChunk.class.getMethod("takeLegacyHeightmap").invoke(metadata))
            .thenAnswer(invocation -> new ShortBytePalette((short) 77));
      } else {
        var environment = mock(EnvironmentChunk.class);
        when(EnvironmentChunk.class.getMethod("serializeProtocol").invoke(environment)).thenReturn(new byte[] {0});
        when(BlockChunk.class.getMethod("getEnvironmentChunk").invoke(metadata)).thenReturn(environment);
      }
    }
    void mockType(String name) throws Exception {
      Class<?> type = Class.forName(name);
      var componentType = mock(ComponentType.class);
      var mocked = mockStatic(type);
      statics.add(mocked);
      mocked.when(() -> type.getMethod("getComponentType").invoke(null)).thenReturn(componentType);
    }
    @Override public void close() {
      for (int i = statics.size() - 1; i >= 0; i--) { statics.get(i).close(); }
    }
  }

  @Test void legacyLoaderReadsDetachedSavedHolderAndOnlyRequestedSections() throws Exception {
    try (var fixture = new Fixture()) {
      var loader = mock(IChunkLoader.class);
      when(loader.loadHolder(3, -1)).thenReturn(CompletableFuture.completedFuture(fixture.root));
      var packets = SimViewDiskSnapshotReader.load(loader, 3, -1, new int[] {-4, 0, 2, 8}, Runnable::run, () -> false).join();
      assertInstanceOf(SetColumn.class, packets.getFirst());
      var sections = packets.stream().filter(SetChunk.class::isInstance).map(SetChunk.class::cast).toList();
      assertEquals(List.of(0, 2), sections.stream().map(packet -> packet.y).toList());
      for (SetChunk section : sections) {
        assertEquals(3, section.x); assertEquals(-1, section.z);
        assertNull(section.data); // Same native air-section representation.
      }
      verify(loader).loadHolder(3, -1);
      verifyNoMoreInteractions(loader);
    }
  }

  @Test void cubicLoaderReadsExplicitNegativeSectionCoordinatesWithoutWorldInsertion() throws Exception {
    try (var fixture = new Fixture()) {
      var loader = mock(IChunkLoader.Cubic.class);
      when(loader.loadHolder(-2, 4)).thenReturn(CompletableFuture.completedFuture(fixture.root));
      when(loader.loadSectionHolder(anyInt(), anyInt(), anyInt()))
          .thenReturn(CompletableFuture.completedFuture(fixture.section));
      var packets = SimViewDiskSnapshotReader.load(loader, -2, 4, new int[] {-3, 5}, Runnable::run, () -> false).join();
      var sections = packets.stream().filter(SetChunk.class::isInstance).map(SetChunk.class::cast).toList();
      assertEquals(List.of(-3, 5), sections.stream().map(packet -> packet.y).toList());
      verify(loader).loadHolder(-2, 4);
      verify(loader).loadSectionHolder(-2, -3, 4);
      verify(loader).loadSectionHolder(-2, 5, 4);
      verifyNoMoreInteractions(loader);
    }
  }

  @Test void cancellationDuringCubicReadPreventsFurtherReadsAndSerialization() throws Exception {
    try (var fixture = new Fixture()) {
      var loader = mock(IChunkLoader.Cubic.class);
      when(loader.loadHolder(0, 0)).thenReturn(CompletableFuture.completedFuture(fixture.root));
      var read = new CompletableFuture<Holder<ChunkStore>>();
      when(loader.loadSectionHolder(0, -3, 0)).thenReturn(read);
      var cancelled = new AtomicBoolean();
      var snapshot = SimViewDiskSnapshotReader.load(loader, 0, 0, new int[] {-3, 5}, Runnable::run, cancelled::get);
      assertFalse(snapshot.isDone());
      cancelled.set(true);
      read.complete(fixture.section);
      assertTrue(snapshot.join().isEmpty());
      verify(loader, never()).loadSectionHolder(0, 5, 0);
    }
  }

  @Test void missingStoredTerrainProducesNoPacketsAndDoesNotGenerateIt() {
    var loader = mock(IChunkLoader.class);
    when(loader.loadHolder(2, 3)).thenReturn(CompletableFuture.completedFuture(null));
    assertTrue(SimViewDiskSnapshotReader.load(loader, 2, 3, new int[] {1}, Runnable::run, () -> false).join().isEmpty());
    verify(loader).loadHolder(2, 3);
    verifyNoMoreInteractions(loader);
  }

  @Test @SuppressWarnings({"unchecked", "rawtypes"})
  void prereleaseSectionEnvironmentsUseActualWirePacketsAndReleaseTheirCache() throws Exception {
    if (SetColumn.class.getField("heightmap").getType() != int[].class) { return; }
    try (var fixture = new Fixture()) {
      Class<?> type = Class.forName("com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection");
      Object environments = mock(type);
      var nativePacket = com.hypixel.hytale.protocol.packets.world.SetChunkEnvironments.class
          .getConstructor(int.class, int.class, int.class, byte[].class).newInstance(-3, -5, 4, new byte[] {0});
      var cached = CompletableFuture.completedFuture(com.hypixel.hytale.protocol.CachedPacket.cache(nativePacket));
      when(type.getMethod("getCachedPacket", int.class, int.class, int.class).invoke(environments, -3, -5, 4))
          .thenReturn(cached);
      var componentType = (ComponentType) type.getMethod("getComponentType").invoke(null);
      when(fixture.section.getComponent(componentType)).thenReturn((com.hypixel.hytale.component.Component) environments);
      var packet = SimViewDiskProtocol.environments(fixture.section, -3, -5, 4);
      byte[] bytes = new byte[packet.computeSize()];
      packet.serialize(java.lang.foreign.MemorySegment.ofArray(bytes), 0);
      var decoded = com.hypixel.hytale.protocol.packets.world.SetChunkEnvironments
          .toObject(java.lang.foreign.MemorySegment.ofArray(bytes));
      assertEquals(-3, decoded.x); assertEquals(-5, decoded.getClass().getField("y").get(decoded));
      assertEquals(4, decoded.z);
      assertEquals(packet, decoded);
      assertThrows(IllegalStateException.class, () -> cached.join().computeSize());
    }
  }
}
