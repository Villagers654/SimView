package net.modtale.simview.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import net.modtale.simview.service.SimViewDistanceService;
import net.modtale.simview.config.TestConfigs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimViewDistanceDisplayTest {
  @Test void diskGenerationVisibilityAndEditingFollowLiveModeAndRejectStaleNativeSelection() throws Exception {
    var nativeConfig = TestConfigs.config("{}");
    var diskConfig = TestConfigs.config("{\"section-streaming\":{\"mode\":\"disk\"}}");
    var config = new java.util.concurrent.atomic.AtomicReference<>(nativeConfig);
    var service = mock(SimViewDistanceService.class);
    when(service.current()).thenAnswer(call -> config.get());
    when(service.saveAndReload(any())).thenAnswer(call -> { config.set(call.getArgument(0)); return config.get(); });
    var page = new SimViewConfigPage(mock(PlayerRef.class), service);
    var advanced = SimViewConfigPage.class.getDeclaredField("advancedMode");
    advanced.setAccessible(true); advanced.set(page, true);
    var query = SimViewConfigPage.class.getDeclaredField("searchQuery");
    query.setAccessible(true); query.set(page, "generatemissingchunks");
    var render = SimViewConfigPage.class.getDeclaredMethod("renderList", UICommandBuilder.class, UIEventBuilder.class);
    render.setAccessible(true);
    var commands = mock(UICommandBuilder.class);
    render.invoke(page, commands, mock(UIEventBuilder.class));
    verify(commands, never()).append(eq("#IndexCards"), anyString());
    config.set(diskConfig);
    commands = mock(UICommandBuilder.class);
    render.invoke(page, commands, mock(UIEventBuilder.class));
    verify(commands, times(1)).append(eq("#IndexCards"), anyString());
    var selected = SimViewConfigPage.class.getDeclaredField("selectedSettingId");
    selected.setAccessible(true); selected.set(page, "generateMissingChunks");
    var current = SimViewConfigPage.class.getDeclaredMethod("currentSetting"); current.setAccessible(true);
    var setting = ((java.util.Optional<?>) current.invoke(page)).orElseThrow();
    var pending = SimViewConfigPage.class.getDeclaredField("pendingBooleanValue");
    pending.setAccessible(true); pending.set(page, true);
    var apply = SimViewConfigPage.class.getDeclaredMethod("applyPending", setting.getClass());
    apply.setAccessible(true);
    assertEquals(true, apply.invoke(page, setting));
    assertTrue(config.get().generateMissingChunks());
    config.set(nativeConfig);
    assertEquals(false, apply.invoke(page, setting));
    verify(service, times(1)).saveAndReload(any());
    var ensureVisible = SimViewConfigPage.class.getDeclaredMethod("ensureSelectionIsVisible");
    ensureVisible.setAccessible(true); ensureVisible.invoke(page);
    assertEquals("targetViewDistanceBlocks", selected.get(page));
  }

  @Test void primarySettingsStayVisibleAboveAdvancedEvenWhenSearchHasNoMatches() throws Exception {
    var service = mock(SimViewDistanceService.class);
    when(service.current()).thenReturn(TestConfigs.config("{}"));
    var page = new SimViewConfigPage(mock(PlayerRef.class), service);
    var render = SimViewConfigPage.class.getDeclaredMethod("renderList", UICommandBuilder.class, UIEventBuilder.class);
    render.setAccessible(true);
    var commands = mock(UICommandBuilder.class);
    render.invoke(page, commands, mock(UIEventBuilder.class));
    verify(commands).set("#IndexList.Visible", false);
    verify(commands, times(3)).append(eq("#PrimaryCards"), anyString());
    verify(commands, never()).append(eq("#IndexCards"), anyString());
    var advanced = SimViewConfigPage.class.getDeclaredField("advancedMode");
    advanced.setAccessible(true);
    advanced.set(page, true);
    var query = SimViewConfigPage.class.getDeclaredField("searchQuery");
    query.setAccessible(true);
    query.set(page, "no-setting-matches-this");
    commands = mock(UICommandBuilder.class);
    render.invoke(page, commands, mock(UIEventBuilder.class));
    verify(commands).set("#IndexList.Visible", true);
    verify(commands).set("#NoResultsLabel.Visible", true);
    verify(commands, times(3)).append(eq("#PrimaryCards"), anyString());
    verify(commands, never()).append(eq("#IndexCards"), anyString());
  }

  @Test void statusDistancesDisplayBlocksAndServerDefault() {
    assertEquals("1024 blocks", SimViewConfigPage.formatDistance(1024));
    assertEquals("2048 blocks", SimViewConfigPage.formatDistance(2048));
    assertEquals("Server default", SimViewConfigPage.formatDistance(-1));
  }

  @Test void editableDistanceValuesAndLabelsUseBlocksWithoutChangingSectionBudgets() throws Exception {
    var field = SimViewConfigPage.class.getDeclaredField("SETTINGS");
    field.setAccessible(true);
    List<?> settings = (List<?>) field.get(null);
    var config = TestConfigs.config("{}");
    int distances = 0;
    for (Object setting : settings) {
      var id = setting.getClass().getDeclaredMethod("id");
      var label = setting.getClass().getDeclaredMethod("label");
      id.setAccessible(true);
      label.setAccessible(true);
      String settingId = (String) id.invoke(setting);
      if (settingId.contains("DistanceBlocks")) {
        distances++;
        assertTrue(((String) label.invoke(setting)).contains("(blocks)"));
        if (settingId.equals("targetViewDistanceBlocks")) {
          var display = SimViewConfigPage.class.getDeclaredMethod("valueAsDisplay", setting.getClass(), config.getClass());
          display.setAccessible(true);
          assertEquals("1024", display.invoke(null, setting, config));
        }
      }
      if (settingId.equals("maxSectionSendsPerSecond")) {
        assertFalse(((String) label.invoke(setting)).contains("blocks"));
      }
    }
    assertEquals(6, distances);
  }
}
