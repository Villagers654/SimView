package net.modtale.simview.config;

public final class TestConfigs {
  private TestConfigs() {}
  public static SimViewConfig config(String json) {
    return SimViewConfig.fromJson(json, SimViewConfig.defaults(8));
  }
}
