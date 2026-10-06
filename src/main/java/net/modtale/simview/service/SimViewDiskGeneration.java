package net.modtale.simview.service;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native owns generation and saving. A disk job stays admitted until its new terrain is saved. */
final class SimViewDiskGeneration implements SimViewDiskSnapshotReader.MissingTerrain {
  private final World world;
  private final IChunkLoader loader;
  private final int x, z;
  private final BooleanSupplier cancelled, stopping;
  private final Executor retryExecutor;
  private final List<Integer> generatedSections = new ArrayList<>();
  private boolean generatedColumn;

  SimViewDiskGeneration(World world, IChunkLoader loader, int x, int z,
      BooleanSupplier cancelled, BooleanSupplier stopping) {
    this(world, loader, x, z, cancelled, stopping, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));
  }

  SimViewDiskGeneration(World world, IChunkLoader loader, int x, int z,
      BooleanSupplier cancelled, BooleanSupplier stopping, Executor retryExecutor) {
    this.world = world; this.loader = loader; this.x = x; this.z = z;
    this.cancelled = cancelled; this.stopping = stopping;
    this.retryExecutor = retryExecutor;
  }

  static CompletableFuture<List<ToClientPacket>> load(World world, int x, int z, int[] ys,
      boolean generate, Executor executor, BooleanSupplier cancelled, BooleanSupplier stopping) {
    IChunkLoader loader = world.getChunkStore().getLoader();
    if (loader == null) { return CompletableFuture.completedFuture(List.of()); }
    if (!generate) { return SimViewDiskSnapshotReader.load(loader, x, z, ys, executor, cancelled); }
    var batch = new SimViewDiskGeneration(world, loader, x, z, cancelled, stopping);
    return batch.finish(SimViewDiskSnapshotReader.load(loader, x, z, ys, executor, cancelled, batch));
  }

  @Override public CompletableFuture<Holder<ChunkStore>> column() {
    return generate(() -> world.getChunkStore().getChunkReferenceAsync(
        ChunkUtil.indexChunk(x, z), GetChunkFlags.NO_SET_TICKING_SYNC), null);
  }

  @Override public CompletableFuture<Holder<ChunkStore>> section(int y) {
    return generate(() -> world.getChunkStore().getChunkSectionReferenceAsync(
        x, y, z, GetChunkFlags.NO_SET_TICKING_SYNC), y);
  }

  private boolean mayStart() {
    return !cancelled.getAsBoolean() && !stopping.getAsBoolean() && world.isAlive()
        && !world.isSavingLocked() && world.getWorldConfig().canSaveChunks()
        && world.getWorldConfig().shouldSaveNewChunks() && world.getWorldConfig().canUnloadChunks();
  }

  private CompletableFuture<Holder<ChunkStore>> generate(
      Supplier<CompletableFuture<Ref<ChunkStore>>> request, Integer sectionY) {
    if (stopping.getAsBoolean() || cancelled.getAsBoolean() || !world.isAlive()) {
      return CompletableFuture.completedFuture(null);
    }
    // Starting and copying must run on the world thread. Never cancel a shared native future.
    return CompletableFuture.supplyAsync(() -> mayStart() ? request.get()
        : CompletableFuture.<Ref<ChunkStore>>completedFuture(null), world).thenCompose(future -> future)
        .thenCompose(ref -> {
          if (ref == null || stopping.getAsBoolean() || !world.isAlive()) {
            return CompletableFuture.completedFuture(null);
          }
          return CompletableFuture.supplyAsync(() -> {
            if (!ref.isValid() || !world.isAlive()) { return null; }
            if (sectionY == null) { generatedColumn = true; }
            else { generatedSections.add(sectionY); }
            if (cancelled.getAsBoolean() || stopping.getAsBoolean()) { return null; }
            // Same public snapshot operation used by the native saver. Detached copies only
            // reach packet workers; no live holder metadata is mutated there.
            return world.getChunkStore().getStore().copySerializableEntity(ref);
          }, world);
        });
  }

  CompletableFuture<List<ToClientPacket>> finish(CompletableFuture<List<ToClientPacket>> snapshot) {
    return snapshot.handle((packets, failure) -> {
      if (!generatedColumn && generatedSections.isEmpty()) {
        return failure == null ? CompletableFuture.completedFuture(packets)
            : CompletableFuture.<List<ToClientPacket>>failedFuture(failure);
      }
      return queueSave().handle((ignored, queueFailure) -> null)
          .thenCompose(ignored -> waitForPersistence()).thenCompose(ignored -> {
        if (failure != null) { return CompletableFuture.<List<ToClientPacket>>failedFuture(failure); }
        return CompletableFuture.completedFuture(cancelled.getAsBoolean() || stopping.getAsBoolean() || !world.isAlive()
            ? List.<ToClientPacket>of() : packets);
      });
    }).thenCompose(future -> future);
  }

  private CompletableFuture<Void> queueSave() {
    if (stopping.getAsBoolean() || !world.isAlive()) { return CompletableFuture.completedFuture(null); }
    try { return CompletableFuture.runAsync(() -> {
      if (stopping.getAsBoolean() || !world.isAlive() || world.getChunkStore().getStore().isShutdown()) { return; }
      Ref<ChunkStore> ref = world.getChunkStore().getChunkReference(ChunkUtil.indexChunk(x, z));
      if (ref != null && ref.isValid()) {
        ChunkSavingSystems.Data saves = world.getChunkStore().getStore().getResource(ChunkStore.SAVE_RESOURCE);
        saves.push(ref); // Normal native admission, dirtiness, saving locks and failure recovery apply.
      }
    }, world); }
    catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
  }

  private CompletableFuture<Void> waitForPersistence() {
    var terminal = new CompletableFuture<Void>();
    pollPersistence(terminal);
    return terminal;
  }

  private void pollPersistence(CompletableFuture<Void> terminal) {
    if (stopping.getAsBoolean() || !world.isAlive()) { terminal.complete(null); return; }
    CompletableFuture<Boolean> saved;
    try {
      saved = generatedColumn ? loader.loadHolder(x, z).thenApply(holder -> holder != null)
          : CompletableFuture.completedFuture(true);
    } catch (RuntimeException exception) { saved = CompletableFuture.failedFuture(exception); }
    if (loader instanceof IChunkLoader.Cubic cubic) {
      for (int y : generatedSections) {
        saved = saved.thenCompose(complete -> !complete ? CompletableFuture.completedFuture(false)
            : cubic.loadSectionHolder(x, y, z).thenApply(holder -> holder != null));
      }
    }
    // Each poll completes before the next starts: one terminal promise and at most one
    // scheduled retry per batch, even when persistence stalls indefinitely.
    saved.handle((complete, failure) -> failure == null && complete).thenAccept(complete -> {
      if (complete || stopping.getAsBoolean() || !world.isAlive()) { terminal.complete(null); return; }
      try { CompletableFuture.runAsync(() -> pollPersistence(terminal), retryExecutor); }
      catch (RuntimeException exception) {
        if (stopping.getAsBoolean() || !world.isAlive()) { terminal.complete(null); }
        // Otherwise fail closed: do not release a dirty generation admission slot.
      }
    });
  }
}
