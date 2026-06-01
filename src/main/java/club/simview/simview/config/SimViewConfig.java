package club.simview.simview.config;

import com.hypixel.hytale.server.core.HytaleServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SimViewConfig(
    boolean enabled,
    boolean guiEnabled,
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

  public static SimViewConfig defaults() {
    int runtimeHytaleViewDistance = Math.max(0, HytaleServer.get().getConfig().getMaxViewRadius());
    return new SimViewConfig(
        true,
        true,
        32,
        runtimeHytaleViewDistance,
        SimViewAdjustmentMode.OFF,
        SimViewAdjustmentMode.OFF,
        0,
        96,
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
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load SimView config from " + configFile, exception);
    }

    try {
      String json = Files.readString(configFile, StandardCharsets.UTF_8);
      return fromJson(json, defaults);
    } catch (IOException ignored) {
      // Fall back to defaults if JSON cannot be read.
    }

    return defaults;
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
    // Ticking distance override is independent from SimView's cold-chunk feature toggle.
    // When configured, this should still drive Hytale's runtime view cap.
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
    String lineSeparator = System.lineSeparator();
    StringBuilder text = new StringBuilder();
    appendObjectStart(text, lineSeparator, 0, "");
    appendObjectStart(text, lineSeparator, 1, "core");
    appendBoolean(text, lineSeparator, 2, "enabled", config.enabled(), true);
    appendBoolean(text, lineSeparator, 2, "gui-enabled", config.guiEnabled(), true);
    appendObjectStart(text, lineSeparator, 2, "target");
    appendNumber(text, lineSeparator, 3, "view-distance-chunks", config.targetViewDistanceChunks(), true);
    appendNumber(text, lineSeparator, 3, "simulation-distance-chunks", config.targetSimulationDistanceChunks(), false);
    appendObjectEnd(text, lineSeparator, 2, true);
    appendObjectStart(text, lineSeparator, 2, "limits");
    appendObjectStart(text, lineSeparator, 3, "minimum");
    appendNumber(text, lineSeparator, 4, "view-distance-chunks", config.minimumTargetViewDistanceChunks(), true);
    appendNumber(text, lineSeparator, 4, "simulation-distance-chunks", config.minimumTargetSimulationDistanceChunks(), false);
    appendObjectEnd(text, lineSeparator, 3, true);
    appendObjectStart(text, lineSeparator, 3, "maximum");
    appendNumber(text, lineSeparator, 4, "view-distance-chunks", config.maximumTargetViewDistanceChunks(), true);
    appendNumber(text, lineSeparator, 4, "simulation-distance-chunks", config.maximumTargetSimulationDistanceChunks(), false);
    appendObjectEnd(text, lineSeparator, 3, false);
    appendObjectEnd(text, lineSeparator, 2, false);
    appendObjectEnd(text, lineSeparator, 1, true);

    appendObjectStart(text, lineSeparator, 1, "auto-adjustment");
    appendObjectStart(text, lineSeparator, 2, "mode");
    appendString(text, lineSeparator, 3, "view", config.adjustmentMode().name().toLowerCase(), true);
    appendString(text, lineSeparator, 3, "simulation", config.simulationAdjustmentMode().name().toLowerCase(), false);
    appendObjectEnd(text, lineSeparator, 2, true);
    appendObjectStart(text, lineSeparator, 2, "cadence");
    appendNumber(text, lineSeparator, 3, "ticks-per-check", config.adjustmentTicksPerCheck(), true);
    appendNumber(text, lineSeparator, 3, "startup-delay-ticks", config.adjustmentStartupDelayTicks(), false);
    appendObjectEnd(text, lineSeparator, 2, true);
    appendObjectStart(text, lineSeparator, 2, "checks");
    appendObjectStart(text, lineSeparator, 3, "view");
    appendNumber(text, lineSeparator, 4, "for-increase", config.adjustmentPassedChecksForIncrease(), true);
    appendNumber(text, lineSeparator, 4, "for-decrease", config.adjustmentPassedChecksForDecrease(), false);
    appendObjectEnd(text, lineSeparator, 3, true);
    appendObjectStart(text, lineSeparator, 3, "simulation");
    appendNumber(text, lineSeparator, 4, "for-increase", config.simulationAdjustmentPassedChecksForIncrease(), true);
    appendNumber(text, lineSeparator, 4, "for-decrease", config.simulationAdjustmentPassedChecksForDecrease(), false);
    appendObjectEnd(text, lineSeparator, 3, false);
    appendObjectEnd(text, lineSeparator, 2, true);
    appendObjectStart(text, lineSeparator, 2, "proactive");
    appendNumber(text, lineSeparator, 3, "global-cold-chunk-count-target", config.proactiveGlobalColdChunkCountTarget(), true);
    appendNumber(text, lineSeparator, 3, "global-ticking-chunk-count-target", config.proactiveGlobalTickingChunkCountTarget(), false);
    appendObjectEnd(text, lineSeparator, 2, true);
    appendObjectStart(text, lineSeparator, 2, "reactive");
    appendNumber(text, lineSeparator, 3, "increase-mspt-threshold", config.reactiveIncreaseMsptThreshold(), true);
    appendNumber(text, lineSeparator, 3, "decrease-mspt-threshold", config.reactiveDecreaseMsptThreshold(), true);
    appendNumber(text, lineSeparator, 3, "mspt-collection-period-ticks", config.reactiveMsptCollectionPeriodTicks(), true);
    appendBoolean(text, lineSeparator, 3, "use-mspt-prediction", config.reactiveUseMsptPrediction(), true);
    appendNumber(text, lineSeparator, 3, "mspt-prediction-history-minutes", config.reactiveMsptPredictionHistoryMinutes(), false);
    appendObjectEnd(text, lineSeparator, 2, false);
    appendObjectEnd(text, lineSeparator, 1, true);

    appendObjectStart(text, lineSeparator, 1, "cold-chunk-streaming");
    appendBoolean(text, lineSeparator, 2, "generate-missing", config.generateMissingColdChunks(), true);
    appendBoolean(text, lineSeparator, 2, "despawn-entities", config.despawnEntitiesInColdChunks(), true);
    appendObjectStart(text, lineSeparator, 2, "budget");
    appendNumber(text, lineSeparator, 3, "chunk-sends-per-second", config.maxChunkSendsPerSecond(), true);
    appendNumber(text, lineSeparator, 3, "chunk-sends-per-tick", config.maxChunkSendsPerTick(), true);
    appendNumber(text, lineSeparator, 3, "cold-chunk-loads-in-flight", config.maxColdChunkLoadsInFlight(), false);
    appendObjectEnd(text, lineSeparator, 2, false);
    appendObjectEnd(text, lineSeparator, 1, true);

    appendObjectStart(text, lineSeparator, 1, "speeding-adjustments");
    appendNumber(text, lineSeparator, 2, "not-send-blocks-per-tick", config.speedingNotSendBlocksPerTick(), true);
    appendNumber(text, lineSeparator, 2, "cooldown-ticks", config.speedingCooldownTicks(), true);
    appendObjectStart(text, lineSeparator, 2, "budget");
    appendNumber(text, lineSeparator, 3, "chunk-sends-per-second", config.speedingChunkSendsPerSecond(), true);
    appendNumber(text, lineSeparator, 3, "chunk-sends-per-tick", config.speedingChunkSendsPerTick(), false);
    appendObjectEnd(text, lineSeparator, 2, false);
    appendObjectEnd(text, lineSeparator, 1, false);

    text.append("}").append(lineSeparator);
    return text.toString();
  }

  private static SimViewConfig fromJson(String json, SimViewConfig defaults) {
    String core = jsonSection(json, "core");
    String coreTarget = jsonSection(core, "target");
    String coreLimits = jsonSection(core, "limits");
    String coreLimitsMinimum = jsonSection(coreLimits, "minimum");
    String coreLimitsMaximum = jsonSection(coreLimits, "maximum");

    String adjustment = jsonSection(json, "auto-adjustment");
    String adjustmentMode = jsonSection(adjustment, "mode");
    String adjustmentCadence = jsonSection(adjustment, "cadence");
    String adjustmentChecks = jsonSection(adjustment, "checks");
    String adjustmentViewChecks = jsonSection(adjustmentChecks, "view");
    String adjustmentSimulationChecks = jsonSection(adjustmentChecks, "simulation");
    String adjustmentProactive = jsonSection(adjustment, "proactive");
    String adjustmentReactive = jsonSection(adjustment, "reactive");

    String streaming = jsonSection(json, "cold-chunk-streaming");
    String streamingBudget = jsonSection(streaming, "budget");

    String speeding = jsonSection(json, "speeding-adjustments");
    String speedingBudget = jsonSection(speeding, "budget");

    boolean enabled = jsonBool(core, "enabled", defaults.enabled());
    boolean guiEnabled = jsonBool(core, "gui-enabled", defaults.guiEnabled());
    int targetViewDistanceChunks =
        nonNegativeInt(jsonInt(coreTarget, "view-distance-chunks", defaults.targetViewDistanceChunks()));
    int targetSimulationDistanceChunks =
        jsonInt(coreTarget, "simulation-distance-chunks", defaults.targetSimulationDistanceChunks());

    SimViewAdjustmentMode viewAdjustmentMode =
        SimViewAdjustmentMode.fromProperty(
            jsonString(adjustmentMode, "view", defaults.adjustmentMode().name().toLowerCase()),
            defaults.adjustmentMode());

    SimViewAdjustmentMode simulationAdjustmentMode =
        SimViewAdjustmentMode.fromProperty(
            jsonString(adjustmentMode, "simulation", defaults.simulationAdjustmentMode().name().toLowerCase()),
            defaults.simulationAdjustmentMode());

    int adjustmentTicksPerCheck =
        positiveInt(jsonInt(adjustmentCadence, "ticks-per-check", defaults.adjustmentTicksPerCheck()));
    int adjustmentStartupDelayTicks =
        nonNegativeInt(jsonInt(adjustmentCadence, "startup-delay-ticks", defaults.adjustmentStartupDelayTicks()));
    int adjustmentPassedChecksForIncrease =
        positiveInt(jsonInt(adjustmentViewChecks, "for-increase", defaults.adjustmentPassedChecksForIncrease()));
    int adjustmentPassedChecksForDecrease =
        positiveInt(jsonInt(adjustmentViewChecks, "for-decrease", defaults.adjustmentPassedChecksForDecrease()));

    int simulationAdjustmentPassedChecksForIncrease =
        positiveInt(jsonInt(adjustmentSimulationChecks, "for-increase", defaults.simulationAdjustmentPassedChecksForIncrease()));

    int simulationAdjustmentPassedChecksForDecrease =
        positiveInt(jsonInt(adjustmentSimulationChecks, "for-decrease", defaults.simulationAdjustmentPassedChecksForDecrease()));

    return new SimViewConfig(
        enabled,
        guiEnabled,
        targetViewDistanceChunks,
        targetSimulationDistanceChunks,
        viewAdjustmentMode,
        simulationAdjustmentMode,
        nonNegativeInt(
            jsonInt(
                coreLimitsMinimum,
                "view-distance-chunks",
                defaults.minimumTargetViewDistanceChunks())),
        nonNegativeInt(
            jsonInt(
                coreLimitsMaximum,
                "view-distance-chunks",
                defaults.maximumTargetViewDistanceChunks())),
        nonNegativeInt(
            jsonInt(
                coreLimitsMinimum,
                "simulation-distance-chunks",
                defaults.minimumTargetSimulationDistanceChunks())),
        nonNegativeInt(
            jsonInt(
                coreLimitsMaximum,
                "simulation-distance-chunks",
                defaults.maximumTargetSimulationDistanceChunks())),
        adjustmentTicksPerCheck,
        adjustmentStartupDelayTicks,
        adjustmentPassedChecksForIncrease,
        adjustmentPassedChecksForDecrease,
        simulationAdjustmentPassedChecksForIncrease,
        simulationAdjustmentPassedChecksForDecrease,
        nonNegativeLong(
            jsonLong(
                adjustmentProactive,
                "global-cold-chunk-count-target",
                defaults.proactiveGlobalColdChunkCountTarget())),
        nonNegativeLong(
            jsonLong(
                adjustmentProactive,
                "global-ticking-chunk-count-target",
                defaults.proactiveGlobalTickingChunkCountTarget())),
        nonNegativeDouble(
            jsonDouble(
                adjustmentReactive,
                "increase-mspt-threshold",
                defaults.reactiveIncreaseMsptThreshold())),
        nonNegativeDouble(
            jsonDouble(
                adjustmentReactive,
                "decrease-mspt-threshold",
                defaults.reactiveDecreaseMsptThreshold())),
        positiveInt(
            jsonInt(
                adjustmentReactive,
                "mspt-collection-period-ticks",
                defaults.reactiveMsptCollectionPeriodTicks())),
        jsonBool(adjustmentReactive, "use-mspt-prediction", defaults.reactiveUseMsptPrediction()),
        positiveInt(
            jsonInt(
                adjustmentReactive,
                "mspt-prediction-history-minutes",
                defaults.reactiveMsptPredictionHistoryMinutes())),
        jsonBool(streaming, "generate-missing", defaults.generateMissingColdChunks()),
        positiveInt(jsonInt(streamingBudget, "chunk-sends-per-second", defaults.maxChunkSendsPerSecond())),
        positiveInt(jsonInt(streamingBudget, "chunk-sends-per-tick", defaults.maxChunkSendsPerTick())),
        positiveInt(
            jsonInt(streamingBudget, "cold-chunk-loads-in-flight", defaults.maxColdChunkLoadsInFlight())),
        jsonBool(streaming, "despawn-entities", defaults.despawnEntitiesInColdChunks()),
        nonNegativeDouble(
            jsonDouble(
                speeding,
                "not-send-blocks-per-tick",
                defaults.speedingNotSendBlocksPerTick())),
        positiveInt(jsonInt(speedingBudget, "chunk-sends-per-second", defaults.speedingChunkSendsPerSecond())),
        positiveInt(jsonInt(speedingBudget, "chunk-sends-per-tick", defaults.speedingChunkSendsPerTick())),
        nonNegativeInt(jsonInt(speeding, "cooldown-ticks", defaults.speedingCooldownTicks())));
  }

  private static void appendObjectStart(StringBuilder text, String lineSeparator, int indent, String key) {
    if (key.isEmpty()) {
      text.append("{").append(lineSeparator);
      return;
    }
    text.append("  ".repeat(Math.max(0, indent))).append("\"").append(key).append("\": {").append(lineSeparator);
  }

  private static void appendObjectEnd(StringBuilder text, String lineSeparator, int indent, boolean withComma) {
    text.append("  ".repeat(Math.max(0, indent))).append("}");
    if (withComma) {
      text.append(",");
    }
    text.append(lineSeparator);
  }

  private static void appendString(
      StringBuilder text, String lineSeparator, int indent, String key, String value, boolean withComma) {
    appendPrimitive(text, lineSeparator, indent, key, "\"" + value + "\"", withComma);
  }

  private static void appendNumber(
      StringBuilder text, String lineSeparator, int indent, String key, Number value, boolean withComma) {
    appendPrimitive(text, lineSeparator, indent, key, value.toString(), withComma);
  }

  private static void appendBoolean(
      StringBuilder text, String lineSeparator, int indent, String key, boolean value, boolean withComma) {
    appendPrimitive(text, lineSeparator, indent, key, Boolean.toString(value), withComma);
  }

  private static void appendPrimitive(
      StringBuilder text, String lineSeparator, int indent, String key, String renderedValue, boolean withComma) {
    text.append("  ".repeat(Math.max(0, indent)))
        .append("\"")
        .append(key)
        .append("\": ")
        .append(renderedValue);
    if (withComma) {
      text.append(",");
    }
    text.append(lineSeparator);
  }

  private static String jsonSection(String json, String sectionName) {
    String patternText = "\"" + Pattern.quote(sectionName) + "\"\\s*:\\s*\\{";
    Pattern pattern = Pattern.compile(patternText);
    Matcher matcher = pattern.matcher(json);
    if (!matcher.find()) {
      return "";
    }
    int open = matcher.end() - 1;
    int close = findMatchingBrace(json, open);
    if (close <= open) {
      return "";
    }
    return json.substring(open + 1, close);
  }

  private static int findMatchingBrace(String text, int openBraceIndex) {
    int depth = 0;
    for (int i = openBraceIndex; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static String jsonString(String section, String key, String fallback) {
    String value = jsonMatch(section, key, "\"((?:[^\"\\\\]|\\\\.)*)\"");
    return value == null ? fallback : value.trim();
  }

  private static boolean jsonBool(String section, String key, boolean fallback) {
    String value = jsonMatch(section, key, "(true|false)");
    return value == null ? fallback : Boolean.parseBoolean(value.trim());
  }

  private static int jsonInt(String section, String key, int fallback) {
    String value = jsonMatch(section, key, "(-?\\d+)");
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long jsonLong(String section, String key, long fallback) {
    String value = jsonMatch(section, key, "(-?\\d+)");
    if (value == null) {
      return fallback;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double jsonDouble(String section, String key, double fallback) {
    String value = jsonMatch(section, key, "(-?\\d+(?:\\.\\d+)?)");
    if (value == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String jsonMatch(String text, String key, String valuePattern) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + valuePattern);
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
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
