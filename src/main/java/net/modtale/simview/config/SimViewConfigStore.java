package net.modtale.simview.config;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class SimViewConfigStore {

  private final Path dataDirectory;
  private final AtomicReference<SimViewConfig> config;
  private final SimViewConfig defaults;

  public SimViewConfigStore(Path dataDirectory) {
    this(dataDirectory, SimViewConfig.defaults());
  }

  SimViewConfigStore(Path dataDirectory, SimViewConfig defaults) {
    this.dataDirectory = dataDirectory;
    this.defaults = defaults;
    this.config = new AtomicReference<>(SimViewConfig.load(dataDirectory, defaults));
  }

  public SimViewConfig current() {
    return config.get();
  }

  public synchronized SimViewConfig reload() {
    SimViewConfig loaded = SimViewConfig.load(dataDirectory, defaults);
    config.set(loaded);
    return loaded;
  }

  public synchronized SimViewConfig saveAndReload(SimViewConfig updated) {
    SimViewConfig.save(dataDirectory, updated);
    return reload();
  }
}
