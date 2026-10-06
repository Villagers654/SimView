package net.modtale.simview.service;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.SetChunkEnvironments;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.IntBytePalette;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.ShortBytePalette;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/** Bridges the two supported public protocol layouts without accessing vendor private members. */
final class SimViewDiskProtocol {
  private static final Field HEIGHT;
  private static final boolean SECTION_ENVIRONMENTS;
  private static final Method HEIGHT_GETTER, ENVIRONMENT_GETTER, ENVIRONMENT_SERIALIZE;
  private static final Method HEIGHT_TYPE, HEIGHT_SERIALIZE, HEIGHT_TREE, TREE_RESOLVE, LEGACY_HEIGHT;
  private static final Method ENVIRONMENT_TYPE, ENVIRONMENT_PACKET;
  private static final Class<?> TREE_CLASS;
  private static final java.lang.reflect.Constructor<?> LEGACY_CONSTRUCTOR;
  private static final Method LEGACY_SERIALIZE;

  static {
    try {
      HEIGHT = SetColumn.class.getField("heightmap");
      SECTION_ENVIRONMENTS = HEIGHT.getType() == int[].class;
      if (SECTION_ENVIRONMENTS) {
        Class<?> height = Class.forName("com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn");
        TREE_CLASS = Class.forName("com.hypixel.hytale.server.core.universe.world.chunk.heightmap.TreeHeightmap");
        Class<?> environment = Class.forName("com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection");
        HEIGHT_TYPE = height.getMethod("getComponentType");
        HEIGHT_SERIALIZE = height.getMethod("serializeToInts");
        HEIGHT_TREE = height.getMethod("getHeightmap");
        TREE_RESOLVE = IChunkLoader.Cubic.class.getMethod("resolveTreeHeightmapColumns", int.class, int.class, TREE_CLASS);
        LEGACY_HEIGHT = BlockChunk.class.getMethod("takeLegacyHeightmap");
        Class<?> legacy = Class.forName("com.hypixel.hytale.server.core.universe.world.chunk.heightmap.LegacyHeightmap");
        LEGACY_CONSTRUCTOR = legacy.getConstructor(ShortBytePalette.class);
        LEGACY_SERIALIZE = legacy.getMethod("serializeToInts");
        ENVIRONMENT_TYPE = environment.getMethod("getComponentType");
        ENVIRONMENT_PACKET = environment.getMethod("getCachedPacket", int.class, int.class, int.class);
        HEIGHT_GETTER = ENVIRONMENT_GETTER = ENVIRONMENT_SERIALIZE = null;
      } else {
        if (HEIGHT.getType() != byte[].class) { throw new IllegalStateException("Unsupported column heightmap protocol"); }
        HEIGHT_GETTER = BlockChunk.class.getMethod("getHeight", int.class, int.class);
        ENVIRONMENT_GETTER = BlockChunk.class.getMethod("getEnvironmentChunk");
        ENVIRONMENT_SERIALIZE = ENVIRONMENT_GETTER.getReturnType().getMethod("serializeProtocol");
        HEIGHT_TYPE = HEIGHT_SERIALIZE = HEIGHT_TREE = TREE_RESOLVE = LEGACY_HEIGHT = null;
        ENVIRONMENT_TYPE = ENVIRONMENT_PACKET = null;
        TREE_CLASS = null;
        LEGACY_CONSTRUCTOR = null; LEGACY_SERIALIZE = null;
      }
    } catch (ReflectiveOperationException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  static CompletableFuture<Void> resolveHeight(IChunkLoader loader, Holder<ChunkStore> holder, int x, int z) {
    Object height = height(holder);
    if (height == null || !(loader instanceof IChunkLoader.Cubic)) {
      return CompletableFuture.completedFuture(null);
    }
    Object tree = invoke(HEIGHT_TREE, height);
    if (!TREE_CLASS.isInstance(tree)) { return CompletableFuture.completedFuture(null); }
    @SuppressWarnings("unchecked")
    var future = (CompletableFuture<Void>) invoke(TREE_RESOLVE, loader, x, z, tree);
    return future;
  }

  static Object height(Holder<ChunkStore> holder) {
    return SECTION_ENVIRONMENTS ? holder.getComponent(type(HEIGHT_TYPE)) : null;
  }

  static SetColumn column(BlockChunk blocks, Object height, int x, int z) {
    IntBytePalette tint = new IntBytePalette();
    for (int localX = 0; localX < 32; localX++) {
      for (int localZ = 0; localZ < 32; localZ++) { tint.set(localX, localZ, blocks.getTint(localX, localZ)); }
    }
    SetColumn packet = new SetColumn();
    packet.x = x; packet.z = z; packet.tintmap = tint.serialize();
    try {
      if (SECTION_ENVIRONMENTS) {
        int[] heights;
        if (height != null) { heights = (int[]) invoke(HEIGHT_SERIALIZE, height); }
        else {
          ShortBytePalette legacy = (ShortBytePalette) invoke(LEGACY_HEIGHT, blocks);
          if (legacy == null) { throw new IllegalArgumentException("Saved column has no heightmap"); }
          heights = (int[]) invoke(LEGACY_SERIALIZE, LEGACY_CONSTRUCTOR.newInstance(legacy));
        }
        HEIGHT.set(packet, heights);
      } else {
        ShortBytePalette heights = new ShortBytePalette();
        for (int localX = 0; localX < 32; localX++) {
          for (int localZ = 0; localZ < 32; localZ++) {
            heights.set(localX, localZ, (short) invoke(HEIGHT_GETTER, blocks, localX, localZ));
          }
        }
        HEIGHT.set(packet, heights.serialize());
        SetColumn.class.getField("environments").set(packet,
            invoke(ENVIRONMENT_SERIALIZE, invoke(ENVIRONMENT_GETTER, blocks)));
      }
      return packet;
    } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
  }

  static ToClientPacket environments(Holder<ChunkStore> holder, int x, int y, int z) {
    if (!SECTION_ENVIRONMENTS || holder == null) { return null; }
    Object environment = holder.getComponent(type(ENVIRONMENT_TYPE));
    if (environment == null) { return null; }
    @SuppressWarnings("unchecked")
    var future = (CompletableFuture<CachedPacket<?>>) invoke(ENVIRONMENT_PACKET, environment, x, y, z);
    try (var packet = future.join()) {
      int size = packet.computeSize();
      if (size > SimViewDiskSnapshotReader.MAX_PACKET_BYTES) {
        throw new IllegalArgumentException("Stored environment packet too large");
      }
      byte[] bytes = new byte[size];
      packet.serialize(MemorySegment.ofArray(bytes), 0);
      return SetChunkEnvironments.toObject(MemorySegment.ofArray(bytes));
    }
  }

  @SuppressWarnings("unchecked")
  private static ComponentType<ChunkStore, Component<ChunkStore>> type(Method getter) {
    return (ComponentType<ChunkStore, Component<ChunkStore>>) invoke(getter, null);
  }

  private static Object invoke(Method method, Object receiver, Object... args) {
    try { return method.invoke(receiver, args); }
    catch (ReflectiveOperationException exception) { throw new IllegalStateException("Hytale public streaming API failed", exception); }
  }
}
