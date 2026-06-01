package club.simview.simview.command;

import club.simview.simview.permission.SimViewAccessControl;
import club.simview.simview.service.SimViewDistanceService;
import club.simview.simview.ui.SimViewConfigPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class SimViewGuiCommand extends AbstractPlayerCommand {

  private final SimViewDistanceService distanceService;

  public SimViewGuiCommand(SimViewDistanceService distanceService) {
    super("simview", "Opens the SimView configuration GUI");
    this.distanceService = distanceService;
    this.setPermissionGroups("hytale:None");
  }

  @Override
  protected void execute(
      CommandContext commandContext,
      Store<EntityStore> store,
      Ref<EntityStore> ref,
      PlayerRef playerRef,
      World world) {
    if (!distanceService.current().guiEnabled()) {
      commandContext.sendMessage(
          Message.raw("SimView GUI is disabled in config (`core.gui-enabled`).").color("red"));
      return;
    }

    if (!SimViewAccessControl.canUseGui(playerRef)) {
      commandContext.sendMessage(
          Message.raw(
                  "You need admin permissions to use /simview on multiplayer servers.")
              .color("red"));
      return;
    }

    Player player = store.getComponent(ref, Player.getComponentType());
    if (player == null) {
      commandContext.sendMessage(
          Message.raw("Unable to open SimView GUI right now.").color("red"));
      return;
    }

    player
        .getPageManager()
        .openCustomPage(ref, store, new SimViewConfigPage(playerRef, distanceService));
  }
}
