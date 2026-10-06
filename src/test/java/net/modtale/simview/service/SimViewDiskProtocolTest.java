package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.protocol.packets.world.SetFluids;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.ShortBytePalette;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class SimViewDiskProtocolTest {
  @Test void columnMetadataUsesTheActualRuntimeWireLayout() throws Exception {
    var blocks = mock(BlockChunk.class);
    when(blocks.getTint(anyInt(), anyInt())).thenReturn(123);
    var height = SetColumn.class.getField("heightmap");
    if (height.getType() == byte[].class) {
      var getter = BlockChunk.class.getMethod("getHeight", int.class, int.class);
      when((Short) getter.invoke(blocks, anyInt(), anyInt())).thenReturn((short) 77);
      var environment = mock(EnvironmentChunk.class);
      when(EnvironmentChunk.class.getMethod("serializeProtocol").invoke(environment)).thenReturn(new byte[] {1, 2});
      when(BlockChunk.class.getMethod("getEnvironmentChunk").invoke(blocks)).thenReturn(environment);
    } else {
      assertEquals(int[].class, height.getType());
      when(BlockChunk.class.getMethod("takeLegacyHeightmap").invoke(blocks))
          .thenReturn(new ShortBytePalette((short) 77));
    }
    SetColumn packet = SimViewDiskProtocol.column(blocks, null, -3, 5);
    byte[] bytes = new byte[packet.computeSize()];
    packet.serialize(MemorySegment.ofArray(bytes), 0);
    SetColumn decoded = SetColumn.toObject(MemorySegment.ofArray(bytes));
    assertEquals(-3, decoded.x); assertEquals(5, decoded.z);
    assertArrayEquals(packet.tintmap, decoded.tintmap);
    if (height.getType() == byte[].class) {
      var heights = new ShortBytePalette();
      heights.deserialize(MemorySegment.ofArray((byte[]) height.get(decoded)), 0);
      for (int x = 0; x < 32; x++) {
        for (int z = 0; z < 32; z++) { assertEquals(77, heights.get(x, z)); }
      }
      assertArrayEquals(new byte[] {1, 2}, (byte[]) SetColumn.class.getField("environments").get(decoded));
    } else {
      int[] values = (int[]) height.get(decoded);
      assertEquals(1024, values.length);
      for (int value : values) { assertEquals(77, value); }
    }
  }

  @Test void blockAndFluidSnapshotsRoundTripWithSectionCoordinatesAndReleaseTemporaryCache() {
    var blocks = new BlockSection();
    var packet = SimViewDiskSnapshotReader.blockPacket(blocks, -2, -4, 3);
    assertEquals(-4, packet.y);
    assertNull(packet.data); // Native air sections omit their block payload.
    try (var nativePacket = blocks.getCachedChunkPacket(-2, -4, 3).join()) {
      byte[] nativeBytes = new byte[nativePacket.computeSize()];
      nativePacket.serialize(MemorySegment.ofArray(nativeBytes), 0);
      assertEquals(com.hypixel.hytale.protocol.packets.world.SetChunk.toObject(MemorySegment.ofArray(nativeBytes)), packet);
    }
    var fluids = new FluidSection();
    SetFluids fluid = SimViewDiskSnapshotReader.fluidPacket(fluids, -2, -4, 3);
    assertEquals(-2, fluid.x); assertEquals(-4, fluid.y); assertEquals(3, fluid.z);
    byte[] bytes = new byte[fluid.computeSize()];
    fluid.serialize(MemorySegment.ofArray(bytes), 0);
    assertEquals(fluid, SetFluids.toObject(MemorySegment.ofArray(bytes)));
    assertThrows(IllegalStateException.class, () -> fluids.getCachedPacket().join().computeSize());
  }
}
