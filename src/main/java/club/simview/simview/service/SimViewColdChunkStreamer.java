package club.simview.simview.service;

import club.simview.simview.config.SimViewConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.iterator.CircleSpiralIterator;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.UnloadChunk;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;

public final class SimViewColdChunkStreamer implements AutoCloseable {

  private static final ComponentType<ChunkStore, BlockChunk> BLOCK_CHUNK_COMPONENT_TYPE =
      BlockChunk.getComponentType();
  private static final ComponentType<ChunkStore, ChunkColumn> CHUNK_COLUMN_COMPONENT_TYPE =
      ChunkColumn.getComponentType();
  private static final ComponentType<ChunkStore, BlockSection> BLOCK_SECTION_COMPONENT_TYPE =
      BlockSection.getComponentType();
  private static final ComponentType<ChunkStore, FluidSection> FLUID_SECTION_COMPONENT_TYPE =
      FluidSection.getComponentType();
  private static final BlockChunkPacketCache[] BLOCK_PACKET_CACHES = BlockChunkPacketCache.values();
  private static final ToClientPacket[] EMPTY_PACKETS = new ToClientPacket[0];
  private static final CompletableFuture<ToClientPacket[]> EMPTY_PACKETS_FUTURE =
      CompletableFuture.completedFuture(EMPTY_PACKETS);
  private static final int PACKET_WORKER_COUNT =
      Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
  private static final int GLOBAL_LOADS_IN_FLIGHT_LIMIT = Math.max(128, PACKET_WORKER_COUNT * 64);

  private final ConcurrentHashMap<UUID, PlayerColdView> views = new ConcurrentHashMap<>();
  private final AtomicInteger globalLoadsInFlight = new AtomicInteger();
  private final ExecutorService packetExecutor =
      Executors.newFixedThreadPool(PACKET_WORKER_COUNT, new SimViewThreadFactory());

  public void tick(
      World world,
      PlayerRef playerRef,
      Vector3d position,
      float deltaSeconds,
      SimViewConfig config,
      int hytaleSimulationDistanceChunks,
      int effectiveViewDistanceChunks) {
    UUID playerUuid = playerRef.getUuid();
    if (!config.enabled() || effectiveViewDistanceChunks <= hytaleSimulationDistanceChunks) {
      unload(playerRef);
      return;
    }
    if (playerUuid == null || !playerRef.isValid()) {
      unload(playerRef);
      return;
    }
    if (!playerRef.getPacketHandler().getChannel(NetworkChannel.Chunks).isWritable()) {
      return;
    }

    PlayerColdView view = views.computeIfAbsent(playerUuid, ignored -> new PlayerColdView());
    view.remember(playerRef);

    int chunkX = ChunkUtil.chunkCoordinate(position.x());
    int chunkZ = ChunkUtil.chunkCoordinate(position.z());
    boolean speeding = view.updateSpeed(position, config);
    int perTickBudget = speeding ? config.speedingChunkSendsPerTick() : config.maxChunkSendsPerTick();
    int perSecondBudget = speeding ? config.speedingChunkSendsPerSecond() : config.maxChunkSendsPerSecond();
    int chunkBudget = view.chunkBudget(deltaSeconds, perSecondBudget, perTickBudget);

    view.moveAndUnload(playerRef, chunkX, chunkZ, hytaleSimulationDistanceChunks, effectiveViewDistanceChunks);
    view.enqueue(
        world,
        playerRef,
        chunkX,
        chunkZ,
        hytaleSimulationDistanceChunks,
        effectiveViewDistanceChunks,
        chunkBudget,
        config.maxColdChunkLoadsInFlight(),
        config.generateMissingColdChunks());
  }

  public void unload(PlayerRef playerRef) {
    UUID playerUuid = playerRef.getUuid();
    if (playerUuid == null) {
      return;
    }

    PlayerColdView view = views.remove(playerUuid);
    if (view != null) {
      view.unloadAll(playerRef);
    }
  }

  public void unloadAll() {
    for (PlayerColdView view : views.values()) {
      view.unloadAll();
    }
    views.clear();
  }

  @Override
  public void close() {
    packetExecutor.shutdownNow();
  }

  private boolean tryAcquireGlobalLoadSlot(int perPlayerLoadsInFlight) {
    int perPlayerLimit = Math.max(1, perPlayerLoadsInFlight);
    long scaledLimit = (long) perPlayerLimit * Math.max(1, views.size());
    int globalLimit = (int) Math.max(perPlayerLimit, Math.min(GLOBAL_LOADS_IN_FLIGHT_LIMIT, scaledLimit));
    while (true) {
      int current = globalLoadsInFlight.get();
      if (current >= globalLimit) {
        return false;
      }
      if (globalLoadsInFlight.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  private void releaseGlobalLoadSlot() {
    globalLoadsInFlight.updateAndGet(current -> Math.max(0, current - 1));
  }

  private static final class SimViewThreadFactory implements ThreadFactory {
    private final AtomicInteger nextThreadId = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "SimView-cold-packet-" + nextThreadId.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }

  private enum BlockChunkPacketCache {
    HEIGHTMAP("getCachedHeightmapPacket"),
    TINTS("getCachedTintsPacket"),
    ENVIRONMENTS("getCachedEnvironmentsPacket");

    private final MethodHandle methodHandle;

    BlockChunkPacketCache(String methodName) {
      try {
        methodHandle =
            MethodHandles.privateLookupIn(BlockChunk.class, MethodHandles.lookup())
                .findVirtual(BlockChunk.class, methodName, MethodType.methodType(CompletableFuture.class));
      } catch (NoSuchMethodException | IllegalAccessException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<? extends ToClientPacket> get(BlockChunk blockChunk) {
      try {
        return (CompletableFuture<? extends ToClientPacket>) methodHandle.invoke(blockChunk);
      } catch (Throwable throwable) {
        return CompletableFuture.failedFuture(throwable);
      }
    }
  }

  private final class PlayerColdView {
    private final LongOpenHashSet sent = new LongOpenHashSet();
    private final LongOpenHashSet loading = new LongOpenHashSet();
    private final LongOpenHashSet coveredByHot = new LongOpenHashSet();
    private final LongOpenHashSet resend = new LongOpenHashSet();
    private final LongOpenHashSet pendingRetry = new LongOpenHashSet();
    private final CircleSpiralIterator iterator = new CircleSpiralIterator();
    private UUID worldUuid;
    private int centerX;
    private int centerZ;
    private int completedRadius;
    private int lastInnerRadius = -1;
    private int lastOuterRadius = -1;
    private double lastX;
    private double lastZ;
    private boolean hasLastPosition;
    private int speedingCooldownTicks;
    private float accumulator;
    private PlayerRef lastPlayerRef;

    void remember(PlayerRef playerRef) {
      lastPlayerRef = playerRef;
    }

    boolean updateSpeed(Vector3d position, SimViewConfig config) {
      boolean speeding = false;
      if (config.speedingNotSendBlocksPerTick() > 0.0D && hasLastPosition) {
        double deltaX = position.x() - lastX;
        double deltaZ = position.z() - lastZ;
        double threshold = config.speedingNotSendBlocksPerTick();
        speeding = deltaX * deltaX + deltaZ * deltaZ > threshold * threshold;
      }

      lastX = position.x();
      lastZ = position.z();
      hasLastPosition = true;
      if (speeding) {
        speedingCooldownTicks = config.speedingCooldownTicks();
      } else if (speedingCooldownTicks > 0) {
        speedingCooldownTicks--;
      }
      return speedingCooldownTicks > 0;
    }

    synchronized int chunkBudget(float deltaSeconds, int perSecondBudget, int perTickBudget) {
      if (perSecondBudget <= 0 || perTickBudget <= 0) {
        accumulator = 0.0F;
        return 0;
      }
      accumulator += Math.max(0.0F, deltaSeconds);
      int budget = Math.min((int) (perSecondBudget * accumulator), perTickBudget);
      if (budget > 0) {
        accumulator -= budget * (1.0F / perSecondBudget);
      }
      return budget;
    }

    synchronized void moveAndUnload(PlayerRef playerRef, int chunkX, int chunkZ, int innerRadius, int outerRadius) {
      UUID newWorldUuid = playerRef.getWorldUuid();
      if (!Objects.equals(newWorldUuid, worldUuid)) {
        unloadAll(playerRef);
        worldUuid = newWorldUuid;
        centerX = chunkX;
        centerZ = chunkZ;
        completedRadius = innerRadius;
        lastInnerRadius = innerRadius;
        lastOuterRadius = outerRadius;
        return;
      }

      int moved = Math.max(Math.abs(centerX - chunkX), Math.abs(centerZ - chunkZ));
      boolean radiiChanged = innerRadius != lastInnerRadius || outerRadius != lastOuterRadius;
      boolean innerRadiusShrank = lastInnerRadius >= 0 && innerRadius < lastInnerRadius;
      if (moved > 0) {
        centerX = chunkX;
        centerZ = chunkZ;
      }
      if (moved > 0 || innerRadiusShrank) {
        completedRadius = innerRadius;
      }

      completedRadius = Math.min(completedRadius, outerRadius);
      if (moved > 0 || radiiChanged) {
        unloadOutOfRange(playerRef, innerRadius, outerRadius);
        lastInnerRadius = innerRadius;
        lastOuterRadius = outerRadius;
      }
    }

    synchronized void enqueue(
        World world,
        PlayerRef playerRef,
        int chunkX,
        int chunkZ,
        int innerRadius,
        int outerRadius,
        int perTickBudget,
        int maxLoadsInFlight,
        boolean generateMissingColdChunks) {
      if (perTickBudget <= 0 || loading.size() >= maxLoadsInFlight) {
        return;
      }

      int remaining = Math.min(perTickBudget, maxLoadsInFlight - loading.size());
      remaining =
          enqueueResends(
              pendingRetry,
              world,
              playerRef,
              chunkX,
              chunkZ,
              innerRadius,
              outerRadius,
              remaining,
              maxLoadsInFlight,
              generateMissingColdChunks);
      if (remaining <= 0 || loading.size() >= maxLoadsInFlight) {
        return;
      }

      remaining =
          enqueueResends(
              resend,
              world,
              playerRef,
              chunkX,
              chunkZ,
              innerRadius,
              outerRadius,
              remaining,
              maxLoadsInFlight,
              generateMissingColdChunks);
      if (remaining <= 0 || loading.size() >= maxLoadsInFlight) {
        return;
      }

      int radiusFrom = Math.max(innerRadius, completedRadius);
      if (radiusFrom >= outerRadius) {
        completedRadius = Math.max(completedRadius, outerRadius);
        return;
      }

      iterator.init(chunkX, chunkZ, radiusFrom, outerRadius);
      while (remaining > 0 && iterator.hasNext()) {
        long chunkIndex = iterator.next();
        if (sent.contains(chunkIndex) || loading.contains(chunkIndex) || coveredByHot.contains(chunkIndex)) {
          continue;
        }
        if (distanceSquared(chunkX, chunkZ, chunkIndex) <= innerRadius * innerRadius) {
          continue;
        }
        if (loading.add(chunkIndex)) {
          if (!loadAndSend(world, playerRef, chunkIndex, maxLoadsInFlight, generateMissingColdChunks)) {
            loading.remove(chunkIndex);
            break;
          }
          remaining--;
        }
      }
      completedRadius = Math.max(completedRadius, iterator.getCompletedRadius());
    }

    private int enqueueResends(
        LongOpenHashSet queue,
        World world,
        PlayerRef playerRef,
        int chunkX,
        int chunkZ,
        int innerRadius,
        int outerRadius,
        int remaining,
        int maxLoadsInFlight,
        boolean generateMissingColdChunks) {
      int innerRadiusSquared = innerRadius * innerRadius;
      int outerRadiusSquared = outerRadius * outerRadius;
      LongIterator iterator = queue.iterator();
      while (remaining > 0 && iterator.hasNext()) {
        long chunkIndex = iterator.nextLong();
        int distanceSquared = distanceSquared(chunkX, chunkZ, chunkIndex);
        if (distanceSquared <= innerRadiusSquared || distanceSquared > outerRadiusSquared) {
          iterator.remove();
          continue;
        }
        if (sent.contains(chunkIndex) || loading.contains(chunkIndex)) {
          iterator.remove();
          continue;
        }
        if (loading.add(chunkIndex)) {
          if (!loadAndSend(world, playerRef, chunkIndex, maxLoadsInFlight, generateMissingColdChunks)) {
            loading.remove(chunkIndex);
            break;
          }
          iterator.remove();
          remaining--;
        }
      }
      return remaining;
    }

    synchronized void unloadAll(PlayerRef playerRef) {
      if (!playerRef.isValid()) {
        sent.clear();
        loading.clear();
        coveredByHot.clear();
        resend.clear();
        pendingRetry.clear();
        trimQueues();
        resetTransientState();
        return;
      }

      PacketHandler packetHandler = playerRef.getPacketHandler();
      unloadChunks(packetHandler, sent);
      unloadChunks(packetHandler, resend);
      sent.clear();
      loading.clear();
      coveredByHot.clear();
      resend.clear();
      pendingRetry.clear();
      trimQueues();
      resetTransientState();
    }

    private void trimQueues() {
      sent.trim();
      loading.trim();
      coveredByHot.trim();
      resend.trim();
      pendingRetry.trim();
    }

    private static void unloadChunks(PacketHandler packetHandler, LongOpenHashSet chunks) {
      LongIterator iterator = chunks.iterator();
      while (iterator.hasNext()) {
        long chunkIndex = iterator.nextLong();
        packetHandler.writeNoCache(
            new UnloadChunk(ChunkUtil.xOfChunkIndex(chunkIndex), ChunkUtil.zOfChunkIndex(chunkIndex)));
      }
    }

    synchronized void unloadAll() {
      if (lastPlayerRef != null) {
        unloadAll(lastPlayerRef);
      }
    }

    private void resetTransientState() {
      completedRadius = 0;
      lastInnerRadius = -1;
      lastOuterRadius = -1;
      hasLastPosition = false;
      speedingCooldownTicks = 0;
      accumulator = 0.0F;
    }

    private void unloadOutOfRange(PlayerRef playerRef, int innerRadius, int outerRadius) {
      int innerRadiusSquared = innerRadius * innerRadius;
      int outerRadiusSquared = outerRadius * outerRadius;
      LongIterator iterator = sent.iterator();
      while (iterator.hasNext()) {
        long chunkIndex = iterator.nextLong();
        int distanceSquared = distanceSquared(centerX, centerZ, chunkIndex);
        if (distanceSquared <= innerRadiusSquared) {
          coveredByHot.add(chunkIndex);
          iterator.remove();
        } else if (distanceSquared > outerRadiusSquared) {
          playerRef
              .getPacketHandler()
              .writeNoCache(
                  new UnloadChunk(ChunkUtil.xOfChunkIndex(chunkIndex), ChunkUtil.zOfChunkIndex(chunkIndex)));
          iterator.remove();
        }
      }

      LongIterator coveredIterator = coveredByHot.iterator();
      while (coveredIterator.hasNext()) {
        long chunkIndex = coveredIterator.nextLong();
        int distanceSquared = distanceSquared(centerX, centerZ, chunkIndex);
        if (distanceSquared > outerRadiusSquared) {
          playerRef
              .getPacketHandler()
              .writeNoCache(
                  new UnloadChunk(ChunkUtil.xOfChunkIndex(chunkIndex), ChunkUtil.zOfChunkIndex(chunkIndex)));
          coveredIterator.remove();
        } else if (distanceSquared > innerRadiusSquared) {
          resend.add(chunkIndex);
          coveredIterator.remove();
        }
      }

      LongIterator loadingIterator = loading.iterator();
      while (loadingIterator.hasNext()) {
        long chunkIndex = loadingIterator.nextLong();
        int distanceSquared = distanceSquared(centerX, centerZ, chunkIndex);
        if (distanceSquared <= innerRadiusSquared || distanceSquared > outerRadiusSquared) {
          pendingRetry.add(chunkIndex);
          loadingIterator.remove();
        }
      }
    }

    private boolean loadAndSend(
        World world, PlayerRef playerRef, long chunkIndex, int maxLoadsInFlight, boolean generateIfMissing) {
      if (!tryAcquireGlobalLoadSlot(maxLoadsInFlight)) {
        return false;
      }

      int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
      int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
      CompletableFuture<ToClientPacket[]> packetsFuture =
          loadStoredChunkPackets(world, chunkIndex, chunkX, chunkZ, generateIfMissing);

      packetsFuture.whenCompleteAsync(
          (packets, throwable) -> finishLoad(playerRef, chunkIndex, packets, throwable), world);
      return true;
    }

    private CompletableFuture<ToClientPacket[]> loadStoredChunkPackets(
        World world, long chunkIndex, int chunkX, int chunkZ, boolean generateIfMissing) {
      ChunkStore chunkStore = world.getChunkStore();
      IChunkLoader loader = chunkStore.getLoader();
      if (loader == null) {
        return generateMissingChunkPackets(world, chunkIndex, generateIfMissing);
      }

      return loader
          .loadHolder(chunkX, chunkZ)
          .thenComposeAsync(
              holder -> {
                if (holder == null) {
                  return generateMissingChunkPackets(world, chunkIndex, generateIfMissing);
                }
                return createSectionPackets(holder, chunkX, chunkZ)
                    .thenCompose(
                        packets ->
                            packets.length == 0
                                ? generateMissingChunkPackets(world, chunkIndex, generateIfMissing)
                                : CompletableFuture.completedFuture(packets));
              },
              packetExecutor);
    }

    private CompletableFuture<ToClientPacket[]> generateMissingChunkPackets(
        World world, long chunkIndex, boolean generateIfMissing) {
      if (!generateIfMissing) {
        return EMPTY_PACKETS_FUTURE;
      }
      return world.getNonTickingChunkAsync(chunkIndex).thenComposeAsync(this::createSectionPackets, packetExecutor);
    }

    private CompletableFuture<ToClientPacket[]> createSectionPackets(
        Holder<ChunkStore> holder, int chunkX, int chunkZ) {
      if (holder == null) {
        return EMPTY_PACKETS_FUTURE;
      }

      BlockChunk blockChunk = holder.getComponent(BLOCK_CHUNK_COMPONENT_TYPE);
      if (blockChunk == null) {
        return EMPTY_PACKETS_FUTURE;
      }
      blockChunk.load(chunkX, chunkZ);
      loadSectionsFromHolder(holder, blockChunk);
      FluidSection[] fluidSections = loadFluidSectionsFromHolder(holder, chunkX, chunkZ);
      return createSectionPacketsFromBlockChunk(blockChunk, chunkX, chunkZ, fluidSections);
    }

    @SuppressWarnings("deprecation")
    private static void loadSectionsFromHolder(Holder<ChunkStore> holder, BlockChunk blockChunk) {
      ChunkColumn chunkColumn = holder.getComponent(CHUNK_COLUMN_COMPONENT_TYPE);
      if (chunkColumn == null) {
        return;
      }

      Holder<ChunkStore>[] sectionHolders = chunkColumn.getSectionHolders();
      if (sectionHolders == null) {
        return;
      }

      BlockSection[] sections = blockChunk.getChunkSections();
      int sectionCount = Math.min(sections.length, sectionHolders.length);
      for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
        Holder<ChunkStore> sectionHolder = sectionHolders[sectionIndex];
        if (sectionHolder != null) {
          sections[sectionIndex] = sectionHolder.ensureAndGetComponent(BLOCK_SECTION_COMPONENT_TYPE);
        }
      }
      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
        if (sections[sectionIndex] == null) {
          sections[sectionIndex] = new BlockSection();
        }
      }
    }

    private CompletableFuture<ToClientPacket[]> createSectionPackets(WorldChunk worldChunk) {
      if (worldChunk == null) {
        return EMPTY_PACKETS_FUTURE;
      }
      BlockChunk blockChunk = worldChunk.getBlockChunk();
      if (blockChunk == null) {
        return EMPTY_PACKETS_FUTURE;
      }
      return createSectionPacketsFromBlockChunk(blockChunk, worldChunk.getX(), worldChunk.getZ(), null);
    }

    @SuppressWarnings("deprecation")
    private CompletableFuture<ToClientPacket[]> createSectionPacketsFromBlockChunk(
        BlockChunk blockChunk, int chunkX, int chunkZ, FluidSection[] fluidSections) {
      BlockSection[] sections = blockChunk.getChunkSections();
      if (sections.length == 0) {
        return EMPTY_PACKETS_FUTURE;
      }
      ensureCompleteBlockSections(sections);

      int fluidPacketCount = 0;
      int fluidSectionCount = fluidSections == null ? 0 : Math.min(fluidSections.length, sections.length);
      for (int sectionIndex = 0; sectionIndex < fluidSectionCount; sectionIndex++) {
        if (fluidSections[sectionIndex] != null) {
          fluidPacketCount++;
        }
      }

      CompletableFuture<?>[] futures =
          new CompletableFuture<?>[BLOCK_PACKET_CACHES.length + sections.length + fluidPacketCount];
      int futureCount = 0;
      for (BlockChunkPacketCache packetCache : BLOCK_PACKET_CACHES) {
        futures[futureCount++] = packetCache.get(blockChunk);
      }
      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
        futures[futureCount++] = sections[sectionIndex].getCachedChunkPacket(chunkX, sectionIndex, chunkZ);
      }
      for (int sectionIndex = 0; sectionIndex < fluidSectionCount; sectionIndex++) {
        FluidSection fluidSection = fluidSections[sectionIndex];
        if (fluidSection != null) {
          futures[futureCount++] = fluidSection.getCachedPacket();
        }
      }

      return CompletableFuture.allOf(futures)
          .thenApplyAsync(
              ignored -> {
                ToClientPacket[] packets = new ToClientPacket[futures.length];
                int packetCount = 0;
                for (CompletableFuture<?> future : futures) {
                  ToClientPacket packet = (ToClientPacket) future.join();
                  if (packet != null) {
                    packets[packetCount++] = packet;
                  }
                }
                if (packetCount == 0) {
                  return EMPTY_PACKETS;
                }
                if (packetCount == packets.length) {
                  return packets;
                }

                ToClientPacket[] compactPackets = new ToClientPacket[packetCount];
                System.arraycopy(packets, 0, compactPackets, 0, packetCount);
                return compactPackets;
              },
              packetExecutor);
    }

    private static void ensureCompleteBlockSections(BlockSection[] sections) {
      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
        if (sections[sectionIndex] == null) {
          sections[sectionIndex] = new BlockSection();
        }
      }
    }

    @SuppressWarnings("deprecation")
    private static FluidSection[] loadFluidSectionsFromHolder(Holder<ChunkStore> holder, int chunkX, int chunkZ) {
      ChunkColumn chunkColumn = holder.getComponent(CHUNK_COLUMN_COMPONENT_TYPE);
      if (chunkColumn == null) {
        return null;
      }

      Holder<ChunkStore>[] sectionHolders = chunkColumn.getSectionHolders();
      if (sectionHolders == null) {
        return null;
      }

      FluidSection[] fluidSections = null;
      for (int sectionIndex = 0; sectionIndex < sectionHolders.length; sectionIndex++) {
        Holder<ChunkStore> sectionHolder = sectionHolders[sectionIndex];
        if (sectionHolder == null || !sectionHolder.getArchetype().contains(FLUID_SECTION_COMPONENT_TYPE)) {
          continue;
        }

        FluidSection fluidSection = sectionHolder.ensureAndGetComponent(FLUID_SECTION_COMPONENT_TYPE);
        if (fluidSection != null) {
          fluidSection.load(chunkX, sectionIndex, chunkZ);
          if (fluidSections == null) {
            fluidSections = new FluidSection[sectionHolders.length];
          }
          fluidSections[sectionIndex] = fluidSection;
        }
      }
      return fluidSections;
    }

    private synchronized void finishLoad(
        PlayerRef playerRef, long chunkIndex, ToClientPacket[] packets, Throwable throwable) {
      releaseGlobalLoadSlot();
      if (!loading.remove(chunkIndex)) {
        return;
      }
      if (throwable != null || packets == null || packets.length == 0 || !playerRef.isValid()) {
        return;
      }
      if (!playerRef.getPacketHandler().getChannel(NetworkChannel.Chunks).isWritable()) {
        return;
      }

      PacketHandler packetHandler = playerRef.getPacketHandler();
      packetHandler.write(packets);
      sent.add(chunkIndex);
    }

    private static int distanceSquared(int centerX, int centerZ, long chunkIndex) {
      int deltaX = ChunkUtil.xOfChunkIndex(chunkIndex) - centerX;
      int deltaZ = ChunkUtil.zOfChunkIndex(chunkIndex) - centerZ;
      return deltaX * deltaX + deltaZ * deltaZ;
    }
  }
}
