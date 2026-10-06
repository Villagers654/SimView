package net.modtale.simview.service;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.protocol.packets.world.SetFluids;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightData;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Builds detached saved-terrain snapshots. Never inserts holders into the world store. */
final class SimViewDiskSnapshotReader {
  static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
  static final int MAX_PACKET_BYTES = 512 * 1024;

  static CompletableFuture<List<ToClientPacket>> load(IChunkLoader loader, int x, int z,
      int[] sectionYs, Executor executor, BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) { return CompletableFuture.completedFuture(List.of()); }
    return loader.loadHolder(x, z).thenComposeAsync(holder -> {
      if (holder == null || cancelled.getAsBoolean()) {
        return CompletableFuture.completedFuture(List.of());
      }
      Builder builder = new Builder();
      BlockChunk blocks = holder.getComponent(BlockChunk.getComponentType());
      if (blocks == null) { return CompletableFuture.completedFuture(List.of()); }
      return SimViewDiskProtocol.resolveHeight(loader, holder, x, z).thenComposeAsync(resolved -> {
        if (cancelled.getAsBoolean()) { return CompletableFuture.completedFuture(List.of()); }
        builder.add(SimViewDiskProtocol.column(blocks, SimViewDiskProtocol.height(holder), x, z));
        if (loader instanceof IChunkLoader.Cubic cubic) {
          CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
          for (int y : sectionYs) {
            int sectionY = y;
            chain = chain.thenComposeAsync(ignored -> cancelled.getAsBoolean()
                ? CompletableFuture.completedFuture(null)
                : cubic.loadSectionHolder(x, sectionY, z).thenAcceptAsync(section -> {
                  if (section != null && !cancelled.getAsBoolean()) {
                    builder.section(section, x, sectionY, z);
                  }
                }, executor), executor);
          }
          return chain.thenApply(ignored -> cancelled.getAsBoolean() ? List.of() : builder.finish());
        }
        ChunkColumn column = holder.getComponent(ChunkColumn.getComponentType());
        if (column == null || column.getSectionHolders() == null) {
          return CompletableFuture.completedFuture(List.of());
        }
        Holder<ChunkStore>[] sections = column.getSectionHolders();
        for (int y : sectionYs) {
          if (y < 0 || y >= sections.length) { continue; }
          if (cancelled.getAsBoolean()) { return CompletableFuture.completedFuture(List.of()); }
          builder.section(sections[y], x, y, z);
        }
        return CompletableFuture.completedFuture(builder.finish());
      }, executor);
    }, executor);
  }

  static SetChunk blockPacket(BlockSection blocks, int x, int y, int z) {
    return new SetChunk(x, y, z,
        BlockChunk.SEND_LOCAL_LIGHTING_DATA && blocks.hasLocalLight()
            ? light(blocks.getLocalLight(), blocks.getLocalChangeCounter()) : null,
        BlockChunk.SEND_GLOBAL_LIGHTING_DATA && blocks.hasGlobalLight()
            ? light(blocks.getGlobalLight(), blocks.getGlobalChangeCounter()) : null,
        blocks.isSolidAir() ? null : blocks.serializeForPacket());
  }

  private static byte[] light(ChunkLightData light, short change) {
    if (light == null || light.getChangeId() != change) { return null; }
    byte[] bytes = new byte[light.serializedForPacketByteSize()];
    light.serializeForPacket(MemorySegment.ofArray(bytes), 0);
    return bytes;
  }

  static SetFluids fluidPacket(FluidSection fluid, int x, int y, int z) {
    fluid.load(x, y, z);
    // The current public API exposes fluid packet serialization through CachedPacket.
    // Copy it into an ordinary packet and release the temporary Netty buffer immediately.
    try (var cached = fluid.getCachedPacket().join()) {
      int size = cached.computeSize();
      if (size > MAX_PACKET_BYTES) { throw new IllegalArgumentException("Stored fluid packet too large"); }
      byte[] bytes = new byte[size];
      cached.serialize(MemorySegment.ofArray(bytes), 0);
      return SetFluids.toObject(MemorySegment.ofArray(bytes));
    }
  }

  private static final class Builder {
    private final List<ToClientPacket> packets = new ArrayList<>();
    private int bytes;
    private int sections;

    void add(ToClientPacket packet) {
      int size = packet.computeSize();
      if (size > MAX_PACKET_BYTES || size > MAX_SNAPSHOT_BYTES - bytes) {
        throw new IllegalArgumentException("Stored terrain snapshot exceeds streaming buffer limit");
      }
      bytes += size;
      packets.add(packet);
    }

    void section(Holder<ChunkStore> holder, int x, int y, int z) {
      BlockSection blocks = holder == null ? null : holder.getComponent(BlockSection.getComponentType());
      add(blockPacket(blocks == null ? new BlockSection() : blocks, x, y, z));
      sections++;
      FluidSection fluid = holder == null ? null : holder.getComponent(FluidSection.getComponentType());
      if (fluid != null) { add(fluidPacket(fluid, x, y, z)); }
      ToClientPacket environments = SimViewDiskProtocol.environments(holder, x, y, z);
      if (environments != null) { add(environments); }
    }

    List<ToClientPacket> finish() {
      return sections == 0 ? List.of() : List.copyOf(packets);
    }
  }
}
