package net.modtale.simview.config;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class SimViewConfigStore {

  private final Path dataDirectory;
  private final AtomicReference<SimViewConfig> config;

  public SimViewConfigStore(Path dataDirectory) {
    this.dataDirectory = dataDirectory;
    this.config = new AtomicReference<>(SimViewConfig.load(dataDirectory));
  }

  public SimViewConfig current() {
    return config.get();
  }

  public SimViewConfig reload() {
    SimViewConfig loaded = SimViewConfig.load(dataDirectory);
    config.set(loaded);
    return loaded;
  }

  public SimViewConfig saveAndReload(SimViewConfig updated) {
    SimViewConfig.save(dataDirectory, updated);
    return reload();
  }
}
