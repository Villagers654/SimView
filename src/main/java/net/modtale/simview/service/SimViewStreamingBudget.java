package net.modtale.simview.service;

import net.modtale.simview.config.SimViewConfig;
import org.joml.Vector3d;

public final class SimViewStreamingBudget {
  private double x, y, z;
  private boolean initialized;
  private int cooldown;

  public Budget update(Vector3d position, SimViewConfig config) {
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
    return cooldown > 0
        ? new Budget(config.speedingSectionSendsPerSecond(), config.speedingSectionSendsPerTick())
        : new Budget(config.maxSectionSendsPerSecond(), config.maxSectionSendsPerTick());
  }

  public record Budget(int perSecond, int perTick) {}
}
