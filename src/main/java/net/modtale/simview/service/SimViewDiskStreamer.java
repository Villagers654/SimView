package net.modtale.simview.service;

import com.hypixel.hytale.math.iterator.CircleSpiralIterator;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.protocol.packets.world.UnloadChunks;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;

/** Client writes and ownership changes run on the world thread, never on an IO worker. */
public final class SimViewDiskStreamer implements AutoCloseable {
  static final int MAX_JOBS = 4;
  static final int MAX_SCANS_PER_TICK = 256;
  static final int RETRY_TICKS = 600;
  static final int MAX_BYTES_PER_TICK = SimViewDiskSnapshotReader.MAX_PACKET_BYTES;

  @FunctionalInterface
  interface SnapshotSource {
    CompletableFuture<List<ToClientPacket>> load(World world, int x, int z, int[] sectionYs, boolean generate,
        Executor executor, BooleanSupplier cancelled);
  }

  private final Map<UUID, View> views = new HashMap<>();
  private final Set<Job> jobs = new HashSet<>();
  private final ThreadPoolExecutor workers;
  private final SnapshotSource source;
  private volatile boolean closed;
  private final BooleanSupplier generationAllowed;
  private long nextWarningNanos;

  public SimViewDiskStreamer(BooleanSupplier generationAllowed) {
    this(null, generationAllowed);
  }

  SimViewDiskStreamer(SnapshotSource source) {
    this(source, () -> true);
  }

  private SimViewDiskStreamer(SnapshotSource source, BooleanSupplier generationAllowed) {
    this.generationAllowed = generationAllowed;
    this.source = source == null ? (world, x, z, ys, generate, executor, cancelled) ->
        SimViewDiskGeneration.load(world, x, z, ys, generate, executor, cancelled, () -> closed) : source;
    workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_JOBS), task -> {
      Thread thread = new Thread(task, "SimView-disk-packets");
      thread.setDaemon(true);
      return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
  }

  public synchronized void tick(World world, PlayerRef player, ChunkTracker tracker, Vector3d position,
      float dt, int simulationBlocks, int viewBlocks, SimViewStreamingBudget.Budget budget, boolean generateMissing) {
    reap();
    if (closed || !player.isValid() || !Objects.equals(player.getWorldUuid(), world.getWorldConfig().getUuid())) { return; }
    var channel = player.getPacketHandler().getChannel(StreamType.Game);
    boolean writable = tracker.isReadyForChunks() && channel != null && channel.isWritable();
    int x = ChunkUtil.chunkCoordinate(position.x());
    int y = ChunkUtil.chunkCoordinate(position.y());
    int z = ChunkUtil.chunkCoordinate(position.z());
    int inner = simulationBlocks / ChunkUtil.SIZE;
    int outer = viewBlocks / ChunkUtil.SIZE;
    View view = views.get(player.getUuid());
    if (view != null && view.world != world) {
      discard(player.getUuid()); // The client world reset owns old-world terrain removal.
      view = null;
    }
    if (view == null) {
      view = new View(world, player, tracker);
      views.put(player.getUuid(), view);
    }
    view.tracker = tracker;
    if (!view.initialized || view.x != x || view.y != y || view.z != z || view.inner != inner || view.outer != outer
        || view.generateMissing != generateMissing) {
      cancel(view, true);
      view.prune(x, y, z, inner, outer);
      view.x = x; view.y = y; view.z = z; view.inner = inner; view.outer = outer;
      view.initialized = true;
      view.generateMissing = generateMissing;
      view.iterator.init(x, z, 0, outer);
      view.rescanAt = 0;
    }
    boolean nativeReset = false;
    for (var entry : view.columns.entrySet()) {
      Column column = entry.getValue();
      boolean nativeVisible = distanceSquared(x, z, entry.getKey()) <= inner * inner;
      boolean nativeLoaded = (column.nativeVisible || column.nativeLoaded || nativeVisible)
          && tracker.isLoaded(entry.getKey());
      if ((column.nativeVisible && !nativeVisible) || (column.nativeLoaded && !nativeLoaded)) {
        // A native column reset can erase vertical disk sections too. Explicit cleanup
        // also covers columns native streaming had not actually finished loading yet.
        view.unload(Map.of(entry.getKey(), column));
        column.sections.clear();
        column.metadata = false;
        column.retryAt = 0;
        nativeReset = true;
      }
      column.nativeVisible = nativeVisible;
      column.nativeLoaded = nativeLoaded;
    }
    if (nativeReset) {
      cancel(view, true);
      view.iterator.init(x, z, 0, outer);
      view.rescanAt = 0;
    }
    view.ticks++;
    if (!writable || outer <= inner) { view.waitingForSlot = false; return; }
    if (!Float.isFinite(dt) || dt < 0) { dt = 0; }
    view.tokens = Math.min(budget.perTick(), view.tokens + Math.min(dt, 1) * budget.perSecond());
    int allowance = Math.min(budget.perTick(), (int) view.tokens);
    if (allowance <= 0) { return; }
    int bytes = 0;
    Job job = view.job;
    if (job != null && job.future.isDone()) {
      List<ToClientPacket> packets;
      try { packets = job.future.join(); }
      catch (RuntimeException exception) {
        long now = System.nanoTime();
        if (now - nextWarningNanos >= 0) {
          world.getLogger().atWarning().withCause(exception).log("SimView disk snapshot failed; retrying after scan backoff.");
          nextWarningNanos = now + TimeUnit.SECONDS.toNanos(30);
        }
        packets = List.of();
      }
      Column column = view.columns.get(job.index);
      while (job.cursor < packets.size() && allowance > 0) {
        ToClientPacket packet = packets.get(job.cursor);
        if (packet instanceof SetColumn && tracker.isLoaded(job.index)) {
          job.cursor++; // Keep live native column metadata when only vertical cold sections are supplied.
          continue;
        }
        int size = packet.computeSize();
        if (size > MAX_BYTES_PER_TICK - bytes) { break; }
        if (packet instanceof SetColumn) { column.metadata = true; }
        if (packet instanceof SetChunk section) {
          column.mark(section.y);
          job.sent.mark(section.y);
        }
        player.getPacketHandler().writeNoCache(packet);
        job.cursor++;
        bytes += size;
        if (packet instanceof SetChunk) { allowance--; view.tokens--; }
      }
      if (job.cursor == packets.size()) {
        jobs.remove(job);
        view.job = null;
        view.waitingForSlot = true;
        View completed = view;
        if (views.values().stream().anyMatch(other -> other != completed && other.waitingForSlot)) {
          return; // Yield the released global slot to another player already waiting for admission.
        }
      }
    }
    if (view.job != null || view.ticks < view.rescanAt) { return; }
    if (jobs.size() >= MAX_JOBS) { view.waitingForSlot = true; return; }
    view.waitingForSlot = false;
    for (int scans = 0; scans < MAX_SCANS_PER_TICK && view.iterator.hasNext(); scans++) {
      long index = view.iterator.next();
      int horizontal = distanceSquared(x, z, index);
      if (horizontal > outer * outer) { continue; }
      Column column = view.columns.computeIfAbsent(index, ignored -> new Column());
      column.nativeVisible = horizontal <= inner * inner;
      column.nativeLoaded = column.nativeVisible && tracker.isLoaded(index);
      if (view.ticks < column.retryAt) { continue; }
      int vertical = (int) Math.sqrt(outer * outer - horizontal);
      var needed = new it.unimi.dsi.fastutil.ints.IntArrayList();
      for (int sectionY = y - vertical; sectionY <= y + vertical; sectionY++) {
        int dy = sectionY - y;
        if (horizontal + dy * dy > inner * inner && !column.has(sectionY)) { needed.add(sectionY); }
      }
      if (needed.isEmpty()) { continue; }
      column.retryAt = view.ticks + RETRY_TICKS;
      Job next = new Job(index);
      jobs.add(next);
      view.job = next;
      try {
        next.future = source.load(world, ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index),
            needed.toIntArray(), generateMissing, workers,
            () -> next.cancelled || closed || (generateMissing && !generationAllowed.getAsBoolean()));
      } catch (RuntimeException exception) {
        next.future = CompletableFuture.failedFuture(exception);
      }
      next.future.whenComplete((packets, failure) -> {
        synchronized (SimViewDiskStreamer.this) {
          if (next.cancelled) { jobs.remove(next); }
        }
      });
      return;
    }
    if (!view.iterator.hasNext()) {
      view.iterator.init(x, z, 0, outer);
      view.rescanAt = view.ticks + RETRY_TICKS;
    }
  }

  public synchronized void leave(PlayerRef player) {
    View view = views.remove(player.getUuid());
    if (view == null) { return; }
    cancel(view, false);
    if (player.isValid() && view.world.getWorldConfig().getUuid().equals(player.getWorldUuid())) {
      view.unload(view.columns);
    }
    view.columns.clear();
    reap();
  }

  public synchronized void discard(UUID player) {
    View view = views.remove(player);
    if (view != null) { cancel(view, false); view.columns.clear(); }
    reap();
  }

  private static void cancel(View view, boolean unloadPartial) {
    if (view.job != null) {
      Job job = view.job;
      job.cancelled = true;
      if (unloadPartial && !job.sent.sections.isEmpty()) {
        view.unload(Map.of(job.index, job.sent));
        Column column = view.columns.get(job.index);
        job.sent.forEach(column::remove);
        column.retryAt = 0;
      }
      view.job = null;
    }
  }

  private void reap() {
    // A cancelled client cannot release a slot while its underlying IO is still running.
    jobs.removeIf(job -> job.cancelled && job.future != null && job.future.isDone());
  }

  public synchronized int inFlight() { reap(); return jobs.size(); }
  public synchronized int sentColumns(UUID player) {
    View view = views.get(player);
    return view == null ? 0 : (int) view.columns.values().stream()
        .filter(column -> column.metadata || !column.sections.isEmpty()).count();
  }

  @Override public synchronized void close() {
    if (closed) { return; }
    closed = true;
    var cleanup = new ArrayList<>(views.values());
    views.clear();
    for (View view : cleanup) {
      cancel(view, false);
      try {
        view.world.execute(() -> {
          if (view.player.isValid() && view.world.getWorldConfig().getUuid().equals(view.player.getWorldUuid())) {
            view.unload(view.columns);
          }
          view.columns.clear();
        });
      } catch (RuntimeException exception) { view.columns.clear(); }
    }
    for (Job job : jobs) { job.cancelled = true; }
    workers.shutdown();
    reap();
  }

  private static int distanceSquared(int x, int z, long index) {
    long dx = (long) ChunkUtil.xOfChunkIndex(index) - x;
    long dz = (long) ChunkUtil.zOfChunkIndex(index) - z;
    return (int) Math.min(Integer.MAX_VALUE, dx * dx + dz * dz);
  }

  private static final class Job {
    final long index;
    final Column sent = new Column();
    volatile boolean cancelled;
    CompletableFuture<List<ToClientPacket>> future;
    int cursor;
    Job(long index) { this.index = index; }
  }

  private static final class Column {
    int base;
    BitSet sections = new BitSet();
    boolean metadata;
    boolean nativeVisible, nativeLoaded;
    long retryAt;
    boolean has(int y) { return y >= base && sections.get(y - base); }
    void remove(int y) { if (y >= base) { sections.clear(y - base); } }
    void mark(int y) {
      if (sections.isEmpty()) { base = y; }
      if (y < base) {
        BitSet shifted = new BitSet();
        forEach(oldY -> shifted.set(oldY - y));
        sections = shifted;
        base = y;
      }
      sections.set(y - base);
    }
    void forEach(java.util.function.IntConsumer action) {
      for (int bit = sections.nextSetBit(0); bit >= 0; bit = sections.nextSetBit(bit + 1)) {
        action.accept(base + bit);
      }
    }
    void compact() {
      int first = sections.nextSetBit(0);
      if (first > 0) {
        sections = sections.get(first, sections.length());
        base += first;
      }
    }
  }

  private static final class View {
    final World world;
    final PlayerRef player;
    ChunkTracker tracker;
    final Map<Long, Column> columns = new HashMap<>();
    final CircleSpiralIterator iterator = new CircleSpiralIterator();
    int x, y, z, inner, outer;
    long ticks, rescanAt;
    boolean initialized, waitingForSlot, generateMissing;
    float tokens;
    Job job;
    View(World world, PlayerRef player, ChunkTracker tracker) {
      this.world = world; this.player = player; this.tracker = tracker;
    }

    void prune(int newX, int newY, int newZ, int newInner, int newOuter) {
      Map<Long, Column> removed = new HashMap<>();
      var iterator = columns.entrySet().iterator();
      while (iterator.hasNext()) {
        var entry = iterator.next();
        long index = entry.getKey();
        Column column = entry.getValue();
        int horizontal = distanceSquared(newX, newZ, index);
        Column removal = new Column();
        column.forEach(sectionY -> {
          long dy = (long) sectionY - newY;
          long distance = horizontal + dy * dy;
          if (distance <= newInner * newInner || distance > newOuter * newOuter) { removal.mark(sectionY); }
        });
        removal.forEach(column::remove);
        column.compact();
        if (column.sections.isEmpty()) {
          removal.metadata = column.metadata;
          column.metadata = false;
        }
        if (removal.metadata || !removal.sections.isEmpty()) { removed.put(index, removal); }
        if (horizontal > newOuter * newOuter) { iterator.remove(); }
        else { column.retryAt = 0; }
      }
      unload(removed);
    }

    void unload(Map<Long, Column> removed) {
      if (removed.isEmpty()) { return; }
      List<int[]> reloads = new ArrayList<>();
      tracker.forEachLoadedSection((x, y, z) -> {
        Column column = removed.get(ChunkUtil.indexChunk(x, z));
        if (column != null && column.has(y)) { reloads.add(new int[] {x, y, z}); }
      });
      // Bound each packet to the native unload batch size; no individual packet per section.
      var sectionBatch = new it.unimi.dsi.fastutil.ints.IntArrayList();
      var columnBatch = new it.unimi.dsi.fastutil.ints.IntArrayList();
      for (var entry : removed.entrySet()) {
        long index = entry.getKey();
        int x = ChunkUtil.xOfChunkIndex(index), z = ChunkUtil.zOfChunkIndex(index);
        entry.getValue().forEach(y -> {
          sectionBatch.add(x); sectionBatch.add(y); sectionBatch.add(z);
          if (sectionBatch.size() >= 768) {
            player.getPacketHandler().writeNoCache(new UnloadChunks(sectionBatch.toIntArray(), null));
            sectionBatch.clear();
          }
        });
        if (entry.getValue().metadata && !tracker.isLoaded(index)) {
          columnBatch.add(x); columnBatch.add(z);
          if (columnBatch.size() >= 1024) {
            player.getPacketHandler().writeNoCache(new UnloadChunks(null, columnBatch.toIntArray()));
            columnBatch.clear();
          }
        }
      }
      if (!sectionBatch.isEmpty() || !columnBatch.isEmpty()) {
        player.getPacketHandler().writeNoCache(new UnloadChunks(
            sectionBatch.isEmpty() ? null : sectionBatch.toIntArray(),
            columnBatch.isEmpty() ? null : columnBatch.toIntArray()));
      }
      // The native tracker may already own a promoted section. Reset only our sections,
      // preserve native column metadata, then request resends through the current 3D API.
      for (int[] section : reloads) { tracker.removeForReload(section[0], section[1], section[2]); }
    }
  }
}
