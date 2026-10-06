package net.modtale.simview.command;

import net.modtale.simview.config.SimViewDistances;
import net.modtale.simview.service.SimViewDistanceService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class SimViewStatusCommand extends AbstractPlayerCommand {
  private final SimViewDistanceService distanceService;

  public SimViewStatusCommand(SimViewDistanceService distanceService) {
    super("simviewstatus", "Shows current streaming limits and world loading counters");
    this.distanceService = distanceService;
    setPermissionGroups("hytale:Admin");
  }

  @Override
  protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
      PlayerRef playerRef, World world) {
    ChunkTracker tracker = store.getComponent(ref, ChunkTracker.getComponentType());
    if (tracker == null) {
      context.sendMessage(Message.raw("SimView status: player section tracker is not available.").color("red"));
      return;
    }
    var channel = playerRef.getPacketHandler().getChannel(StreamType.Game);
    var config = distanceService.current();
    var chunks = world.getChunkStore();
    context.sendMessage(Message.raw("SimView status: enabled=" + config.enabled()
        + ", tracker-min-loaded=" + SimViewDistances.sectionsToBlocks(tracker.getMinLoadedRadius()) + " blocks"
        + ", tracker-max-hot=" + SimViewDistances.sectionsToBlocks(tracker.getMaxHotLoadedRadius()) + " blocks"
        + ", live-budget=" + tracker.getMaxSectionsPerSecond() + "/second, "
        + tracker.getMaxSectionsPerTick() + "/tick"
        + ", configured-budget=" + config.maxSectionSendsPerSecond() + "/"
        + config.maxSectionSendsPerTick() + ", speeding-budget=" + config.speedingSectionSendsPerSecond()
        + "/" + config.speedingSectionSendsPerTick() + " (0=inherit)"
        + ", ready=" + tracker.isReadyForChunks() + ", writable=" + (channel != null && channel.isWritable())
        + ", player-sections-loaded=" + tracker.getLoadedSectionsCount()
        + ", player-sections-loading=" + tracker.getLoadingSectionsCount()
        + ", cubic-sections=" + chunks.supportsCubicSections()
        + ", world-generated-counter=" + chunks.getTotalGeneratedChunksCount()
        + ", world-storage-loaded-counter=" + chunks.getTotalLoadedChunksCount()));
  }
}
