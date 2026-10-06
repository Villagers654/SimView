package net.modtale.simview.config;

import java.util.Locale;

public enum SimViewStreamingMode {
  NATIVE, DISK;

  public static SimViewStreamingMode parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Streaming mode must be native or disk.", exception);
    }
  }
}
