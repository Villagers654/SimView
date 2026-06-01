package net.modtale.simview.config;

public enum SimViewAdjustmentMode {
  OFF,
  PROACTIVE,
  REACTIVE,
  MIXED;

  public boolean includesProactive() {
    return this == PROACTIVE || this == MIXED;
  }

  public boolean includesReactive() {
    return this == REACTIVE || this == MIXED;
  }

  public static SimViewAdjustmentMode fromProperty(String value, SimViewAdjustmentMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }

    String normalized = value.trim().replace('-', '_').toUpperCase();
    try {
      return SimViewAdjustmentMode.valueOf(normalized);
    } catch (IllegalArgumentException ignored) {
      return fallback;
    }
  }
}
