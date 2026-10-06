package net.modtale.simview.service;

import net.modtale.simview.config.SimViewConfig;
import org.joml.Vector3d;

public final class SimViewStreamingBudget {
  private double x, y, z;
  private boolean initialized;
  private int cooldown;

  public Budget update(Vector3d position, SimViewConfig config, Budget nativeBudget) {
    double dx = position.x() - x, dy = position.y() - y, dz = position.z() - z;
    double threshold = config.speedingNotSendBlocksPerTick();
    if (initialized && threshold > 0 && dx * dx + dy * dy + dz * dz > threshold * threshold) {
      cooldown = Math.max(0, config.speedingCooldownTicks());
    } else if (cooldown > 0) {
      cooldown--;
    }
    x = position.x();
    y = position.y();
    z = position.z();
    initialized = true;
    int normalPerSecond = configuredOrDefault(config.maxSectionSendsPerSecond(), nativeBudget.perSecond());
    int normalPerTick = configuredOrDefault(config.maxSectionSendsPerTick(), nativeBudget.perTick());
    return cooldown > 0
        ? new Budget(configuredOrDefault(config.speedingSectionSendsPerSecond(), normalPerSecond),
            configuredOrDefault(config.speedingSectionSendsPerTick(), normalPerTick))
        : new Budget(normalPerSecond, normalPerTick);
  }

  private static int configuredOrDefault(int configured, int fallback) {
    return configured > 0 ? configured : Math.max(1, fallback);
  }

  public record Budget(int perSecond, int perTick) {}
}
