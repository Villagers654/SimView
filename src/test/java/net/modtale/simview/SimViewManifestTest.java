package net.modtale.simview;

import static org.junit.jupiter.api.Assertions.*;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.plugin.PluginManifest.ServerVersionCheck;
import java.nio.charset.StandardCharsets;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class SimViewManifestTest {
  @Test void actualLoaderCodecAcceptsOnlyVerifiedApiVersion() throws Exception {
    try (var stream = getClass().getResourceAsStream("/manifest.json")) {
      assertNotNull(stream);
      String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      PluginManifest manifest = PluginManifest.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
      assertEquals(ServerVersionCheck.COMPATIBLE,
          PluginManifest.checkServerVersionCompatibility(manifest.getServerVersion(), "0.7.0-pre.5.1"));
      for (String unsupported : new String[] {"0.5.3", "0.6.8", "0.7.0-pre.5", "0.7.0-pre.5.2", "0.7.0"}) {
        assertEquals(ServerVersionCheck.INCOMPATIBLE,
            PluginManifest.checkServerVersionCompatibility(manifest.getServerVersion(), unsupported), unsupported);
      }
    }
  }
}
