package net.modtale.simview.ui;

import net.modtale.simview.config.SimViewAdjustmentMode;
import net.modtale.simview.config.SimViewConfig;
import net.modtale.simview.config.SimViewDistances;
import net.modtale.simview.permission.SimViewAccessControl;
import net.modtale.simview.service.SimViewAutoTuner;
import net.modtale.simview.service.SimViewDistanceService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SimViewConfigPage
    extends com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage<
        SimViewConfigPage.PageEventData> {

  private static final String UI_PAGE = "Pages/SimView/SimViewConfigPage.ui";
  private static final String UI_SETTING_ENTRY = "Pages/SimView/SimViewSettingEntry.ui";

  private static final String ACTION_SELECT = "SelectSetting";
  private static final String ACTION_BOOLEAN_CHANGED = "BooleanChanged";
  private static final String ACTION_TEXT_CHANGED = "TextChanged";
  private static final String ACTION_ENUM_CHANGED = "EnumChanged";
  private static final String ACTION_APPLY = "ApplySetting";
  private static final String ACTION_TOGGLE_ADVANCED = "ToggleAdvanced";

  private static final Set<String> SIMPLE_SETTING_IDS =
      Set.of(
          "targetViewDistanceBlocks",
          "targetSimulationDistanceBlocks",
          "disableJoinHintMessage");

  private static final List<SettingDef> SETTINGS =
      List.of(
          new SettingDef(
              "enabled", "Core: Enabled", SettingKind.BOOLEAN, "Enable or disable SimView"),
          new SettingDef(
              "guiEnabled",
              "Core: GUI Enabled",
              SettingKind.BOOLEAN,
              "Enable or disable this GUI"),
          new SettingDef(
              "disableJoinHintMessage",
              "Core: Disable Join Tip Message",
              SettingKind.BOOLEAN,
              "Disable the /simview tip message shown when players join"),
          new SettingDef(
              "targetViewDistanceBlocks",
              "Target: View Distance (blocks)",
              SettingKind.INTEGER,
              "Visible distance in blocks (0-2048); rounded down to multiples of 32"),
          new SettingDef(
              "targetSimulationDistanceBlocks",
              "Target: Simulation Distance (blocks)",
              SettingKind.INTEGER,
              "Ticking distance in blocks (0-2048, multiples of 32); -1 uses the native hot radius"),
          new SettingDef(
              "adjustmentMode",
              "Auto Mode: View",
              SettingKind.MODE,
              "Auto adjustment mode for view distance"),
          new SettingDef(
              "simulationAdjustmentMode",
              "Auto Mode: Simulation",
              SettingKind.MODE,
              "Auto adjustment mode for simulation distance"),
          new SettingDef(
              "minimumTargetViewDistanceBlocks",
              "Limit Min: View Distance (blocks)",
              SettingKind.INTEGER,
              "Minimum view distance in blocks (0-2048); rounded down to multiples of 32"),
          new SettingDef(
              "maximumTargetViewDistanceBlocks",
              "Limit Max: View Distance (blocks)",
              SettingKind.INTEGER,
              "Maximum view distance in blocks (0-2048); rounded down to multiples of 32"),
          new SettingDef(
              "minimumTargetSimulationDistanceBlocks",
              "Limit Min: Simulation Distance (blocks)",
              SettingKind.INTEGER,
              "Minimum simulation distance in blocks (0-2048); rounded down to multiples of 32"),
          new SettingDef(
              "maximumTargetSimulationDistanceBlocks",
              "Limit Max: Simulation Distance (blocks)",
              SettingKind.INTEGER,
              "Maximum simulation distance in blocks (0-2048); rounded down to multiples of 32"),
          new SettingDef(
              "adjustmentTicksPerCheck",
              "Auto Cadence: Ticks Per Check",
              SettingKind.INTEGER,
              "How often auto adjustment evaluates"),
          new SettingDef(
              "adjustmentStartupDelayTicks",
              "Auto Cadence: Startup Delay Ticks",
              SettingKind.INTEGER,
              "Delay before the first auto-adjust check"),
          new SettingDef(
              "adjustmentPassedChecksForIncrease",
              "Auto Checks: View Increase",
              SettingKind.INTEGER,
              "Consecutive view increase checks required"),
          new SettingDef(
              "adjustmentPassedChecksForDecrease",
              "Auto Checks: View Decrease",
              SettingKind.INTEGER,
              "Consecutive view decrease checks required"),
          new SettingDef(
              "simulationAdjustmentPassedChecksForIncrease",
              "Auto Checks: Simulation Increase",
              SettingKind.INTEGER,
              "Consecutive simulation increase checks required"),
          new SettingDef(
              "simulationAdjustmentPassedChecksForDecrease",
              "Auto Checks: Simulation Decrease",
              SettingKind.INTEGER,
              "Consecutive simulation decrease checks required"),
          new SettingDef(
              "proactiveGlobalColdSectionCountTarget",
              "Auto Proactive: Section Target",
              SettingKind.LONG,
              "Global cold section estimate target"),
          new SettingDef(
              "proactiveGlobalTickingSectionCountTarget",
              "Auto Proactive: Ticking Section Target",
              SettingKind.LONG,
              "Global ticking section estimate target"),
          new SettingDef(
              "reactiveIncreaseMsptThreshold",
              "Auto Reactive: Increase MSPT",
              SettingKind.DOUBLE,
              "MSPT threshold for allowing increases"),
          new SettingDef(
              "reactiveDecreaseMsptThreshold",
              "Auto Reactive: Decrease MSPT",
              SettingKind.DOUBLE,
              "MSPT threshold for forcing decreases"),
          new SettingDef(
              "reactiveMsptCollectionPeriodTicks",
              "Auto Reactive: Collection Period",
              SettingKind.INTEGER,
              "Ticks between MSPT collection windows"),
          new SettingDef(
              "reactiveUseMsptPrediction",
              "Auto Reactive: Use Prediction",
              SettingKind.BOOLEAN,
              "Use historical MSPT prediction to block risky increases"),
          new SettingDef(
              "reactiveMsptPredictionHistoryMinutes",
              "Auto Reactive: Prediction History",
              SettingKind.INTEGER,
              "Minutes of history for MSPT prediction"),
          new SettingDef(
              "maxSectionSendsPerSecond",
              "Section Streaming: Sends Per Second",
              SettingKind.INTEGER,
              "Section budget per second; 0 preserves the native connection budget"),
          new SettingDef(
              "maxSectionSendsPerTick",
              "Section Streaming: Sends Per Tick",
              SettingKind.INTEGER,
              "Section budget per tick; 0 preserves the native budget"),
          new SettingDef(
              "despawnEntitiesInColdChunks",
              "Cold Streaming: Despawn Entities",
              SettingKind.BOOLEAN,
              "Despawn entities in sections"),
          new SettingDef(
              "speedingNotSendBlocksPerTick",
              "Speeding: Not-Send Blocks/Tick",
              SettingKind.DOUBLE,
              "Speed threshold before tighter budgets apply"),
          new SettingDef(
              "speedingSectionSendsPerSecond",
              "Speeding: Sends Per Second",
              SettingKind.INTEGER,
              "Section budget per second while speeding; 0 inherits the normal budget"),
          new SettingDef(
              "speedingSectionSendsPerTick",
              "Speeding: Sends Per Tick",
              SettingKind.INTEGER,
              "Section budget per tick while speeding; 0 inherits the normal budget"),
          new SettingDef(
              "speedingCooldownTicks",
              "Speeding: Cooldown Ticks",
              SettingKind.INTEGER,
              "Ticks before normal budgets resume"));

  private static final Map<String, SettingDef> SETTINGS_BY_ID = indexById();

  private final SimViewDistanceService distanceService;

  private String searchQuery = "";
  private String selectedSettingId = "targetViewDistanceBlocks";
  private boolean advancedMode;
  private String feedback = "Choose a setting and click Apply to save.";
  private String pendingTextValue = "";
  private boolean pendingBooleanValue;
  private String pendingEnumValue = SimViewAdjustmentMode.OFF.name();
  private boolean pendingDirty;

  public SimViewConfigPage(PlayerRef playerRef, SimViewDistanceService distanceService) {
    super(playerRef, CustomPageLifetime.CanDismiss, PageEventData.CODEC);
    this.distanceService = distanceService;
    resetPendingForSelection();
  }

  @Override
  public void build(
      Ref<EntityStore> ref,
      UICommandBuilder commandBuilder,
      UIEventBuilder eventBuilder,
      Store<EntityStore> store) {
    commandBuilder.append(UI_PAGE);

    commandBuilder.set("#SearchInput.Value", searchQuery);
    eventBuilder.addEventBinding(
        CustomUIEventBindingType.ValueChanged,
        "#SearchInput",
        EventData.of(PageEventData.KEY_SEARCH_QUERY, "#SearchInput.Value"),
        false);

    eventBuilder.addEventBinding(
        CustomUIEventBindingType.ValueChanged,
        "#BooleanEditor #CheckBox",
        EventData.of(PageEventData.KEY_ACTION, ACTION_BOOLEAN_CHANGED)
            .append(PageEventData.KEY_BOOLEAN_VALUE, "#BooleanEditor #CheckBox.Value"),
        false);

    eventBuilder.addEventBinding(
        CustomUIEventBindingType.ValueChanged,
        "#TextEditor",
        EventData.of(PageEventData.KEY_ACTION, ACTION_TEXT_CHANGED)
            .append(PageEventData.KEY_TEXT_VALUE, "#TextEditor.Value"),
        false);

    eventBuilder.addEventBinding(
        CustomUIEventBindingType.ValueChanged,
        "#EnumEditor",
        EventData.of(PageEventData.KEY_ACTION, ACTION_ENUM_CHANGED)
            .append(PageEventData.KEY_ENUM_VALUE, "#EnumEditor.Value"),
        false);

    eventBuilder.addEventBinding(
        CustomUIEventBindingType.Activating,
        "#ApplyButton",
        EventData.of(PageEventData.KEY_ACTION, ACTION_APPLY),
        false);

    eventBuilder.addEventBinding(
        CustomUIEventBindingType.Activating,
        "#AdvancedToggleButton",
        EventData.of(PageEventData.KEY_ACTION, ACTION_TOGGLE_ADVANCED),
        false);

    render(commandBuilder, eventBuilder);
  }

  @Override
  public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
    if (!distanceService.current().guiEnabled() || !SimViewAccessControl.canUseGui(playerRef)) {
      close();
      return;
    }
    super.handleDataEvent(ref, store, data);

    if (data.searchQuery != null) {
      searchQuery = data.searchQuery.trim().toLowerCase(Locale.ROOT);
      rerender();
      return;
    }

    if (data.action == null || data.action.isBlank()) {
      return;
    }

    if (ACTION_SELECT.equals(data.action)) {
      if (data.settingId != null && SETTINGS_BY_ID.containsKey(data.settingId)) {
        selectedSettingId = data.settingId;
        resetPendingForSelection();
      }
      rerender();
      return;
    }

    if (ACTION_TOGGLE_ADVANCED.equals(data.action)) {
      advancedMode = !advancedMode;
      if (!advancedMode && !SIMPLE_SETTING_IDS.contains(selectedSettingId)) {
        selectedSettingId = "targetViewDistanceBlocks";
        resetPendingForSelection();
      }
      rerender();
      return;
    }

    Optional<SettingDef> selectedSetting = currentSetting();
    if (selectedSetting.isEmpty()) {
      return;
    }

    boolean changed = false;
    switch (data.action) {
      case ACTION_BOOLEAN_CHANGED -> {
        pendingBooleanValue = data.booleanValue;
        pendingDirty = true;
        feedback = "Unsaved change. Click Apply.";
        updateDirtyUiState();
        return;
      }
      case ACTION_TEXT_CHANGED -> {
        pendingTextValue = data.textValue == null ? "" : data.textValue;
        pendingDirty = true;
        feedback = "Unsaved change. Click Apply.";
        updateDirtyUiState();
        return;
      }
      case ACTION_ENUM_CHANGED -> {
        pendingEnumValue = data.enumValue == null ? SimViewAdjustmentMode.OFF.name() : data.enumValue;
        pendingDirty = true;
        feedback = "Unsaved change. Click Apply.";
        updateDirtyUiState();
        return;
      }
      case ACTION_APPLY -> {
        try {
          synchronized (distanceService) {
            changed = applyPending(selectedSetting.get());
          }
        } catch (IllegalStateException exception) {
          feedback = "Unable to save settings. Check the server configuration directory.";
          playerRef.sendMessage(Message.raw(feedback).color("red"));
        }
      }
      default -> {
        return;
      }
    }

    rerender();

    if (changed) {
      playerRef.sendMessage(Message.raw(feedback).color("green"));
    }
  }

  private void rerender() {
    UICommandBuilder commandBuilder = new UICommandBuilder();
    UIEventBuilder eventBuilder = new UIEventBuilder();
    render(commandBuilder, eventBuilder);
    sendUpdate(commandBuilder, eventBuilder, false);
  }

  private void updateDirtyUiState() {
    UICommandBuilder commandBuilder = new UICommandBuilder();
    commandBuilder.set("#ApplyButton.Disabled", !pendingDirty);
    commandBuilder.set("#ApplyState.Text", feedback);
    sendUpdate(commandBuilder, new UIEventBuilder(), false);
  }

  private boolean applyPending(SettingDef setting) {
    return switch (setting.kind()) {
      case BOOLEAN -> applyBoolean(setting, pendingBooleanValue);
      case MODE -> applyMode(setting, pendingEnumValue);
      case INTEGER, LONG, DOUBLE -> applyText(setting, pendingTextValue);
    };
  }

  private void resetPendingForSelection() {
    Optional<SettingDef> selected = currentSetting();
    if (selected.isEmpty()) {
      pendingTextValue = "";
      pendingBooleanValue = false;
      pendingEnumValue = SimViewAdjustmentMode.OFF.name();
      pendingDirty = false;
      return;
    }

    SettingDef setting = selected.get();
    SimViewConfig config = distanceService.current();
    pendingTextValue = valueAsDisplay(setting, config);
    pendingBooleanValue = booleanValue(setting.id(), config);
    pendingEnumValue = modeValue(setting.id(), config).name();
    pendingDirty = false;
  }

  private void ensureSelectionIsVisible() {
    if (!advancedMode && !SIMPLE_SETTING_IDS.contains(selectedSettingId)) {
      selectedSettingId = "targetViewDistanceBlocks";
      resetPendingForSelection();
    }
  }

  private void render(UICommandBuilder commandBuilder, UIEventBuilder eventBuilder) {
    ensureSelectionIsVisible();
    SimViewConfig config = distanceService.current();
    SimViewAutoTuner.AutoTuneSnapshot autoTuneSnapshot = distanceService.autoTuneSnapshot();

    renderList(commandBuilder, eventBuilder);
    renderEditor(commandBuilder);
    commandBuilder.set("#ApplyState.Text", feedback);
    commandBuilder.set("#ApplyButton.Disabled", !pendingDirty);
    commandBuilder.set("#AdvancedToggleButton.Text", advancedMode ? "Advanced: ON" : "Advanced: OFF");
    commandBuilder.set("#SearchInput.Visible", advancedMode);
    commandBuilder.set("#RuntimeInfo.Visible", advancedMode);
    commandBuilder.set("#StatusInfo.Visible", advancedMode);

    commandBuilder.set(
        "#RuntimeSimulationDistance.Text", String.valueOf(distanceService.activeSimulationDistanceCap()));
    commandBuilder.set("#RuntimeViewDistance.Text", String.valueOf(distanceService.activeViewDistanceCap()));
    commandBuilder.set("#StatusEnabled.Text", config.enabled() ? "Enabled" : "Disabled");
    commandBuilder.set(
        "#StatusConfiguredSimulationDistance.Text",
        formatDistance(distanceService.hytaleSimulationDistanceBlocks()));
    commandBuilder.set(
        "#StatusActiveSimulationDistance.Text",
        formatDistance(distanceService.activeSimulationDistanceCap()));
    commandBuilder.set(
        "#StatusConfiguredSimulationTarget.Text", formatDistance(config.targetSimulationDistanceBlocks()));
    commandBuilder.set(
        "#StatusActiveSimulationTarget.Text",
        formatDistance(distanceService.activeTargetSimulationDistanceBlocks()));
    commandBuilder.set(
        "#StatusConfiguredViewTarget.Text", formatDistance(config.targetViewDistanceBlocks()));
    commandBuilder.set(
        "#StatusActiveViewTarget.Text", formatDistance(distanceService.activeTargetViewDistanceBlocks()));
    commandBuilder.set(
        "#StatusViewMode.Text",
        config.adjustmentMode().name().toLowerCase(Locale.ROOT)
            + " (last: "
            + autoTuneSnapshot.lastViewCandidate().name().toLowerCase(Locale.ROOT)
            + ")");
    commandBuilder.set(
        "#StatusSimulationMode.Text",
        config.simulationAdjustmentMode().name().toLowerCase(Locale.ROOT)
            + " (last: "
            + autoTuneSnapshot.lastSimulationCandidate().name().toLowerCase(Locale.ROOT)
            + ")");
    commandBuilder.set("#StatusMspt.Text", String.format(Locale.ROOT, "%.2f", autoTuneSnapshot.mspt()));
    commandBuilder.set(
        "#StatusColdChunks.Text",
        autoTuneSnapshot.estimatedColdSections() + " / " + autoTuneSnapshot.proactiveColdSectionTarget());
    commandBuilder.set(
        "#StatusTickingChunks.Text",
        autoTuneSnapshot.estimatedTickingSections()
            + " / "
            + autoTuneSnapshot.proactiveTickingSectionTarget());
    commandBuilder.set(
        "#StatusViewChecks.Text",
        "+"
            + autoTuneSnapshot.viewConsecutiveIncreaseChecks()
            + " / -"
            + autoTuneSnapshot.viewConsecutiveDecreaseChecks());
    commandBuilder.set(
        "#StatusSimulationChecks.Text",
        "+"
            + autoTuneSnapshot.simulationConsecutiveIncreaseChecks()
            + " / -"
            + autoTuneSnapshot.simulationConsecutiveDecreaseChecks());
  }

  private void renderList(UICommandBuilder commandBuilder, UIEventBuilder eventBuilder) {
    commandBuilder.clear("#IndexCards");

    List<SettingDef> visibleSettings = visibleSettings();
    if (visibleSettings.isEmpty()) {
      commandBuilder.set("#NoResultsLabel.Visible", true);
      return;
    }

    commandBuilder.set("#NoResultsLabel.Visible", false);

    for (int index = 0; index < visibleSettings.size(); index++) {
      SettingDef setting = visibleSettings.get(index);
      commandBuilder.append("#IndexCards", UI_SETTING_ENTRY);
      String row = "#IndexCards[" + index + "]";
      commandBuilder.set(row + " #SettingName.Text", setting.label());
      commandBuilder.set(row + " #SettingValue.Text", valueAsDisplay(setting, distanceService.current()));
      commandBuilder.set(
          row + " #SelectButton.Text",
          Objects.equals(setting.id(), selectedSettingId) ? "Selected" : "Edit");
      commandBuilder.set(
          row + " #SelectButton.Disabled", Objects.equals(setting.id(), selectedSettingId));
      eventBuilder.addEventBinding(
          CustomUIEventBindingType.Activating,
          row + " #SelectButton",
          EventData.of(PageEventData.KEY_ACTION, ACTION_SELECT)
              .append(PageEventData.KEY_SETTING_ID, setting.id()),
          false);
    }
  }

  private void renderEditor(UICommandBuilder commandBuilder) {
    Optional<SettingDef> selectedSetting = currentSetting();
    if (selectedSetting.isEmpty()) {
      commandBuilder.set("#SelectedSetting.Text", "No setting selected");
      commandBuilder.set("#SelectedDescription.Text", "");
      commandBuilder.set("#BooleanEditor.Visible", false);
      commandBuilder.set("#TextEditorGroup.Visible", false);
      commandBuilder.set("#EnumEditorGroup.Visible", false);
      return;
    }

    SettingDef setting = selectedSetting.get();

    commandBuilder.set("#SelectedSetting.Text", setting.label());
    commandBuilder.set("#SelectedDescription.Text", setting.description());

    commandBuilder.set("#BooleanEditor.Visible", setting.kind() == SettingKind.BOOLEAN);
    commandBuilder.set(
        "#TextEditorGroup.Visible",
        setting.kind() == SettingKind.INTEGER
            || setting.kind() == SettingKind.LONG
            || setting.kind() == SettingKind.DOUBLE);
    commandBuilder.set("#EnumEditorGroup.Visible", setting.kind() == SettingKind.MODE);

    if (setting.kind() == SettingKind.BOOLEAN) {
      commandBuilder.set("#BooleanEditor #CheckBox.Value", pendingBooleanValue);
    }

    if (setting.kind() == SettingKind.INTEGER
        || setting.kind() == SettingKind.LONG
        || setting.kind() == SettingKind.DOUBLE) {
      commandBuilder.set("#TextEditor.Value", pendingTextValue);
    }

    if (setting.kind() == SettingKind.MODE) {
      commandBuilder.set("#EnumEditor.Entries", modeEntries());
      commandBuilder.set("#EnumEditor.Value", pendingEnumValue);
    }
  }

  private List<SettingDef> visibleSettings() {
    List<SettingDef> sourceSettings = SETTINGS;
    if (!advancedMode) {
      sourceSettings = new ArrayList<>();
      for (SettingDef setting : SETTINGS) {
        if (SIMPLE_SETTING_IDS.contains(setting.id())) {
          sourceSettings.add(setting);
        }
      }
    }

    if (searchQuery.isBlank()) {
      return sourceSettings;
    }

    List<SettingDef> visible = new ArrayList<>();
    for (SettingDef setting : sourceSettings) {
      String haystack =
          (setting.label() + " " + setting.id() + " " + setting.description())
              .toLowerCase(Locale.ROOT);
      if (haystack.contains(searchQuery)) {
        visible.add(setting);
      }
    }
    return visible;
  }

  private Optional<SettingDef> currentSetting() {
    return Optional.ofNullable(SETTINGS_BY_ID.get(selectedSettingId));
  }

  private boolean applyBoolean(SettingDef setting, boolean value) {
    if (setting.kind() != SettingKind.BOOLEAN) {
      return false;
    }

    SimViewConfig current = distanceService.current();
    ConfigDraft draft = new ConfigDraft(current);

    switch (setting.id()) {
      case "enabled" -> draft.enabled = value;
      case "guiEnabled" -> draft.guiEnabled = value;
      case "disableJoinHintMessage" -> draft.disableJoinHintMessage = value;
      case "reactiveUseMsptPrediction" -> draft.reactiveUseMsptPrediction = value;
      case "despawnEntitiesInColdChunks" -> draft.despawnEntitiesInColdChunks = value;
      default -> {
        feedback = "This setting cannot be edited as a boolean.";
        return false;
      }
    }

    distanceService.saveAndReload(draft.toConfig());
    feedback = "Updated " + setting.label() + ".";
    pendingDirty = false;
    resetPendingForSelection();
    return true;
  }

  private boolean applyMode(SettingDef setting, String rawMode) {
    if (setting.kind() != SettingKind.MODE || rawMode == null) {
      return false;
    }

    SimViewAdjustmentMode mode = SimViewAdjustmentMode.fromProperty(rawMode, null);
    if (mode == null) {
      feedback = "Invalid mode value.";
      return false;
    }

    SimViewConfig current = distanceService.current();
    ConfigDraft draft = new ConfigDraft(current);

    switch (setting.id()) {
      case "adjustmentMode" -> draft.adjustmentMode = mode;
      case "simulationAdjustmentMode" -> draft.simulationAdjustmentMode = mode;
      default -> {
        feedback = "This setting cannot be edited as a mode.";
        return false;
      }
    }

    distanceService.saveAndReload(draft.toConfig());
    feedback = "Updated " + setting.label() + " to " + mode.name().toLowerCase(Locale.ROOT) + ".";
    pendingDirty = false;
    resetPendingForSelection();
    return true;
  }

  private boolean applyText(SettingDef setting, String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      feedback = "Value cannot be blank.";
      return false;
    }

    SimViewConfig current = distanceService.current();
    ConfigDraft draft = new ConfigDraft(current);

    try {
      switch (setting.id()) {
        case "targetViewDistanceBlocks" -> draft.targetViewDistanceBlocks = nonNegativeInt(rawValue);
        case "targetSimulationDistanceBlocks" ->
            draft.targetSimulationDistanceBlocks = minInt(rawValue, -1);
        case "minimumTargetViewDistanceBlocks" ->
            draft.minimumTargetViewDistanceBlocks = nonNegativeInt(rawValue);
        case "maximumTargetViewDistanceBlocks" ->
            draft.maximumTargetViewDistanceBlocks = nonNegativeInt(rawValue);
        case "minimumTargetSimulationDistanceBlocks" ->
            draft.minimumTargetSimulationDistanceBlocks = nonNegativeInt(rawValue);
        case "maximumTargetSimulationDistanceBlocks" ->
            draft.maximumTargetSimulationDistanceBlocks = nonNegativeInt(rawValue);
        case "adjustmentTicksPerCheck" -> draft.adjustmentTicksPerCheck = positiveInt(rawValue);
        case "adjustmentStartupDelayTicks" ->
            draft.adjustmentStartupDelayTicks = nonNegativeInt(rawValue);
        case "adjustmentPassedChecksForIncrease" ->
            draft.adjustmentPassedChecksForIncrease = positiveInt(rawValue);
        case "adjustmentPassedChecksForDecrease" ->
            draft.adjustmentPassedChecksForDecrease = positiveInt(rawValue);
        case "simulationAdjustmentPassedChecksForIncrease" ->
            draft.simulationAdjustmentPassedChecksForIncrease = positiveInt(rawValue);
        case "simulationAdjustmentPassedChecksForDecrease" ->
            draft.simulationAdjustmentPassedChecksForDecrease = positiveInt(rawValue);
        case "proactiveGlobalColdSectionCountTarget" ->
            draft.proactiveGlobalColdSectionCountTarget = nonNegativeLong(rawValue);
        case "proactiveGlobalTickingSectionCountTarget" ->
            draft.proactiveGlobalTickingSectionCountTarget = nonNegativeLong(rawValue);
        case "reactiveIncreaseMsptThreshold" ->
            draft.reactiveIncreaseMsptThreshold = nonNegativeDouble(rawValue);
        case "reactiveDecreaseMsptThreshold" ->
            draft.reactiveDecreaseMsptThreshold = nonNegativeDouble(rawValue);
        case "reactiveMsptCollectionPeriodTicks" ->
            draft.reactiveMsptCollectionPeriodTicks = positiveInt(rawValue);
        case "reactiveMsptPredictionHistoryMinutes" ->
            draft.reactiveMsptPredictionHistoryMinutes = positiveInt(rawValue);
        case "maxSectionSendsPerSecond" -> draft.maxSectionSendsPerSecond = nonNegativeInt(rawValue);
        case "maxSectionSendsPerTick" -> draft.maxSectionSendsPerTick = nonNegativeInt(rawValue);
        case "speedingNotSendBlocksPerTick" ->
            draft.speedingNotSendBlocksPerTick = nonNegativeDouble(rawValue);
        case "speedingSectionSendsPerSecond" ->
            draft.speedingSectionSendsPerSecond = nonNegativeInt(rawValue);
        case "speedingSectionSendsPerTick" -> draft.speedingSectionSendsPerTick = nonNegativeInt(rawValue);
        case "speedingCooldownTicks" -> draft.speedingCooldownTicks = nonNegativeInt(rawValue);
        default -> {
          feedback = "This setting is not edited with a text value.";
          return false;
        }
      }
    } catch (IllegalArgumentException exception) {
      feedback = exception.getMessage();
      return false;
    }

    distanceService.saveAndReload(draft.toConfig());
    feedback = "Updated " + setting.label() + ".";
    pendingDirty = false;
    resetPendingForSelection();
    return true;
  }

  private static List<DropdownEntryInfo> modeEntries() {
    return List.of(
        new DropdownEntryInfo(LocalizableString.fromString("Off"), SimViewAdjustmentMode.OFF.name()),
        new DropdownEntryInfo(
            LocalizableString.fromString("Proactive"), SimViewAdjustmentMode.PROACTIVE.name()),
        new DropdownEntryInfo(
            LocalizableString.fromString("Reactive"), SimViewAdjustmentMode.REACTIVE.name()),
        new DropdownEntryInfo(
            LocalizableString.fromString("Mixed"), SimViewAdjustmentMode.MIXED.name()));
  }

  private static int nonNegativeInt(String rawValue) {
    int parsed = Integer.parseInt(rawValue.trim());
    if (parsed < 0) {
      throw new IllegalArgumentException("Value must be >= 0.");
    }
    return parsed;
  }

  private static int positiveInt(String rawValue) {
    int parsed = Integer.parseInt(rawValue.trim());
    if (parsed <= 0) {
      throw new IllegalArgumentException("Value must be > 0.");
    }
    return parsed;
  }

  private static int minInt(String rawValue, int minimum) {
    int parsed = Integer.parseInt(rawValue.trim());
    if (parsed < minimum) {
      throw new IllegalArgumentException("Value must be >= " + minimum + ".");
    }
    return parsed;
  }

  private static long nonNegativeLong(String rawValue) {
    long parsed = Long.parseLong(rawValue.trim());
    if (parsed < 0L) {
      throw new IllegalArgumentException("Value must be >= 0.");
    }
    return parsed;
  }

  private static double nonNegativeDouble(String rawValue) {
    double parsed = Double.parseDouble(rawValue.trim());
    if (!Double.isFinite(parsed) || parsed < 0D) {
      throw new IllegalArgumentException("Value must be finite and >= 0.");
    }
    return parsed;
  }

  private static Map<String, SettingDef> indexById() {
    Map<String, SettingDef> map = new HashMap<>();
    for (SettingDef setting : SETTINGS) {
      map.put(setting.id(), setting);
    }
    return map;
  }

  private static String valueAsDisplay(SettingDef setting, SimViewConfig config) {
    return switch (setting.id()) {
      case "enabled" -> Boolean.toString(config.enabled());
      case "guiEnabled" -> Boolean.toString(config.guiEnabled());
      case "disableJoinHintMessage" -> Boolean.toString(config.disableJoinHintMessage());
      case "targetViewDistanceBlocks" -> Integer.toString(config.targetViewDistanceBlocks());
      case "targetSimulationDistanceBlocks" -> Integer.toString(config.targetSimulationDistanceBlocks());
      case "adjustmentMode" -> config.adjustmentMode().name().toLowerCase(Locale.ROOT);
      case "simulationAdjustmentMode" ->
          config.simulationAdjustmentMode().name().toLowerCase(Locale.ROOT);
      case "minimumTargetViewDistanceBlocks" ->
          Integer.toString(config.minimumTargetViewDistanceBlocks());
      case "maximumTargetViewDistanceBlocks" ->
          Integer.toString(config.maximumTargetViewDistanceBlocks());
      case "minimumTargetSimulationDistanceBlocks" ->
          Integer.toString(config.minimumTargetSimulationDistanceBlocks());
      case "maximumTargetSimulationDistanceBlocks" ->
          Integer.toString(config.maximumTargetSimulationDistanceBlocks());
      case "adjustmentTicksPerCheck" -> Integer.toString(config.adjustmentTicksPerCheck());
      case "adjustmentStartupDelayTicks" -> Integer.toString(config.adjustmentStartupDelayTicks());
      case "adjustmentPassedChecksForIncrease" ->
          Integer.toString(config.adjustmentPassedChecksForIncrease());
      case "adjustmentPassedChecksForDecrease" ->
          Integer.toString(config.adjustmentPassedChecksForDecrease());
      case "simulationAdjustmentPassedChecksForIncrease" ->
          Integer.toString(config.simulationAdjustmentPassedChecksForIncrease());
      case "simulationAdjustmentPassedChecksForDecrease" ->
          Integer.toString(config.simulationAdjustmentPassedChecksForDecrease());
      case "proactiveGlobalColdSectionCountTarget" ->
          Long.toString(config.proactiveGlobalColdSectionCountTarget());
      case "proactiveGlobalTickingSectionCountTarget" ->
          Long.toString(config.proactiveGlobalTickingSectionCountTarget());
      case "reactiveIncreaseMsptThreshold" ->
          Double.toString(config.reactiveIncreaseMsptThreshold());
      case "reactiveDecreaseMsptThreshold" ->
          Double.toString(config.reactiveDecreaseMsptThreshold());
      case "reactiveMsptCollectionPeriodTicks" ->
          Integer.toString(config.reactiveMsptCollectionPeriodTicks());
      case "reactiveUseMsptPrediction" -> Boolean.toString(config.reactiveUseMsptPrediction());
      case "reactiveMsptPredictionHistoryMinutes" ->
          Integer.toString(config.reactiveMsptPredictionHistoryMinutes());
      case "maxSectionSendsPerSecond" -> Integer.toString(config.maxSectionSendsPerSecond());
      case "maxSectionSendsPerTick" -> Integer.toString(config.maxSectionSendsPerTick());
      case "despawnEntitiesInColdChunks" -> Boolean.toString(config.despawnEntitiesInColdChunks());
      case "speedingNotSendBlocksPerTick" ->
          Double.toString(config.speedingNotSendBlocksPerTick());
      case "speedingSectionSendsPerSecond" -> Integer.toString(config.speedingSectionSendsPerSecond());
      case "speedingSectionSendsPerTick" -> Integer.toString(config.speedingSectionSendsPerTick());
      case "speedingCooldownTicks" -> Integer.toString(config.speedingCooldownTicks());
      default -> "";
    };
  }

  static String formatDistance(int blocks) {
    return SimViewDistances.format(blocks);
  }

  private static boolean booleanValue(String settingId, SimViewConfig config) {
    return switch (settingId) {
      case "enabled" -> config.enabled();
      case "guiEnabled" -> config.guiEnabled();
      case "disableJoinHintMessage" -> config.disableJoinHintMessage();
      case "reactiveUseMsptPrediction" -> config.reactiveUseMsptPrediction();
      case "despawnEntitiesInColdChunks" -> config.despawnEntitiesInColdChunks();
      default -> false;
    };
  }

  private static SimViewAdjustmentMode modeValue(String settingId, SimViewConfig config) {
    return switch (settingId) {
      case "adjustmentMode" -> config.adjustmentMode();
      case "simulationAdjustmentMode" -> config.simulationAdjustmentMode();
      default -> SimViewAdjustmentMode.OFF;
    };
  }

  private enum SettingKind {
    BOOLEAN,
    INTEGER,
    LONG,
    DOUBLE,
    MODE
  }

  private record SettingDef(String id, String label, SettingKind kind, String description) {}

  private static final class ConfigDraft {
    private boolean enabled;
    private boolean guiEnabled;
    private boolean disableJoinHintMessage;
    private int targetViewDistanceBlocks;
    private int targetSimulationDistanceBlocks;
    private SimViewAdjustmentMode adjustmentMode;
    private SimViewAdjustmentMode simulationAdjustmentMode;
    private int minimumTargetViewDistanceBlocks;
    private int maximumTargetViewDistanceBlocks;
    private int minimumTargetSimulationDistanceBlocks;
    private int maximumTargetSimulationDistanceBlocks;
    private int adjustmentTicksPerCheck;
    private int adjustmentStartupDelayTicks;
    private int adjustmentPassedChecksForIncrease;
    private int adjustmentPassedChecksForDecrease;
    private int simulationAdjustmentPassedChecksForIncrease;
    private int simulationAdjustmentPassedChecksForDecrease;
    private long proactiveGlobalColdSectionCountTarget;
    private long proactiveGlobalTickingSectionCountTarget;
    private double reactiveIncreaseMsptThreshold;
    private double reactiveDecreaseMsptThreshold;
    private int reactiveMsptCollectionPeriodTicks;
    private boolean reactiveUseMsptPrediction;
    private int reactiveMsptPredictionHistoryMinutes;
    private int maxSectionSendsPerSecond;
    private int maxSectionSendsPerTick;
    private boolean despawnEntitiesInColdChunks;
    private double speedingNotSendBlocksPerTick;
    private int speedingSectionSendsPerSecond;
    private int speedingSectionSendsPerTick;
    private int speedingCooldownTicks;

    private ConfigDraft(SimViewConfig config) {
      this.enabled = config.enabled();
      this.guiEnabled = config.guiEnabled();
      this.disableJoinHintMessage = config.disableJoinHintMessage();
      this.targetViewDistanceBlocks = config.targetViewDistanceBlocks();
      this.targetSimulationDistanceBlocks = config.targetSimulationDistanceBlocks();
      this.adjustmentMode = config.adjustmentMode();
      this.simulationAdjustmentMode = config.simulationAdjustmentMode();
      this.minimumTargetViewDistanceBlocks = config.minimumTargetViewDistanceBlocks();
      this.maximumTargetViewDistanceBlocks = config.maximumTargetViewDistanceBlocks();
      this.minimumTargetSimulationDistanceBlocks = config.minimumTargetSimulationDistanceBlocks();
      this.maximumTargetSimulationDistanceBlocks = config.maximumTargetSimulationDistanceBlocks();
      this.adjustmentTicksPerCheck = config.adjustmentTicksPerCheck();
      this.adjustmentStartupDelayTicks = config.adjustmentStartupDelayTicks();
      this.adjustmentPassedChecksForIncrease = config.adjustmentPassedChecksForIncrease();
      this.adjustmentPassedChecksForDecrease = config.adjustmentPassedChecksForDecrease();
      this.simulationAdjustmentPassedChecksForIncrease =
          config.simulationAdjustmentPassedChecksForIncrease();
      this.simulationAdjustmentPassedChecksForDecrease =
          config.simulationAdjustmentPassedChecksForDecrease();
      this.proactiveGlobalColdSectionCountTarget = config.proactiveGlobalColdSectionCountTarget();
      this.proactiveGlobalTickingSectionCountTarget = config.proactiveGlobalTickingSectionCountTarget();
      this.reactiveIncreaseMsptThreshold = config.reactiveIncreaseMsptThreshold();
      this.reactiveDecreaseMsptThreshold = config.reactiveDecreaseMsptThreshold();
      this.reactiveMsptCollectionPeriodTicks = config.reactiveMsptCollectionPeriodTicks();
      this.reactiveUseMsptPrediction = config.reactiveUseMsptPrediction();
      this.reactiveMsptPredictionHistoryMinutes = config.reactiveMsptPredictionHistoryMinutes();
      this.maxSectionSendsPerSecond = config.maxSectionSendsPerSecond();
      this.maxSectionSendsPerTick = config.maxSectionSendsPerTick();
      this.despawnEntitiesInColdChunks = config.despawnEntitiesInColdChunks();
      this.speedingNotSendBlocksPerTick = config.speedingNotSendBlocksPerTick();
      this.speedingSectionSendsPerSecond = config.speedingSectionSendsPerSecond();
      this.speedingSectionSendsPerTick = config.speedingSectionSendsPerTick();
      this.speedingCooldownTicks = config.speedingCooldownTicks();
    }

    private SimViewConfig toConfig() {
      return new SimViewConfig(
          enabled,
          guiEnabled,
          disableJoinHintMessage,
          targetViewDistanceBlocks,
          targetSimulationDistanceBlocks,
          adjustmentMode,
          simulationAdjustmentMode,
          minimumTargetViewDistanceBlocks,
          maximumTargetViewDistanceBlocks,
          minimumTargetSimulationDistanceBlocks,
          maximumTargetSimulationDistanceBlocks,
          adjustmentTicksPerCheck,
          adjustmentStartupDelayTicks,
          adjustmentPassedChecksForIncrease,
          adjustmentPassedChecksForDecrease,
          simulationAdjustmentPassedChecksForIncrease,
          simulationAdjustmentPassedChecksForDecrease,
          proactiveGlobalColdSectionCountTarget,
          proactiveGlobalTickingSectionCountTarget,
          reactiveIncreaseMsptThreshold,
          reactiveDecreaseMsptThreshold,
          reactiveMsptCollectionPeriodTicks,
          reactiveUseMsptPrediction,
          reactiveMsptPredictionHistoryMinutes,
          maxSectionSendsPerSecond,
          maxSectionSendsPerTick,
          despawnEntitiesInColdChunks,
          speedingNotSendBlocksPerTick,
          speedingSectionSendsPerSecond,
          speedingSectionSendsPerTick,
          speedingCooldownTicks);
    }
  }

  public static final class PageEventData {
    static final String KEY_ACTION = "Action";
    static final String KEY_SETTING_ID = "SettingId";
    static final String KEY_SEARCH_QUERY = "@SearchQuery";
    static final String KEY_TEXT_VALUE = "@TextValue";
    static final String KEY_BOOLEAN_VALUE = "@BoolValue";
    static final String KEY_ENUM_VALUE = "@EnumValue";

    static final BuilderCodec<PageEventData> CODEC =
        BuilderCodec.<PageEventData>builder(PageEventData.class, PageEventData::new)
            .addField(
                new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action)
            .addField(
                new KeyedCodec<>(KEY_SETTING_ID, Codec.STRING),
                (data, value) -> data.settingId = value,
                data -> data.settingId)
            .addField(
                new KeyedCodec<>(KEY_SEARCH_QUERY, Codec.STRING),
                (data, value) -> data.searchQuery = value,
                data -> data.searchQuery)
            .addField(
                new KeyedCodec<>(KEY_TEXT_VALUE, Codec.STRING),
                (data, value) -> data.textValue = value,
                data -> data.textValue)
            .addField(
                new KeyedCodec<>(KEY_BOOLEAN_VALUE, Codec.BOOLEAN),
                (data, value) -> data.booleanValue = value,
                data -> data.booleanValue)
            .addField(
                new KeyedCodec<>(KEY_ENUM_VALUE, Codec.STRING),
                (data, value) -> data.enumValue = value,
                data -> data.enumValue)
            .build();

    private String action;
    private String settingId;
    private String searchQuery;
    private String textValue;
    private boolean booleanValue;
    private String enumValue;
  }
}
