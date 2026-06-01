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

public record SimViewConfig(
    boolean enabled,
    boolean guiEnabled,
    boolean disableJoinHintMessage,
    int targetViewDistanceChunks,
    int targetSimulationDistanceChunks,
    SimViewAdjustmentMode adjustmentMode,
    SimViewAdjustmentMode simulationAdjustmentMode,
    int minimumTargetViewDistanceChunks,
    int maximumTargetViewDistanceChunks,
    int minimumTargetSimulationDistanceChunks,
    int maximumTargetSimulationDistanceChunks,
    int adjustmentTicksPerCheck,
    int adjustmentStartupDelayTicks,
    int adjustmentPassedChecksForIncrease,
    int adjustmentPassedChecksForDecrease,
    int simulationAdjustmentPassedChecksForIncrease,
    int simulationAdjustmentPassedChecksForDecrease,
    long proactiveGlobalColdChunkCountTarget,
    long proactiveGlobalTickingChunkCountTarget,
    double reactiveIncreaseMsptThreshold,
    double reactiveDecreaseMsptThreshold,
    int reactiveMsptCollectionPeriodTicks,
    boolean reactiveUseMsptPrediction,
    int reactiveMsptPredictionHistoryMinutes,
    boolean generateMissingColdChunks,
    boolean cacheColdChunkPacketsInMemory,
    int maxChunkSendsPerSecond,
    int maxChunkSendsPerTick,
    int maxColdChunkLoadsInFlight,
    boolean despawnEntitiesInColdChunks,
    double speedingNotSendBlocksPerTick,
    int speedingChunkSendsPerSecond,
    int speedingChunkSendsPerTick,
    int speedingCooldownTicks) {

  public static final int CHUNK_SIZE_BLOCKS = 32;
  private static final String CONFIG_FILE = "simview.json";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public static SimViewConfig defaults() {
    int runtimeHytaleViewDistance = Math.max(0, HytaleServer.get().getConfig().getMaxViewRadius());
    return new SimViewConfig(
        true,
        true,
        false,
        32,
        runtimeHytaleViewDistance,
        SimViewAdjustmentMode.OFF,
        SimViewAdjustmentMode.OFF,
        0,
        32,
        0,
        runtimeHytaleViewDistance,
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
        true,
        true,
        96,
        8,
        64,
        true,
        1.2D,
        24,
        2,
        40);
  }

  public static SimViewConfig load(Path dataDirectory) {
    SimViewConfig defaults = defaults();
    Path configDirectory = dataDirectory.resolve("config");
    Path configFile = configDirectory.resolve(CONFIG_FILE);

    try {
      Files.createDirectories(configDirectory);
      if (Files.notExists(configFile)) {
        writeDefaults(configFile, defaults);
      }

      String json = Files.readString(configFile, StandardCharsets.UTF_8);
      return fromJson(json, defaults);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load SimView config from " + configFile, exception);
    }
  }

  public static void save(Path dataDirectory, SimViewConfig config) {
    Path configDirectory = dataDirectory.resolve("config");
    Path configFile = configDirectory.resolve(CONFIG_FILE);
    try {
      Files.createDirectories(configDirectory);
      Files.writeString(configFile, renderJson(config), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to save SimView config to " + configFile, exception);
    }
  }

  public int clampedTargetSimulationDistanceChunks(
      int configuredHytaleViewDistanceChunks, int targetSimulationDistanceChunks) {
    int configuredHytale = Math.max(0, configuredHytaleViewDistanceChunks);
    int requestedTarget =
        targetSimulationDistanceChunks < 0 ? configuredHytale : Math.max(0, targetSimulationDistanceChunks);
    int minimum = Math.max(0, minimumTargetSimulationDistanceChunks);
    int maximum = Math.max(minimum, maximumTargetSimulationDistanceChunks);
    return Math.max(minimum, Math.min(requestedTarget, maximum));
  }

  public int clampedTargetViewDistanceChunks(int simulationDistanceChunks, int targetViewDistanceChunks) {
    int minimum = Math.max(0, Math.max(simulationDistanceChunks, minimumTargetViewDistanceChunks));
    int maximum = Math.max(minimum, maximumTargetViewDistanceChunks);
    return Math.max(minimum, Math.min(targetViewDistanceChunks, maximum));
  }

  public int simulationDistanceCap(int configuredHytaleViewDistanceChunks) {
    return simulationDistanceCap(configuredHytaleViewDistanceChunks, targetSimulationDistanceChunks);
  }

  public int simulationDistanceCap(int configuredHytaleViewDistanceChunks, int activeTargetSimulationDistanceChunks) {
    int configuredHytale = Math.max(0, configuredHytaleViewDistanceChunks);
    return clampedTargetSimulationDistanceChunks(configuredHytale, activeTargetSimulationDistanceChunks);
  }

  public int extendedViewDistanceCap(int simulationDistanceChunks) {
    return extendedViewDistanceCap(simulationDistanceChunks, targetViewDistanceChunks);
  }

  public int extendedViewDistanceCap(int simulationDistanceChunks, int activeTargetViewDistanceChunks) {
    int simulationDistance = Math.max(0, simulationDistanceChunks);
    if (!enabled) {
      return simulationDistance;
    }
    int clampedTarget = clampedTargetViewDistanceChunks(simulationDistance, activeTargetViewDistanceChunks);
    return Math.max(simulationDistance, clampedTarget);
  }

  public int effectiveViewDistance(int requestedClientViewRadius, int serverLimitedViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    int serverLimited = Math.max(0, serverLimitedViewRadius);
    return Math.min(requested, serverLimited);
  }

  public int effectiveExtendedViewDistance(int simulationDistanceChunks, int requestedClientViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(requested, extendedViewDistanceCap(simulationDistanceChunks));
  }

  public int effectiveExtendedViewDistance(
      int simulationDistanceChunks, int requestedClientViewRadius, int activeTargetViewDistanceChunks) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(requested, extendedViewDistanceCap(simulationDistanceChunks, activeTargetViewDistanceChunks));
  }

  public int effectiveSimulationDistance(int simulationDistanceChunks, int requestedClientViewRadius) {
    int requested = Math.max(0, requestedClientViewRadius);
    return Math.min(Math.max(0, simulationDistanceChunks), requested);
  }

  public int effectiveSimulationDistance(
      int simulationDistanceChunks, int requestedClientViewRadius, int serverLimitedViewRadius) {
    int viewDistance = effectiveViewDistance(requestedClientViewRadius, serverLimitedViewRadius);
    return Math.min(Math.max(0, simulationDistanceChunks), viewDistance);
  }

  private static void writeDefaults(Path configFile, SimViewConfig defaults) throws IOException {
    Files.writeString(configFile, renderJson(defaults), StandardCharsets.UTF_8);
  }

  private static String renderJson(SimViewConfig config) {
    JsonObject root = new JsonObject();

    JsonObject core = new JsonObject();
    core.addProperty("enabled", config.enabled());
    core.addProperty("gui-enabled", config.guiEnabled());
    core.addProperty("disable-join-hint-message", config.disableJoinHintMessage());

    JsonObject coreTarget = new JsonObject();
    coreTarget.addProperty("view-distance-chunks", config.targetViewDistanceChunks());
    coreTarget.addProperty("simulation-distance-chunks", config.targetSimulationDistanceChunks());
    core.add("target", coreTarget);

    JsonObject coreLimits = new JsonObject();
    JsonObject coreLimitsMinimum = new JsonObject();
    coreLimitsMinimum.addProperty("view-distance-chunks", config.minimumTargetViewDistanceChunks());
    coreLimitsMinimum.addProperty("simulation-distance-chunks", config.minimumTargetSimulationDistanceChunks());
    coreLimits.add("minimum", coreLimitsMinimum);

    JsonObject coreLimitsMaximum = new JsonObject();
    coreLimitsMaximum.addProperty("view-distance-chunks", config.maximumTargetViewDistanceChunks());
    coreLimitsMaximum.addProperty("simulation-distance-chunks", config.maximumTargetSimulationDistanceChunks());
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
    adjustmentProactive.addProperty("global-cold-chunk-count-target", config.proactiveGlobalColdChunkCountTarget());
    adjustmentProactive.addProperty("global-ticking-chunk-count-target", config.proactiveGlobalTickingChunkCountTarget());
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
    streaming.addProperty("generate-missing", config.generateMissingColdChunks());
    streaming.addProperty("cache-packets-in-memory", config.cacheColdChunkPacketsInMemory());
    streaming.addProperty("despawn-entities", config.despawnEntitiesInColdChunks());

    JsonObject streamingBudget = new JsonObject();
    streamingBudget.addProperty("chunk-sends-per-second", config.maxChunkSendsPerSecond());
    streamingBudget.addProperty("chunk-sends-per-tick", config.maxChunkSendsPerTick());
    streamingBudget.addProperty("cold-chunk-loads-in-flight", config.maxColdChunkLoadsInFlight());
    streaming.add("budget", streamingBudget);
    root.add("cold-chunk-streaming", streaming);

    JsonObject speeding = new JsonObject();
    speeding.addProperty("not-send-blocks-per-tick", config.speedingNotSendBlocksPerTick());
    speeding.addProperty("cooldown-ticks", config.speedingCooldownTicks());

    JsonObject speedingBudget = new JsonObject();
    speedingBudget.addProperty("chunk-sends-per-second", config.speedingChunkSendsPerSecond());
    speedingBudget.addProperty("chunk-sends-per-tick", config.speedingChunkSendsPerTick());
    speeding.add("budget", speedingBudget);
    root.add("speeding-adjustments", speeding);

    return GSON.toJson(root);
  }

  private static SimViewConfig fromJson(String json, SimViewConfig defaults) {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();

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

    JsonObject streaming = objectAt(root, "cold-chunk-streaming");
    JsonObject streamingBudget = objectAt(streaming, "budget");

    JsonObject speeding = objectAt(root, "speeding-adjustments");
    JsonObject speedingBudget = objectAt(speeding, "budget");

    boolean enabled = boolAt(core, "enabled", defaults.enabled());
    boolean guiEnabled = boolAt(core, "gui-enabled", defaults.guiEnabled());
    boolean disableJoinHintMessage = boolAt(core, "disable-join-hint-message", defaults.disableJoinHintMessage());
    int targetViewDistanceChunks = nonNegativeInt(intAt(coreTarget, "view-distance-chunks", defaults.targetViewDistanceChunks()));
    int targetSimulationDistanceChunks = intAt(coreTarget, "simulation-distance-chunks", defaults.targetSimulationDistanceChunks());

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
        targetViewDistanceChunks,
        targetSimulationDistanceChunks,
        viewAdjustmentMode,
        simulationAdjustmentMode,
        nonNegativeInt(intAt(coreLimitsMinimum, "view-distance-chunks", defaults.minimumTargetViewDistanceChunks())),
        nonNegativeInt(intAt(coreLimitsMaximum, "view-distance-chunks", defaults.maximumTargetViewDistanceChunks())),
        nonNegativeInt(intAt(coreLimitsMinimum, "simulation-distance-chunks", defaults.minimumTargetSimulationDistanceChunks())),
        nonNegativeInt(intAt(coreLimitsMaximum, "simulation-distance-chunks", defaults.maximumTargetSimulationDistanceChunks())),
        adjustmentTicksPerCheck,
        adjustmentStartupDelayTicks,
        adjustmentPassedChecksForIncrease,
        adjustmentPassedChecksForDecrease,
        simulationAdjustmentPassedChecksForIncrease,
        simulationAdjustmentPassedChecksForDecrease,
        nonNegativeLong(longAt(adjustmentProactive, "global-cold-chunk-count-target", defaults.proactiveGlobalColdChunkCountTarget())),
        nonNegativeLong(longAt(adjustmentProactive, "global-ticking-chunk-count-target", defaults.proactiveGlobalTickingChunkCountTarget())),
        nonNegativeDouble(doubleAt(adjustmentReactive, "increase-mspt-threshold", defaults.reactiveIncreaseMsptThreshold())),
        nonNegativeDouble(doubleAt(adjustmentReactive, "decrease-mspt-threshold", defaults.reactiveDecreaseMsptThreshold())),
        positiveInt(intAt(adjustmentReactive, "mspt-collection-period-ticks", defaults.reactiveMsptCollectionPeriodTicks())),
        boolAt(adjustmentReactive, "use-mspt-prediction", defaults.reactiveUseMsptPrediction()),
        positiveInt(intAt(adjustmentReactive, "mspt-prediction-history-minutes", defaults.reactiveMsptPredictionHistoryMinutes())),
        boolAt(streaming, "generate-missing", defaults.generateMissingColdChunks()),
        boolAt(streaming, "cache-packets-in-memory", defaults.cacheColdChunkPacketsInMemory()),
        positiveInt(intAt(streamingBudget, "chunk-sends-per-second", defaults.maxChunkSendsPerSecond())),
        positiveInt(intAt(streamingBudget, "chunk-sends-per-tick", defaults.maxChunkSendsPerTick())),
        positiveInt(intAt(streamingBudget, "cold-chunk-loads-in-flight", defaults.maxColdChunkLoadsInFlight())),
        boolAt(streaming, "despawn-entities", defaults.despawnEntitiesInColdChunks()),
        nonNegativeDouble(doubleAt(speeding, "not-send-blocks-per-tick", defaults.speedingNotSendBlocksPerTick())),
        positiveInt(intAt(speedingBudget, "chunk-sends-per-second", defaults.speedingChunkSendsPerSecond())),
        positiveInt(intAt(speedingBudget, "chunk-sends-per-tick", defaults.speedingChunkSendsPerTick())),
        nonNegativeInt(intAt(speeding, "cooldown-ticks", defaults.speedingCooldownTicks())));
  }

  private static JsonObject objectAt(JsonObject parent, String key) {
    if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
      return new JsonObject();
    }
    return parent.getAsJsonObject(key);
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
      return parent.get(key).getAsInt();
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
      return parent.get(key).getAsDouble();
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
