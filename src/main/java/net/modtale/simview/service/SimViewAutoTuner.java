package net.modtale.simview.service;

import net.modtale.simview.config.SimViewAdjustmentMode;
import net.modtale.simview.config.SimViewConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SimViewAutoTuner {

  private static final long STALE_PLAYER_TICKS = 6000L;
  private static final long STALE_PLAYER_PRUNE_INTERVAL_TICKS = 200L;

  private final int configuredHytaleViewDistanceChunks;
  private final SimViewMsptTracker msptTracker;
  private final ConcurrentHashMap<UUID, PlayerSample> playerSamples = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, WorldTickSample> worldLastTick = new ConcurrentHashMap<>();
  private final Deque<MsptChunkRecord> msptChunkHistory = new ArrayDeque<>();

  private volatile int activeTargetViewDistanceChunks;
  private volatile int activeTargetSimulationDistanceChunks;
  private volatile long observedServerTicks;
  private long lastCheckTick;
  private long lastStalePlayerPruneTick;

  private int viewConsecutiveIncreaseChecks;
  private int viewConsecutiveDecreaseChecks;
  private int simulationConsecutiveIncreaseChecks;
  private int simulationConsecutiveDecreaseChecks;

  private AdjustmentCandidate lastViewCandidate = AdjustmentCandidate.STAY;
  private AdjustmentCandidate lastSimulationCandidate = AdjustmentCandidate.STAY;
  private double lastObservedMspt = 50.0D;

  public SimViewAutoTuner(int configuredHytaleViewDistanceChunks, SimViewConfig config) {
    this.configuredHytaleViewDistanceChunks = Math.max(0, configuredHytaleViewDistanceChunks);
    this.msptTracker = new SimViewMsptTracker(config.reactiveMsptCollectionPeriodTicks());
    this.activeTargetSimulationDistanceChunks =
        clampSimulationTarget(config, config.targetSimulationDistanceChunks(), Integer.MAX_VALUE);
    this.activeTargetViewDistanceChunks =
        clampViewTarget(config, config.targetViewDistanceChunks(), activeTargetSimulationDistanceChunks);
    normalizeTargets(config);
  }

  public synchronized void updateConfig(SimViewConfig config) {
    msptTracker.setCollectionPeriodTicks(config.reactiveMsptCollectionPeriodTicks());

    if (config.adjustmentMode() == SimViewAdjustmentMode.OFF) {
      activeTargetViewDistanceChunks =
          clampViewTarget(config, config.targetViewDistanceChunks(), activeTargetSimulationDistanceChunks);
      viewConsecutiveIncreaseChecks = 0;
      viewConsecutiveDecreaseChecks = 0;
      lastViewCandidate = AdjustmentCandidate.STAY;
    }

    if (config.simulationAdjustmentMode() == SimViewAdjustmentMode.OFF) {
      activeTargetSimulationDistanceChunks =
          clampSimulationTarget(config, config.targetSimulationDistanceChunks(), activeTargetViewDistanceChunks);
      simulationConsecutiveIncreaseChecks = 0;
      simulationConsecutiveDecreaseChecks = 0;
      lastSimulationCandidate = AdjustmentCandidate.STAY;
    }

    normalizeTargets(config);

    if (config.adjustmentMode() == SimViewAdjustmentMode.OFF
        && config.simulationAdjustmentMode() == SimViewAdjustmentMode.OFF) {
      msptChunkHistory.clear();
    }
  }

  public int activeTargetViewDistanceChunks() {
    return activeTargetViewDistanceChunks;
  }

  public int activeTargetSimulationDistanceChunks() {
    return activeTargetSimulationDistanceChunks;
  }

  public boolean observe(
      UUID playerUuid,
      UUID worldUuid,
      long worldTick,
      float deltaSeconds,
      SimViewConfig config,
      int requestedViewDistanceChunks) {
    if (playerUuid == null) {
      return false;
    }
    UUID resolvedWorldUuid = worldUuid == null ? playerUuid : worldUuid;

    PlayerSample playerSample = playerSamples.get(playerUuid);
    if (playerSample == null) {
      PlayerSample newSample =
          new PlayerSample(Math.max(0, requestedViewDistanceChunks), observedServerTicks);
      PlayerSample previousSample = playerSamples.putIfAbsent(playerUuid, newSample);
      playerSample = previousSample == null ? newSample : previousSample;
    }
    if (playerSample != null) {
      playerSample.requestedViewDistanceChunks = Math.max(0, requestedViewDistanceChunks);
      playerSample.lastSeenServerTick = observedServerTicks;
    }

    WorldTickSample previousTick = worldLastTick.get(resolvedWorldUuid);
    if (previousTick == null) {
      WorldTickSample newTick = new WorldTickSample(worldTick);
      previousTick = worldLastTick.putIfAbsent(resolvedWorldUuid, newTick);
      if (previousTick == null) {
        return observeServerTick(deltaSeconds, config);
      }
    }

    if (!previousTick.advanceIfNewer(worldTick)) {
      return false;
    }

    return observeServerTick(deltaSeconds, config);
  }

  public synchronized AutoTuneSnapshot snapshot(SimViewConfig config) {
    long coldTarget = Math.max(0L, config.proactiveGlobalColdChunkCountTarget());
    long tickingTarget = Math.max(0L, config.proactiveGlobalTickingChunkCountTarget());
    long estimatedColdChunks =
        estimateGlobalColdChunks(activeTargetViewDistanceChunks, activeTargetSimulationDistanceChunks);
    long estimatedTickingChunks = estimateGlobalTickingChunks(activeTargetSimulationDistanceChunks);

    return new AutoTuneSnapshot(
        activeTargetViewDistanceChunks,
        activeTargetSimulationDistanceChunks,
        lastObservedMspt,
        estimatedColdChunks,
        coldTarget,
        estimatedTickingChunks,
        tickingTarget,
        observedServerTicks,
        viewConsecutiveIncreaseChecks,
        viewConsecutiveDecreaseChecks,
        simulationConsecutiveIncreaseChecks,
        simulationConsecutiveDecreaseChecks,
        lastViewCandidate,
        lastSimulationCandidate);
  }

  public synchronized void clear() {
    playerSamples.clear();
    worldLastTick.clear();
    msptChunkHistory.clear();
    msptTracker.clear();
    observedServerTicks = 0L;
    lastCheckTick = 0L;
    lastStalePlayerPruneTick = 0L;

    viewConsecutiveIncreaseChecks = 0;
    viewConsecutiveDecreaseChecks = 0;
    simulationConsecutiveIncreaseChecks = 0;
    simulationConsecutiveDecreaseChecks = 0;

    lastViewCandidate = AdjustmentCandidate.STAY;
    lastSimulationCandidate = AdjustmentCandidate.STAY;
    lastObservedMspt = 50.0D;
  }

  private boolean observeServerTick(float deltaSeconds, SimViewConfig config) {
    double tickDurationMs = Math.max(0.0D, deltaSeconds) * 1000.0D;
    msptTracker.addTickSample(tickDurationMs);
    lastObservedMspt = msptTracker.currentMspt();
    observedServerTicks++;

    if (observedServerTicks - lastStalePlayerPruneTick >= STALE_PLAYER_PRUNE_INTERVAL_TICKS) {
      lastStalePlayerPruneTick = observedServerTicks;
      pruneStalePlayers();
    }

    if (config.adjustmentMode() == SimViewAdjustmentMode.OFF
        && config.simulationAdjustmentMode() == SimViewAdjustmentMode.OFF) {
      return false;
    }

    long startupDelay = Math.max(0L, config.adjustmentStartupDelayTicks());
    if (observedServerTicks <= startupDelay) {
      return false;
    }

    int ticksPerCheck = Math.max(1, config.adjustmentTicksPerCheck());
    if (observedServerTicks - lastCheckTick < ticksPerCheck) {
      return false;
    }

    lastCheckTick = observedServerTicks;
    return runAdjustmentCheck(config);
  }

  private boolean runAdjustmentCheck(SimViewConfig config) {
    int previousViewTarget = activeTargetViewDistanceChunks;
    int previousSimulationTarget = activeTargetSimulationDistanceChunks;
    long currentColdChunks =
        estimateGlobalColdChunks(activeTargetViewDistanceChunks, activeTargetSimulationDistanceChunks);
    long currentTickingChunks = estimateGlobalTickingChunks(activeTargetSimulationDistanceChunks);
    msptChunkHistory.addLast(
        new MsptChunkRecord(System.currentTimeMillis(), lastObservedMspt, currentColdChunks, currentTickingChunks));
    purgeMsptHistory(config.reactiveMsptPredictionHistoryMinutes());

    AdjustmentCandidate viewCandidate = candidateForView(config, currentColdChunks);
    AdjustmentCandidate simulationCandidate = candidateForSimulation(config, currentTickingChunks);

    lastViewCandidate = viewCandidate;
    lastSimulationCandidate = simulationCandidate;

    applyViewCandidate(config, viewCandidate);
    applySimulationCandidate(config, simulationCandidate);
    normalizeTargets(config);
    return activeTargetViewDistanceChunks != previousViewTarget
        || activeTargetSimulationDistanceChunks != previousSimulationTarget;
  }

  private AdjustmentCandidate candidateForView(SimViewConfig config, long currentColdChunks) {
    if (config.adjustmentMode() == SimViewAdjustmentMode.OFF) {
      return AdjustmentCandidate.STAY;
    }

    AdjustmentCandidate candidate = AdjustmentCandidate.STAY;
    if (config.adjustmentMode().includesProactive()) {
      candidate = candidate.strongest(proactiveViewCandidate(config, currentColdChunks));
    }
    if (config.adjustmentMode().includesReactive()) {
      candidate = candidate.strongest(reactiveViewCandidate(config, currentColdChunks));
    }
    return candidate;
  }

  private AdjustmentCandidate candidateForSimulation(SimViewConfig config, long currentTickingChunks) {
    if (config.simulationAdjustmentMode() == SimViewAdjustmentMode.OFF) {
      return AdjustmentCandidate.STAY;
    }

    AdjustmentCandidate candidate = AdjustmentCandidate.STAY;
    if (config.simulationAdjustmentMode().includesProactive()) {
      candidate = candidate.strongest(proactiveSimulationCandidate(config, currentTickingChunks));
    }
    if (config.simulationAdjustmentMode().includesReactive()) {
      candidate = candidate.strongest(reactiveSimulationCandidate(config, currentTickingChunks));
    }
    return candidate;
  }

  private void applyViewCandidate(SimViewConfig config, AdjustmentCandidate candidate) {
    switch (candidate) {
      case INCREASE -> {
        viewConsecutiveIncreaseChecks++;
        viewConsecutiveDecreaseChecks = 0;
        if (viewConsecutiveIncreaseChecks >= Math.max(1, config.adjustmentPassedChecksForIncrease())
            && activeTargetViewDistanceChunks < clampViewMaximum(config, activeTargetSimulationDistanceChunks)) {
          activeTargetViewDistanceChunks++;
        }
      }
      case DECREASE -> {
        viewConsecutiveDecreaseChecks++;
        viewConsecutiveIncreaseChecks = 0;
        if (viewConsecutiveDecreaseChecks >= Math.max(1, config.adjustmentPassedChecksForDecrease())
            && activeTargetViewDistanceChunks > clampViewMinimum(config, activeTargetSimulationDistanceChunks)) {
          activeTargetViewDistanceChunks--;
        }
      }
      case STAY -> {
        viewConsecutiveIncreaseChecks = 0;
        viewConsecutiveDecreaseChecks = 0;
      }
    }

    activeTargetViewDistanceChunks =
        clampViewTarget(config, activeTargetViewDistanceChunks, activeTargetSimulationDistanceChunks);
  }

  private void applySimulationCandidate(SimViewConfig config, AdjustmentCandidate candidate) {
    switch (candidate) {
      case INCREASE -> {
        simulationConsecutiveIncreaseChecks++;
        simulationConsecutiveDecreaseChecks = 0;
        if (simulationConsecutiveIncreaseChecks
                >= Math.max(1, config.simulationAdjustmentPassedChecksForIncrease())
            && activeTargetSimulationDistanceChunks
                < clampSimulationMaximum(config, activeTargetViewDistanceChunks)) {
          activeTargetSimulationDistanceChunks++;
        }
      }
      case DECREASE -> {
        simulationConsecutiveDecreaseChecks++;
        simulationConsecutiveIncreaseChecks = 0;
        if (simulationConsecutiveDecreaseChecks
                >= Math.max(1, config.simulationAdjustmentPassedChecksForDecrease())
            && activeTargetSimulationDistanceChunks > clampSimulationMinimum(config)) {
          activeTargetSimulationDistanceChunks--;
        }
      }
      case STAY -> {
        simulationConsecutiveIncreaseChecks = 0;
        simulationConsecutiveDecreaseChecks = 0;
      }
    }

    activeTargetSimulationDistanceChunks =
        clampSimulationTarget(config, activeTargetSimulationDistanceChunks, activeTargetViewDistanceChunks);
  }

  private AdjustmentCandidate proactiveViewCandidate(SimViewConfig config, long currentColdChunks) {
    long coldChunkTarget = Math.max(0L, config.proactiveGlobalColdChunkCountTarget());
    if (coldChunkTarget <= 0L) {
      return AdjustmentCandidate.STAY;
    }

    if (currentColdChunks < coldChunkTarget) {
      long increasedColdChunks =
          estimateGlobalColdChunks(activeTargetViewDistanceChunks + 1, activeTargetSimulationDistanceChunks);
      if (increasedColdChunks <= coldChunkTarget) {
        return AdjustmentCandidate.INCREASE;
      }
      return AdjustmentCandidate.STAY;
    }
    if (currentColdChunks > coldChunkTarget) {
      return AdjustmentCandidate.DECREASE;
    }
    return AdjustmentCandidate.STAY;
  }

  private AdjustmentCandidate reactiveViewCandidate(SimViewConfig config, long currentColdChunks) {
    double mspt = lastObservedMspt;
    if (mspt <= config.reactiveIncreaseMsptThreshold()) {
      if (!config.reactiveUseMsptPrediction()) {
        return AdjustmentCandidate.INCREASE;
      }
      long additionalColdChunks =
          Math.max(
              0L,
              estimateGlobalColdChunks(activeTargetViewDistanceChunks + 1, activeTargetSimulationDistanceChunks)
                  - currentColdChunks);
      double maxMsptPerChunk = maximumMsptPerColdChunk();
      if (mspt + (maxMsptPerChunk * additionalColdChunks) >= config.reactiveDecreaseMsptThreshold()) {
        return AdjustmentCandidate.STAY;
      }
      return AdjustmentCandidate.INCREASE;
    }

    if (mspt >= config.reactiveDecreaseMsptThreshold()) {
      return AdjustmentCandidate.DECREASE;
    }

    return AdjustmentCandidate.STAY;
  }

  private AdjustmentCandidate proactiveSimulationCandidate(SimViewConfig config, long currentTickingChunks) {
    long tickingChunkTarget = Math.max(0L, config.proactiveGlobalTickingChunkCountTarget());
    if (tickingChunkTarget <= 0L) {
      return AdjustmentCandidate.STAY;
    }

    if (currentTickingChunks < tickingChunkTarget) {
      long increasedTickingChunks = estimateGlobalTickingChunks(activeTargetSimulationDistanceChunks + 1);
      if (increasedTickingChunks <= tickingChunkTarget) {
        return AdjustmentCandidate.INCREASE;
      }
      return AdjustmentCandidate.STAY;
    }
    if (currentTickingChunks > tickingChunkTarget) {
      return AdjustmentCandidate.DECREASE;
    }
    return AdjustmentCandidate.STAY;
  }

  private AdjustmentCandidate reactiveSimulationCandidate(SimViewConfig config, long currentTickingChunks) {
    double mspt = lastObservedMspt;
    if (mspt <= config.reactiveIncreaseMsptThreshold()) {
      if (!config.reactiveUseMsptPrediction()) {
        return AdjustmentCandidate.INCREASE;
      }
      long additionalTickingChunks =
          Math.max(0L, estimateGlobalTickingChunks(activeTargetSimulationDistanceChunks + 1) - currentTickingChunks);
      double maxMsptPerChunk = maximumMsptPerTickingChunk();
      if (mspt + (maxMsptPerChunk * additionalTickingChunks) >= config.reactiveDecreaseMsptThreshold()) {
        return AdjustmentCandidate.STAY;
      }
      return AdjustmentCandidate.INCREASE;
    }

    if (mspt >= config.reactiveDecreaseMsptThreshold()) {
      return AdjustmentCandidate.DECREASE;
    }

    return AdjustmentCandidate.STAY;
  }

  private long estimateGlobalColdChunks(int targetViewDistanceChunks, int targetSimulationDistanceChunks) {
    int safeView = Math.max(0, targetViewDistanceChunks);
    int safeSimulation = Math.max(0, Math.min(targetSimulationDistanceChunks, safeView));
    long total = 0L;

    for (PlayerSample sample : playerSamples.values()) {
      int requested = sample.requestedViewDistanceChunks;
      int visible = Math.min(requested, safeView);
      int simulated = Math.min(visible, safeSimulation);
      total += coldChunksForRadii(simulated, visible);
    }

    return Math.max(0L, total);
  }

  private long estimateGlobalTickingChunks(int targetSimulationDistanceChunks) {
    int safeSimulation = Math.max(0, targetSimulationDistanceChunks);
    long total = 0L;

    for (PlayerSample sample : playerSamples.values()) {
      int requested = sample.requestedViewDistanceChunks;
      int simulated = Math.min(requested, safeSimulation);
      total += squareRadiusArea(simulated);
    }

    return Math.max(0L, total);
  }

  private static long coldChunksForRadii(int simulatedRadius, int visibleRadius) {
    int safeSimulated = Math.max(0, Math.min(simulatedRadius, visibleRadius));
    int safeVisible = Math.max(0, visibleRadius);
    long visibleArea = squareRadiusArea(safeVisible);
    long simulatedArea = squareRadiusArea(safeSimulated);
    return Math.max(0L, visibleArea - simulatedArea);
  }

  private static long squareRadiusArea(int radius) {
    long diameter = (long) (Math.max(0, radius) * 2) + 1L;
    return diameter * diameter;
  }

  private void purgeMsptHistory(int historyMinutes) {
    long historyLengthMillis = Math.max(1L, historyMinutes) * 60_000L;
    long now = System.currentTimeMillis();
    while (!msptChunkHistory.isEmpty()) {
      MsptChunkRecord oldest = msptChunkHistory.peekFirst();
      if (oldest == null || now - oldest.timestampMillis <= historyLengthMillis) {
        break;
      }
      msptChunkHistory.removeFirst();
    }
  }

  private double maximumMsptPerColdChunk() {
    double max = 0.0D;
    for (MsptChunkRecord record : msptChunkHistory) {
      if (record.coldChunks > 0L) {
        max = Math.max(max, record.mspt / (double) record.coldChunks);
      }
    }
    return max;
  }

  private double maximumMsptPerTickingChunk() {
    double max = 0.0D;
    for (MsptChunkRecord record : msptChunkHistory) {
      if (record.tickingChunks > 0L) {
        max = Math.max(max, record.mspt / (double) record.tickingChunks);
      }
    }
    return max;
  }

  private void pruneStalePlayers() {
    for (Map.Entry<UUID, PlayerSample> entry : playerSamples.entrySet()) {
      if (observedServerTicks - entry.getValue().lastSeenServerTick > STALE_PLAYER_TICKS) {
        playerSamples.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  private void normalizeTargets(SimViewConfig config) {
    activeTargetSimulationDistanceChunks =
        clampSimulationTarget(config, activeTargetSimulationDistanceChunks, Integer.MAX_VALUE);
    activeTargetViewDistanceChunks =
        clampViewTarget(config, activeTargetViewDistanceChunks, activeTargetSimulationDistanceChunks);
    activeTargetSimulationDistanceChunks =
        clampSimulationTarget(config, activeTargetSimulationDistanceChunks, activeTargetViewDistanceChunks);
    activeTargetViewDistanceChunks =
        clampViewTarget(config, activeTargetViewDistanceChunks, activeTargetSimulationDistanceChunks);
  }

  private int clampViewTarget(SimViewConfig config, int target, int simulationTarget) {
    int minimum = clampViewMinimum(config, simulationTarget);
    int maximum = clampViewMaximum(config, simulationTarget);
    return Math.max(minimum, Math.min(target, maximum));
  }

  private int clampViewMinimum(SimViewConfig config, int simulationTarget) {
    return Math.max(simulationTarget, config.minimumTargetViewDistanceChunks());
  }

  private int clampViewMaximum(SimViewConfig config, int simulationTarget) {
    return Math.max(clampViewMinimum(config, simulationTarget), config.maximumTargetViewDistanceChunks());
  }

  private int clampSimulationTarget(SimViewConfig config, int target, int viewLimit) {
    int minimum = clampSimulationMinimum(config);
    int maximum = clampSimulationMaximum(config, viewLimit);
    int resolvedTarget =
        target < 0
            ? config.clampedTargetSimulationDistanceChunks(configuredHytaleViewDistanceChunks, target)
            : target;
    return Math.max(minimum, Math.min(resolvedTarget, maximum));
  }

  private int clampSimulationMinimum(SimViewConfig config) {
    return Math.max(0, config.minimumTargetSimulationDistanceChunks());
  }

  private int clampSimulationMaximum(SimViewConfig config, int viewLimit) {
    int minimum = clampSimulationMinimum(config);
    int maximum = Math.max(minimum, config.maximumTargetSimulationDistanceChunks());
    if (viewLimit >= 0 && viewLimit != Integer.MAX_VALUE) {
      maximum = Math.min(maximum, Math.max(minimum, viewLimit));
    }
    return maximum;
  }

  private static final class PlayerSample {
    private volatile int requestedViewDistanceChunks;
    private volatile long lastSeenServerTick;

    private PlayerSample(int requestedViewDistanceChunks, long lastSeenServerTick) {
      this.requestedViewDistanceChunks = requestedViewDistanceChunks;
      this.lastSeenServerTick = lastSeenServerTick;
    }
  }

  private static final class WorldTickSample {
    private final AtomicLong tick;

    private WorldTickSample(long tick) {
      this.tick = new AtomicLong(tick);
    }

    private boolean advanceIfNewer(long nextTick) {
      while (true) {
        long previousTick = tick.get();
        if (nextTick <= previousTick) {
          return false;
        }
        if (tick.compareAndSet(previousTick, nextTick)) {
          return true;
        }
      }
    }
  }

  private record MsptChunkRecord(long timestampMillis, double mspt, long coldChunks, long tickingChunks) {}

  public record AutoTuneSnapshot(
      int activeTargetViewDistanceChunks,
      int activeTargetSimulationDistanceChunks,
      double mspt,
      long estimatedColdChunks,
      long proactiveColdChunkTarget,
      long estimatedTickingChunks,
      long proactiveTickingChunkTarget,
      long observedTicks,
      int viewConsecutiveIncreaseChecks,
      int viewConsecutiveDecreaseChecks,
      int simulationConsecutiveIncreaseChecks,
      int simulationConsecutiveDecreaseChecks,
      AdjustmentCandidate lastViewCandidate,
      AdjustmentCandidate lastSimulationCandidate) {}

  public enum AdjustmentCandidate {
    INCREASE,
    DECREASE,
    STAY;

    public AdjustmentCandidate strongest(AdjustmentCandidate other) {
      if (this == DECREASE || other == DECREASE) {
        return DECREASE;
      }
      if (this == STAY || other == STAY) {
        return STAY;
      }
      return INCREASE;
    }
  }
}
