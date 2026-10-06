package net.modtale.simview.command;

import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.service.SimViewDistanceService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class SimViewReloadCommand extends AbstractPlayerCommand {

  private final SimViewDistanceService distanceService;

  public SimViewReloadCommand(SimViewDistanceService distanceService) {
    super("simviewreload", "Reloads SimView config");
    this.distanceService = distanceService;
    this.setPermissionGroups("hytale:Admin");
  }

  @Override
  protected void execute(
      CommandContext commandContext,
      Store<EntityStore> store,
      Ref<EntityStore> ref,
      PlayerRef playerRef,
      World world) {
    SimViewConfig config;
    try {
      config = distanceService.reload();
    } catch (IllegalStateException exception) {
      commandContext.sendMessage(Message.raw("Unable to reload SimView. The previous configuration remains active.").color("red"));
      return;
    }
    commandContext.sendMessage(
        Message.raw(
                "SimView reloaded: configured-simulation-distance-chunks="
                    + distanceService.hytaleSimulationDistanceChunks()
                    + ", target-view-distance-chunks="
                    + config.targetViewDistanceChunks()
                    + ", target-simulation-distance-chunks="
                    + config.targetSimulationDistanceChunks()
                    + ", active-target-view-distance-chunks="
                    + distanceService.activeTargetViewDistanceChunks()
                    + ", active-target-simulation-distance-chunks="
                    + distanceService.activeTargetSimulationDistanceChunks()
                    + ", adjustment-mode="
                    + config.adjustmentMode().name().toLowerCase()
                    + ", simulation-adjustment-mode="
                    + config.simulationAdjustmentMode().name().toLowerCase()
                    + ", cold view cap="
                    + distanceService.activeViewDistanceCap()
                    + " chunks, active-simulation-distance-chunks="
                    + distanceService.activeSimulationDistanceCap()
                    + " chunks")
            .color("green"));
  }
}
