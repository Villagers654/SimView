package net.modtale.simview.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.HytaleServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

public record SimViewConfig(
    boolean enabled,
    boolean guiEnabled,
    boolean disableJoinHintMessage,
    int targetViewDistanceBlocks,
    int targetSimulationDistanceBlocks,
    SimViewAdjustmentMode adjustmentMode,
    SimViewAdjustmentMode simulationAdjustmentMode,
    int minimumTargetViewDistanceBlocks,
    int maximumTargetViewDistanceBlocks,
    int minimumTargetSimulationDistanceBlocks,
    int maximumTargetSimulationDistanceBlocks,
    int adjustmentTicksPerCheck,
    int adjustmentStartupDelayTicks,
    int adjustmentPassedChecksForIncrease,
    int adjustmentPassedChecksForDecrease,
    int simulationAdjustmentPassedChecksForIncrease,
    int simulationAdjustmentPassedChecksForDecrease,
    long proactiveGlobalColdSectionCountTarget,
    long proactiveGlobalTickingSectionCountTarget,
    double reactiveIncreaseMsptThreshold,
    double reactiveDecreaseMsptThreshold,
    int reactiveMsptCollectionPeriodTicks,
    boolean reactiveUseMsptPrediction,
    int reactiveMsptPredictionHistoryMinutes,
    int maxSectionSendsPerSecond,
    int maxSectionSendsPerTick,
    boolean despawnEntitiesInColdChunks,
    double speedingNotSendBlocksPerTick,
    int speedingSectionSendsPerSecond,
    int speedingSectionSendsPerTick,
    int speedingCooldownTicks,
    SimViewStreamingMode streamingMode) {

  public static final int CHUNK_SIZE_BLOCKS = com.hypixel.hytale.math.util.ChunkUtil.SIZE;
  private static final String CONFIG_FILE = "simview.json";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public SimViewConfig {
    java.util.Objects.requireNonNull(streamingMode, "streamingMode");
    targetViewDistanceBlocks = boundedRadius(targetViewDistanceBlocks);
    targetSimulationDistanceBlocks = targetSimulationDistanceBlocks < 0 ? -1 : boundedRadius(targetSimulationDistanceBlocks);
    minimumTargetViewDistanceBlocks = boundedRadius(minimumTargetViewDistanceBlocks);
    maximumTargetViewDistanceBlocks = boundedRadius(maximumTargetViewDistanceBlocks);
    minimumTargetSimulationDistanceBlocks = boundedRadius(minimumTargetSimulationDistanceBlocks);
    maximumTargetSimulationDistanceBlocks = boundedRadius(maximumTargetSimulationDistanceBlocks);
    reactiveMsptCollectionPeriodTicks = Math.clamp(reactiveMsptCollectionPeriodTicks, 1, 6_000);
    maxSectionSendsPerSecond = Math.clamp(maxSectionSendsPerSecond, 0, 10_000);
    maxSectionSendsPerTick = Math.clamp(maxSectionSendsPerTick, 0, 128);
    speedingSectionSendsPerSecond = Math.clamp(speedingSectionSendsPerSecond, 0, 10_000);
    speedingSectionSendsPerTick = Math.clamp(speedingSectionSendsPerTick, 0, 128);
    reactiveMsptPredictionHistoryMinutes = Math.clamp(reactiveMsptPredictionHistoryMinutes, 1, 1440);
  }

  private static int boundedRadius(int radius) {
    return SimViewDistances.normalizeBlocks(radius);
  }

  public static SimViewConfig defaults() {
    int runtimeHytaleViewDistance = Math.max(0, HytaleServer.get().getConfig().getMaxViewRadius());
    return defaults(Math.min(runtimeHytaleViewDistance, com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker.MAX_HOT_LOADED_RADIUS));
  }

  static SimViewConfig defaults(int runtimeHytaleViewDistance) {
    return new SimViewConfig(
        true,
        true,
        false,
        1024,
        SimViewDistances.sectionsToBlocks(runtimeHytaleViewDistance),
        SimViewAdjustmentMode.OFF,
        SimViewAdjustmentMode.OFF,
        0,
        1024,
        0,
        SimViewDistances.sectionsToBlocks(runtimeHytaleViewDistance),
        600,
        2400,
        10,
        1,
        10,
        1,
        120_000L,
        0L,
        40.0D,
        47.0D,
        1200,
        true,
        30,
        0,
        0,
        true,
        1.2D,
        0,
        0,
        40,
        SimViewStreamingMode.NATIVE);
  }

  public static SimViewConfig load(Path dataDirectory) {
    return load(dataDirectory, defaults());
  }

  static SimViewConfig load(Path dataDirectory, SimViewConfig defaults) {
    Path configDirectory = dataDirectory.resolve("config");
    Path configFile = configDirectory.resolve(CONFIG_FILE);

    try {
      Files.createDirectories(configDirectory);
      if (Files.notExists(configFile)) {
        writeDefaults(configFile, defaults);
      }

      String json = Files.readString(configFile, StandardCharsets.UTF_8);
      SimViewConfig loaded = fromJson(json, defaults);
      if (schemaVersion(JsonParser.parseString(json).getAsJsonObject()) == 1) {
        Path backup = configDirectory.resolve("simview.v1.json");
        if (Files.notExists(backup)) {
          Files.copy(configFile, backup);
        }
        save(dataDirectory, loaded);
      }
      return loaded;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load SimView config from " + configFile, exception);
    }
  }

  public static void save(Path dataDirectory, SimViewConfig config) {
    Path configDirectory = dataDirectory.resolve("config");
    Path configFile = configDirectory.resolve(CONFIG_FILE);
    try {
      Files.createDirectories(configDirectory);
      Path temporary = Files.createTempFile(configDirectory, "simview-", ".tmp");
      try {
        Files.writeString(temporary, renderJson(config), StandardCharsets.UTF_8);
        try {
          Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
          Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to save SimView config to " + configFile, exception);
    }
  }

  public int clampedTargetSimulationDistanceBlocks(
      int configuredHytaleViewDistanceBlocks, int targetSimulationDistanceBlocks) {
    int configuredHytale = Math.max(0, configuredHytaleViewDistanceBlocks);
    int requestedTarget =
        targetSimulationDistanceBlocks < 0 ? configuredHytale : Math.max(0, targetSimulationDistanceBlocks);
    int minimum = Math.max(0, minimumTargetSimulationDistanceBlocks);
    int maximum = Math.max(minimum, maximumTargetSimulationDistanceBlocks);
    return Math.max(minimum, Math.min(requestedTarget, maximum));
  }

  public int clampedTargetViewDistanceBlocks(int simulationDistanceBlocks, int targetViewDistanceBlocks) {
    int minimum = Math.max(0, Math.max(simulationDistanceBlocks, minimumTargetViewDistanceBlocks));
    int maximum = Math.max(minimum, maximumTargetViewDistanceBlocks);
    return Math.max(minimum, Math.min(targetViewDistanceBlocks, maximum));
  }

  public int simulationDistanceCap(int configuredHytaleViewDistanceBlocks) {
    return simulationDistanceCap(configuredHytaleViewDistanceBlocks, targetSimulationDistanceBlocks);
  }

  public int simulationDistanceCap(int configuredHytaleViewDistanceBlocks, int activeTargetSimulationDistanceBlocks) {
    int configuredHytale = Math.max(0, configuredHytaleViewDistanceBlocks);
    return clampedTargetSimulationDistanceBlocks(configuredHytale, activeTargetSimulationDistanceBlocks);
  }

  public int extendedViewDistanceCap(int simulationDistanceBlocks) {
    return extendedViewDistanceCap(simulationDistanceBlocks, targetViewDistanceBlocks);
  }

  public int extendedViewDistanceCap(int simulationDistanceBlocks, int activeTargetViewDistanceBlocks) {
    int simulationDistance = Math.max(0, simulationDistanceBlocks);
    if (!enabled) {
      return simulationDistance;
    }
    int clampedTarget = clampedTargetViewDistanceBlocks(simulationDistance, activeTargetViewDistanceBlocks);
    return Math.max(simulationDistance, clampedTarget);
  }

  public int effectiveViewDistance(int requestedClientViewRadius, int serverLimitedViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    int serverLimited = Math.max(0, serverLimitedViewRadius);
    return Math.min(requested, serverLimited);
  }

  public int effectiveExtendedViewDistance(int simulationDistanceBlocks, int requestedClientViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(requested, extendedViewDistanceCap(simulationDistanceBlocks));
  }

  public int effectiveExtendedViewDistance(
      int simulationDistanceBlocks, int requestedClientViewRadius, int activeTargetViewDistanceBlocks) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(requested, extendedViewDistanceCap(simulationDistanceBlocks, activeTargetViewDistanceBlocks));
  }

  public int effectiveSimulationDistance(int simulationDistanceBlocks, int requestedClientViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(Math.max(0, simulationDistanceBlocks), requested);
  }

  public int effectiveSimulationDistance(
      int simulationDistanceBlocks, int requestedClientViewRadius, int serverLimitedViewRadius) {
    int viewDistance = effectiveViewDistance(requestedClientViewRadius, serverLimitedViewRadius);
    return Math.min(Math.max(0, simulationDistanceBlocks), viewDistance);
  }

  private static void writeDefaults(Path configFile, SimViewConfig defaults) throws IOException {
    Files.writeString(configFile, renderJson(defaults), StandardCharsets.UTF_8);
  }

  private static String renderJson(SimViewConfig config) {
    JsonObject root = new JsonObject();
    root.addProperty("schema-version", 2);

    JsonObject core = new JsonObject();
    core.addProperty("enabled", config.enabled());
    core.addProperty("gui-enabled", config.guiEnabled());
    core.addProperty("disable-join-hint-message", config.disableJoinHintMessage());

    JsonObject coreTarget = new JsonObject();
    coreTarget.addProperty("view-distance-blocks", config.targetViewDistanceBlocks());
    coreTarget.addProperty("simulation-distance-blocks", config.targetSimulationDistanceBlocks());
    core.add("target", coreTarget);

    JsonObject coreLimits = new JsonObject();
    JsonObject coreLimitsMinimum = new JsonObject();
    coreLimitsMinimum.addProperty("view-distance-blocks", config.minimumTargetViewDistanceBlocks());
    coreLimitsMinimum.addProperty("simulation-distance-blocks", config.minimumTargetSimulationDistanceBlocks());
    coreLimits.add("minimum", coreLimitsMinimum);

    JsonObject coreLimitsMaximum = new JsonObject();
    coreLimitsMaximum.addProperty("view-distance-blocks", config.maximumTargetViewDistanceBlocks());
    coreLimitsMaximum.addProperty("simulation-distance-blocks", config.maximumTargetSimulationDistanceBlocks());
    coreLimits.add("maximum", coreLimitsMaximum);
    core.add("limits", coreLimits);
    root.add("core", core);

    JsonObject adjustment = new JsonObject();
    JsonObject adjustmentMode = new JsonObject();
    adjustmentMode.addProperty("view", config.adjustmentMode().name().toLowerCase());
    adjustmentMode.addProperty("simulation", config.simulationAdjustmentMode().name().toLowerCase());
    adjustment.add("mode", adjustmentMode);

    JsonObject adjustmentCadence = new JsonObject();
    adjustmentCadence.addProperty("ticks-per-check", config.adjustmentTicksPerCheck());
    adjustmentCadence.addProperty("startup-delay-ticks", config.adjustmentStartupDelayTicks());
    adjustment.add("cadence", adjustmentCadence);

    JsonObject adjustmentChecks = new JsonObject();
    JsonObject adjustmentViewChecks = new JsonObject();
    adjustmentViewChecks.addProperty("for-increase", config.adjustmentPassedChecksForIncrease());
    adjustmentViewChecks.addProperty("for-decrease", config.adjustmentPassedChecksForDecrease());
    adjustmentChecks.add("view", adjustmentViewChecks);

    JsonObject adjustmentSimulationChecks = new JsonObject();
    adjustmentSimulationChecks.addProperty("for-increase", config.simulationAdjustmentPassedChecksForIncrease());
    adjustmentSimulationChecks.addProperty("for-decrease", config.simulationAdjustmentPassedChecksForDecrease());
    adjustmentChecks.add("simulation", adjustmentSimulationChecks);
    adjustment.add("checks", adjustmentChecks);

    JsonObject adjustmentProactive = new JsonObject();
    adjustmentProactive.addProperty("global-cold-section-count-target", config.proactiveGlobalColdSectionCountTarget());
    adjustmentProactive.addProperty("global-ticking-section-count-target", config.proactiveGlobalTickingSectionCountTarget());
    adjustment.add("proactive", adjustmentProactive);

    JsonObject adjustmentReactive = new JsonObject();
    adjustmentReactive.addProperty("increase-mspt-threshold", config.reactiveIncreaseMsptThreshold());
    adjustmentReactive.addProperty("decrease-mspt-threshold", config.reactiveDecreaseMsptThreshold());
    adjustmentReactive.addProperty("mspt-collection-period-ticks", config.reactiveMsptCollectionPeriodTicks());
    adjustmentReactive.addProperty("use-mspt-prediction", config.reactiveUseMsptPrediction());
    adjustmentReactive.addProperty("mspt-prediction-history-minutes", config.reactiveMsptPredictionHistoryMinutes());
    adjustment.add("reactive", adjustmentReactive);
    root.add("auto-adjustment", adjustment);

    JsonObject streaming = new JsonObject();
    streaming.addProperty("mode", config.streamingMode().name().toLowerCase(java.util.Locale.ROOT));
    streaming.addProperty("despawn-entities", config.despawnEntitiesInColdChunks());

    JsonObject streamingBudget = new JsonObject();
    streamingBudget.addProperty("section-sends-per-second", config.maxSectionSendsPerSecond());
    streamingBudget.addProperty("section-sends-per-tick", config.maxSectionSendsPerTick());
    streaming.add("budget", streamingBudget);
    root.add("section-streaming", streaming);

    JsonObject speeding = new JsonObject();
    speeding.addProperty("not-send-blocks-per-tick", config.speedingNotSendBlocksPerTick());
    speeding.addProperty("cooldown-ticks", config.speedingCooldownTicks());

    JsonObject speedingBudget = new JsonObject();
    speedingBudget.addProperty("section-sends-per-second", config.speedingSectionSendsPerSecond());
    speedingBudget.addProperty("section-sends-per-tick", config.speedingSectionSendsPerTick());
    speeding.add("budget", speedingBudget);
    root.add("speeding-adjustments", speeding);

    return GSON.toJson(root);
  }

  static SimViewConfig fromJson(String json, SimViewConfig defaults) {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    int version = schemaVersion(root);
    if (version != 1 && version != 2) {
      throw new IllegalArgumentException("Unsupported SimView config schema: " + version);
    }
    JsonObject migrationCore = objectAt(root, "core");
    JsonObject migrationLimits = objectAt(migrationCore, "limits");
    for (JsonObject distances : new JsonObject[] {objectAt(migrationCore, "target"),
        objectAt(migrationLimits, "minimum"), objectAt(migrationLimits, "maximum")}) {
      for (String kind : new String[] {"view", "simulation"}) {
        String legacy = kind + "-distance-chunks";
        String blocks = kind + "-distance-blocks";
        if ((version == 1 && distances.has(blocks)) || (version == 2 && distances.has(legacy))) {
          throw new IllegalArgumentException("Distance units do not match config schema " + version);
        }
        if (version == 1 && distances.has(legacy)) {
          java.math.BigDecimal raw = distances.get(legacy).getAsBigDecimal();
          int chunks = raw.signum() < 0 ? -1
              : raw.min(java.math.BigDecimal.valueOf(SimViewDistances.MAX_RADIUS_SECTIONS)).intValueExact();
          int value = chunks < 0 && kind.equals("simulation") ? -1
              : (int) Math.clamp(chunks, 0L, SimViewDistances.MAX_RADIUS_SECTIONS) * CHUNK_SIZE_BLOCKS;
          distances.remove(legacy);
          distances.addProperty(blocks, value);
        }
      }
    }

    JsonObject core = objectAt(root, "core");
    JsonObject coreTarget = objectAt(core, "target");
    JsonObject coreLimits = objectAt(core, "limits");
    JsonObject coreLimitsMinimum = objectAt(coreLimits, "minimum");
    JsonObject coreLimitsMaximum = objectAt(coreLimits, "maximum");

    JsonObject adjustment = objectAt(root, "auto-adjustment");
    JsonObject adjustmentMode = objectAt(adjustment, "mode");
    JsonObject adjustmentCadence = objectAt(adjustment, "cadence");
    JsonObject adjustmentChecks = objectAt(adjustment, "checks");
    JsonObject adjustmentViewChecks = objectAt(adjustmentChecks, "view");
    JsonObject adjustmentSimulationChecks = objectAt(adjustmentChecks, "simulation");
    JsonObject adjustmentProactive = objectAt(adjustment, "proactive");
    JsonObject adjustmentReactive = objectAt(adjustment, "reactive");

    JsonObject streaming = objectAt(root, "section-streaming");
    JsonObject streamingBudget = objectAt(streaming, "budget");

    JsonObject speeding = objectAt(root, "speeding-adjustments");
    JsonObject speedingBudget = objectAt(speeding, "budget");

    boolean enabled = boolAt(core, "enabled", defaults.enabled());
    boolean guiEnabled = boolAt(core, "gui-enabled", defaults.guiEnabled());
    boolean disableJoinHintMessage = boolAt(core, "disable-join-hint-message", defaults.disableJoinHintMessage());
    int targetViewDistanceBlocks = nonNegativeInt(intAt(coreTarget, "view-distance-blocks", defaults.targetViewDistanceBlocks()));
    int targetSimulationDistanceBlocks = intAt(coreTarget, "simulation-distance-blocks", defaults.targetSimulationDistanceBlocks());

    SimViewAdjustmentMode viewAdjustmentMode =
        SimViewAdjustmentMode.fromProperty(
            stringAt(adjustmentMode, "view", defaults.adjustmentMode().name().toLowerCase()),
            defaults.adjustmentMode());

    SimViewAdjustmentMode simulationAdjustmentMode =
        SimViewAdjustmentMode.fromProperty(
            stringAt(adjustmentMode, "simulation", defaults.simulationAdjustmentMode().name().toLowerCase()),
            defaults.simulationAdjustmentMode());

    int adjustmentTicksPerCheck = positiveInt(intAt(adjustmentCadence, "ticks-per-check", defaults.adjustmentTicksPerCheck()));
    int adjustmentStartupDelayTicks = nonNegativeInt(intAt(adjustmentCadence, "startup-delay-ticks", defaults.adjustmentStartupDelayTicks()));
    int adjustmentPassedChecksForIncrease = positiveInt(intAt(adjustmentViewChecks, "for-increase", defaults.adjustmentPassedChecksForIncrease()));
    int adjustmentPassedChecksForDecrease = positiveInt(intAt(adjustmentViewChecks, "for-decrease", defaults.adjustmentPassedChecksForDecrease()));

    int simulationAdjustmentPassedChecksForIncrease =
        positiveInt(intAt(adjustmentSimulationChecks, "for-increase", defaults.simulationAdjustmentPassedChecksForIncrease()));

    int simulationAdjustmentPassedChecksForDecrease =
        positiveInt(intAt(adjustmentSimulationChecks, "for-decrease", defaults.simulationAdjustmentPassedChecksForDecrease()));

    return new SimViewConfig(
        enabled,
        guiEnabled,
        disableJoinHintMessage,
        targetViewDistanceBlocks,
        targetSimulationDistanceBlocks,
        viewAdjustmentMode,
        simulationAdjustmentMode,
        nonNegativeInt(intAt(coreLimitsMinimum, "view-distance-blocks", defaults.minimumTargetViewDistanceBlocks())),
        nonNegativeInt(intAt(coreLimitsMaximum, "view-distance-blocks", defaults.maximumTargetViewDistanceBlocks())),
        nonNegativeInt(intAt(coreLimitsMinimum, "simulation-distance-blocks", defaults.minimumTargetSimulationDistanceBlocks())),
        nonNegativeInt(intAt(coreLimitsMaximum, "simulation-distance-blocks", defaults.maximumTargetSimulationDistanceBlocks())),
        adjustmentTicksPerCheck,
        adjustmentStartupDelayTicks,
        adjustmentPassedChecksForIncrease,
        adjustmentPassedChecksForDecrease,
        simulationAdjustmentPassedChecksForIncrease,
        simulationAdjustmentPassedChecksForDecrease,
        nonNegativeLong(longAt(adjustmentProactive, "global-cold-section-count-target", defaults.proactiveGlobalColdSectionCountTarget())),
        nonNegativeLong(longAt(adjustmentProactive, "global-ticking-section-count-target", defaults.proactiveGlobalTickingSectionCountTarget())),
        nonNegativeDouble(doubleAt(adjustmentReactive, "increase-mspt-threshold", defaults.reactiveIncreaseMsptThreshold())),
        nonNegativeDouble(doubleAt(adjustmentReactive, "decrease-mspt-threshold", defaults.reactiveDecreaseMsptThreshold())),
        positiveInt(intAt(adjustmentReactive, "mspt-collection-period-ticks", defaults.reactiveMsptCollectionPeriodTicks())),
        boolAt(adjustmentReactive, "use-mspt-prediction", defaults.reactiveUseMsptPrediction()),
        positiveInt(intAt(adjustmentReactive, "mspt-prediction-history-minutes", defaults.reactiveMsptPredictionHistoryMinutes())),
        nonNegativeInt(intAt(streamingBudget, "section-sends-per-second", defaults.maxSectionSendsPerSecond())),
        nonNegativeInt(intAt(streamingBudget, "section-sends-per-tick", defaults.maxSectionSendsPerTick())),
        boolAt(streaming, "despawn-entities", defaults.despawnEntitiesInColdChunks()),
        nonNegativeDouble(doubleAt(speeding, "not-send-blocks-per-tick", defaults.speedingNotSendBlocksPerTick())),
        nonNegativeInt(intAt(speedingBudget, "section-sends-per-second", defaults.speedingSectionSendsPerSecond())),
        nonNegativeInt(intAt(speedingBudget, "section-sends-per-tick", defaults.speedingSectionSendsPerTick())),
        nonNegativeInt(intAt(speeding, "cooldown-ticks", defaults.speedingCooldownTicks())),
        SimViewStreamingMode.parse(stringAt(streaming, "mode", defaults.streamingMode().name())));
  }

  private static JsonObject objectAt(JsonObject parent, String key) {
    if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
      return new JsonObject();
    }
    return parent.getAsJsonObject(key);
  }

  public int nativeLoadingDistance(int simulationDistanceBlocks, int viewDistanceBlocks) {
    return streamingMode == SimViewStreamingMode.DISK ? simulationDistanceBlocks : viewDistanceBlocks;
  }

  private static int schemaVersion(JsonObject root) {
    return root.has("schema-version") ? Integer.parseInt(root.get("schema-version").getAsString()) : 1;
  }

  private static String stringAt(JsonObject parent, String key, String fallback) {
    if (parent == null || !parent.has(key)) {
      return fallback;
    }
    try {
      return parent.get(key).getAsString().trim();
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static boolean boolAt(JsonObject parent, String key, boolean fallback) {
    if (parent == null || !parent.has(key)) {
      return fallback;
    }
    try {
      return parent.get(key).getAsBoolean();
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int intAt(JsonObject parent, String key, int fallback) {
    if (parent == null || !parent.has(key)) {
      return fallback;
    }
    try {
      java.math.BigDecimal value = parent.get(key).getAsBigDecimal();
      value.toBigIntegerExact();
      return value.max(java.math.BigDecimal.valueOf(Integer.MIN_VALUE))
          .min(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static long longAt(JsonObject parent, String key, long fallback) {
    if (parent == null || !parent.has(key)) {
      return fallback;
    }
    try {
      return parent.get(key).getAsLong();
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static double doubleAt(JsonObject parent, String key, double fallback) {
    if (parent == null || !parent.has(key)) {
      return fallback;
    }
    try {
      double value = parent.get(key).getAsDouble();
      return Double.isFinite(value) ? value : fallback;
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int positiveInt(int value) {
    return Math.max(1, value);
  }

  private static int nonNegativeInt(int value) {
    return Math.max(0, value);
  }

  private static long nonNegativeLong(long value) {
    return Math.max(0L, value);
  }

  private static double nonNegativeDouble(double value) {
    return Math.max(0.0D, value);
  }
}
