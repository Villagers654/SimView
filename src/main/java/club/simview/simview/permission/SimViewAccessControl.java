package club.simview.simview.permission;

import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class SimViewAccessControl {

  private SimViewAccessControl() {}

  public static boolean canUseGui(PlayerRef playerRef) {
    return isSingleplayerOwner(playerRef) || isAdmin(playerRef);
  }

  public static boolean isSingleplayerOwner(PlayerRef playerRef) {
    return Constants.SINGLEPLAYER && SingleplayerModule.isOwner(playerRef);
  }

  public static boolean isAdmin(PlayerRef playerRef) {
    return playerRef.hasPermission("*")
        || playerRef.hasPermission("hytale:Admin")
        || playerRef.hasPermission("hytale:admin");
  }
}
