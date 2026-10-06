package net.modtale.simview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.protocol.packets.world.UnloadChunks;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

class SimViewDiskStreamerTest {
  static org.mockito.MockedStatic<com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect> effects;
  @BeforeAll static void assetFixture() throws Exception {
    var type = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.class;
    effects = mockStatic(type);
    Object assetMap = mock(type.getMethod("getAssetMap").getReturnType());
    effects.when(() -> type.getMethod("getAssetMap").invoke(null)).thenReturn(assetMap);
  }
  @AfterAll static void closeFixture() { effects.close(); }
  record Request(int x, int z, int[] ys, BooleanSupplier cancelled,
      CompletableFuture<List<ToClientPacket>> result) {}
  static final class Client {
    final World world = mock(World.class);
    final PlayerRef player = mock(PlayerRef.class);
    final PacketHandler packets = mock(PacketHandler.class);
    final ChunkTracker tracker = mock(ChunkTracker.class);
    final ChannelConnection channel = mock(ChannelConnection.class);
    Client() {
      var config = mock(WorldConfig.class);
      UUID worldId = UUID.randomUUID();
      when(config.getUuid()).thenReturn(worldId);
      when(world.getWorldConfig()).thenReturn(config);
      when(player.getWorldUuid()).thenReturn(worldId);
      when(player.getUuid()).thenReturn(UUID.randomUUID());
      when(player.isValid()).thenReturn(true);
      when(player.getPacketHandler()).thenReturn(packets);
      when(packets.getChannel(StreamType.Game)).thenReturn(channel);
      when(channel.isWritable()).thenReturn(true);
      when(tracker.isReadyForChunks()).thenReturn(true);
    }
    void tick(SimViewDiskStreamer streamer) { tick(streamer, new Vector3d(), 32, 64, 128); }
    void tick(SimViewDiskStreamer streamer, Vector3d position, int inner, int outer, int budget) {
      streamer.tick(world, player, tracker, position, 0.05f, inner, outer,
          new SimViewStreamingBudget.Budget(10000, budget));
    }
  }
  static SimViewDiskStreamer streamer(List<Request> requests) {
    return new SimViewDiskStreamer((world, x, z, ys, executor, cancelled) -> {
      var future = new CompletableFuture<List<ToClientPacket>>();
      requests.add(new Request(x, z, ys, cancelled, future));
      return future;
    });
  }
  static SetChunk section(Request request, int y) {
    return new SetChunk(request.x(), y, request.z(), null, null, new byte[] {0});
  }

  @Test void fillsVerticalColdSectionsAndExcludesHotSphere() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    try (var streamer = streamer(requests)) {
      client.tick(streamer);
      Request request = requests.getFirst();
      assertEquals(0, request.x()); assertEquals(0, request.z());
      assertArrayEquals(new int[] {-2, 2}, request.ys());
      request.result().complete(Arrays.stream(request.ys())
          .mapToObj(y -> (ToClientPacket) section(request, y)).toList());
      client.tick(streamer);
      verify(client.packets, times(2)).writeNoCache(any(SetChunk.class));
      assertEquals(1, streamer.sentColumns(client.player.getUuid()));
    }
  }

  @Test void pendingReadsAreCancelledAndNeverWriteAfterModeExitTransferOrClose() {
    for (int operation = 0; operation < 3; operation++) {
      var requests = new ArrayList<Request>();
      var client = new Client();
      var streamer = streamer(requests);
      client.tick(streamer);
      var request = requests.getFirst();
      if (operation == 0) { streamer.leave(client.player); }
      else if (operation == 1) { streamer.discard(client.player.getUuid()); }
      else { streamer.close(); }
      assertTrue(request.cancelled().getAsBoolean());
      assertEquals(1, streamer.inFlight()); // Cancellation does not admit more concurrent underlying reads.
      request.result().complete(List.of(section(request, request.ys()[0])));
      assertEquals(0, streamer.inFlight());
      verify(client.packets, never()).writeNoCache(any());
      streamer.close();
    }
  }

  @Test void admissionIsBoundedAndFifthPlayerProgressesWhenEarlierJobsFinish() {
    var requests = new ArrayList<Request>();
    var clients = java.util.stream.IntStream.range(0, 5).mapToObj(i -> new Client()).toList();
    try (var streamer = streamer(requests)) {
      for (Client client : clients) { client.tick(streamer); }
      assertEquals(4, requests.size()); assertEquals(4, streamer.inFlight());
      requests.getFirst().result().complete(List.of());
      for (Client client : clients) { client.tick(streamer); }
      assertEquals(5, requests.size());
      assertEquals(4, streamer.inFlight());
      assertEquals(0, streamer.sentColumns(clients.getLast().player.getUuid()));
    }
  }

  @Test void backpressureStopsNewReadsAndDrainingAndSectionBudgetLimitsSends() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    try (var streamer = streamer(requests)) {
      when(client.channel.isWritable()).thenReturn(false);
      client.tick(streamer);
      assertTrue(requests.isEmpty());
      when(client.channel.isWritable()).thenReturn(true);
      client.tick(streamer);
      var request = requests.getFirst();
      request.result().complete(List.of(section(request, -2), section(request, 2)));
      when(client.channel.isWritable()).thenReturn(false);
      client.tick(streamer);
      verify(client.packets, never()).writeNoCache(any());
      when(client.channel.isWritable()).thenReturn(true);
      client.tick(streamer, new Vector3d(), 32, 64, 1);
      verify(client.packets, times(1)).writeNoCache(any(SetChunk.class));
      client.tick(streamer, new Vector3d(), 32, 64, 1);
      verify(client.packets, times(2)).writeNoCache(any(SetChunk.class));
    }
  }

  @Test void promotionUnloadsOnlyOwnedSectionsAndRequestsNativeResend() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    try (var streamer = streamer(requests)) {
      client.tick(streamer);
      var request = requests.getFirst();
      request.result().complete(List.of(section(request, 2)));
      client.tick(streamer);
      when(client.tracker.isLoaded(anyLong())).thenReturn(true);
      doAnswer(invocation -> {
        var consumer = (com.hypixel.hytale.function.consumer.TriIntConsumer) invocation.getArgument(0);
        consumer.accept(0, 2, 0);
        return null;
      }).when(client.tracker).forEachLoadedSection(any());
      client.tick(streamer, new Vector3d(), 64, 96, 128);
      var packets = org.mockito.ArgumentCaptor.forClass(ToClientPacket.class);
      verify(client.packets, atLeastOnce()).writeNoCache(packets.capture());
      var unload = packets.getAllValues().stream().filter(UnloadChunks.class::isInstance)
          .map(UnloadChunks.class::cast).findFirst().orElseThrow();
      assertArrayEquals(new int[] {0, 2, 0}, unload.sections);
      assertNull(unload.columns);
      verify(client.tracker).removeForReload(0, 2, 0);
    }
  }

  @Test void nativeColumnMetadataIsPreservedWhenDiskSuppliesVerticalSections() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    when(client.tracker.isLoaded(anyLong())).thenReturn(true);
    try (var streamer = streamer(requests)) {
      client.tick(streamer);
      var request = requests.getFirst();
      request.result().complete(List.of(new SetColumn(), section(request, 2)));
      client.tick(streamer);
      verify(client.packets, never()).writeNoCache(any(SetColumn.class));
      streamer.leave(client.player);
      var packets = org.mockito.ArgumentCaptor.forClass(ToClientPacket.class);
      verify(client.packets, atLeastOnce()).writeNoCache(packets.capture());
      for (ToClientPacket packet : packets.getAllValues()) {
        if (packet instanceof UnloadChunks unload) { assertNull(unload.columns); }
      }
    }
  }

  @Test void nativeColumnResetUnloadsOwnedSectionsAndReadsThemAgain() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    when(client.tracker.isLoaded(anyLong())).thenReturn(true);
    try (var streamer = streamer(requests)) {
      client.tick(streamer);
      var first = requests.getFirst();
      first.result().complete(List.of(section(first, 2)));
      client.tick(streamer);
      when(client.tracker.isLoaded(anyLong())).thenReturn(false);
      client.tick(streamer);
      var packets = org.mockito.ArgumentCaptor.forClass(ToClientPacket.class);
      verify(client.packets, atLeastOnce()).writeNoCache(packets.capture());
      assertTrue(packets.getAllValues().stream().filter(UnloadChunks.class::isInstance)
          .map(UnloadChunks.class::cast).anyMatch(packet -> Arrays.equals(new int[] {0, 2, 0}, packet.sections)));
      var reread = requests.getLast();
      assertEquals(0, reread.x()); assertEquals(0, reread.z());
      assertArrayEquals(new int[] {-2, 2}, reread.ys());
      assertEquals(0, streamer.sentColumns(client.player.getUuid()));
    }
  }

  @Test void gradualUpwardTravelKeepsOwnershipWithinCurrentViewAndUnloadsOldSections() {
    var requests = new ArrayList<Request>();
    var client = new Client();
    try (var streamer = streamer(requests)) {
      for (int y = 0; y < 160; y++) {
        client.tick(streamer, new Vector3d(0, y * 32, 0), 32, 64, 128);
        var request = requests.getLast();
        request.result().complete(Arrays.stream(request.ys())
            .mapToObj(sectionY -> (ToClientPacket) section(request, sectionY)).toList());
        client.tick(streamer, new Vector3d(0, y * 32, 0), 32, 64, 128);
        assertTrue(streamer.sentColumns(client.player.getUuid()) <= 13);
      }
      var packets = org.mockito.ArgumentCaptor.forClass(ToClientPacket.class);
      verify(client.packets, atLeastOnce()).writeNoCache(packets.capture());
      assertTrue(packets.getAllValues().stream().filter(UnloadChunks.class::isInstance)
          .map(UnloadChunks.class::cast).anyMatch(packet -> packet.sections != null && packet.sections.length > 0));
      // The memory bound matters as well as packet correctness during arbitrarily long travel.
      var viewsField = SimViewDiskStreamer.class.getDeclaredField("views");
      viewsField.setAccessible(true);
      var views = (java.util.Map<?, ?>) viewsField.get(streamer);
      var view = views.get(client.player.getUuid());
      var columnsField = view.getClass().getDeclaredField("columns");
      columnsField.setAccessible(true);
      for (var column : ((java.util.Map<?, ?>) columnsField.get(view)).values()) {
        var sectionsField = column.getClass().getDeclaredField("sections");
        sectionsField.setAccessible(true);
        assertTrue(((java.util.BitSet) sectionsField.get(column)).length() <= 5);
      }
    } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
  }
}
