package net.jpmods.itemtracker.config;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.jpmods.itemtracker.ConfigStore;
import net.jpmods.itemtracker.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Standard scrollable Minecraft options, opened solely from Mod Menu. */
public final class ItemTrackerConfigScreen extends OptionsSubScreen {
    private Settings draft;
    private boolean dia;
    private boolean saveFailed;

    public ItemTrackerConfigScreen(Screen parent) {
        this(parent, ConfigStore.copy(), false);
    }

    private ItemTrackerConfigScreen(Screen parent, Settings settings, boolean selectedDia) {
        super(parent, Minecraft.getInstance().options, Component.literal("Item Tracker"));
        draft = settings;
        dia = selectedDia;
        layout.setHeaderHeight(70);
        layout.setFooterHeight(56);
    }

    @Override protected void addTitle() {
        LinearLayout header = LinearLayout.vertical().spacing(7);
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new net.minecraft.client.gui.components.StringWidget(title, font));
        LinearLayout tabs = LinearLayout.horizontal().spacing(8);
        Button potion = tabs.addChild(Button.builder(Component.literal("PotionTracker"), button -> select(false)).width(151).build());
        Button diasmp = tabs.addChild(Button.builder(Component.literal("DiaSMPTracker"), button -> select(true)).width(151).build());
        potion.active = dia;
        diasmp.active = !dia;
        header.addChild(tabs);
        layout.addToHeader(header);
    }

    private void select(boolean value) {
        if(list != null) list.applyUnsavedChanges();
        minecraft.setScreen(new ItemTrackerConfigScreen(lastScreen, draft, value));
    }

    @Override protected void addOptions() {
        Settings.Hud hud = dia ? draft.diasmp : draft.potion;
        bool("enabled", "Enable this tracker. Deaths, respawns and world changes automatically reset its counters.", hud.enabled, v -> hud.enabled = v);
        if(dia) {
            Settings.DiaSMP d = draft.diasmp;
            integer("pearl_range", "Maximum horizontal distance for received ender-pearl throws, in blocks.", (int)d.range, 1, 64, v -> d.range = v);
            integer("web_range", "Maximum distance to the player and cobweb, in blocks.", (int)d.webRange, 1, 94, v -> d.webRange = v);
            bool("client_estimates", "Estimate cobweb use from block, held-item and inventory updates. Client estimates cannot prove ownership.", d.clientWebEstimates, v -> d.clientWebEstimates = v);
            bool("unseen_webs", "Attribute newly discovered webs to the sole nearby opponent. Pre-existing arena webs can inflate this estimate.", d.unseenWebs, v -> d.unseenWebs = v);
            integer("timing_grace", "Ticks allowed between block, held-item and inventory updates.", d.timingGrace, 0, 10, v -> d.timingGrace = v);
            list.addHeader(Component.translatable("itemtracker.option.opponent"));
            EditBox opponent = new EditBox(font, 0, 0, 310, 20, Component.translatable("itemtracker.option.opponent"));
            opponent.setMaxLength(16);
            opponent.setFilter(v -> v.matches("[A-Za-z0-9_]{0,16}"));
            opponent.setValue(d.webOpponent);
            opponent.setHint(Component.translatable("itemtracker.auto_opponent"));
            opponent.setResponder(v -> d.webOpponent = v);
            opponent.setTooltip(Tooltip.create(Component.literal("Optional username for cobweb estimates. Blank selects the sole nearby opponent automatically.")));
            list.addSmall(opponent, (net.minecraft.client.gui.components.AbstractWidget)null);
        } else {
            Settings.Potion p = draft.potion;
            integer("potion_range", "Maximum horizontal distance for received Healing II throws, in blocks.", (int)p.range, 1, 64, v -> p.range = v);
            bool("lingering", "Also count thrown Lingering Potions of Healing II.", p.countLingering, v -> p.countLingering = v);
        }
        bool("reset_on_enable", "Start fresh when re-enabling this tracker. Automatic death resets always remain active.", hud.resetOnEnable, v -> hud.resetOnEnable = v);
        list.addHeader(Component.translatable("itemtracker.hud"));
        bool("show_hud", "Display item icons and counters using your active resource pack.", hud.showHud, v -> hud.showHud = v);
        bool("zero_counts", "Include nearby opponents with no recorded uses.", hud.showZeroCounts, v -> hud.showZeroCounts = v);
        bool("player_names", "Show the name of each opponent.", hud.showPlayerNames, v -> hud.showPlayerNames = v);
        integer("hud_x", "Horizontal HUD position, as a percentage of available screen space.", (int)hud.hudX, 0, 100, v -> hud.hudX = v);
        integer("hud_y", "Vertical HUD position, as a percentage of available screen space.", (int)hud.hudY, 0, 100, v -> hud.hudY = v);
        integer("hud_scale", "HUD scale: 50% to 300%, in 5% steps.", (int)Math.round(hud.hudScale * 20), 10, 60, v -> hud.hudScale = v / 20.0, v -> (v * 5) + "%");
        integer("max_rows", "Maximum opponents shown, sorted by their recorded use totals.", hud.maxRows, 1, 30, v -> hud.maxRows = v);
        bool("background", "Draw a dark background behind the counters.", hud.background, v -> hud.background = v);
        bool("shadow", "Draw a shadow behind counter text.", hud.textShadow, v -> hud.textShadow = v);
        list.addHeader(Component.translatable("itemtracker.option.text_color"));
        EditBox color = new EditBox(font, 0, 0, 310, 20, Component.translatable("itemtracker.option.text_color"));
        color.setMaxLength(7);
        color.setFilter(v -> v.matches("#?[0-9a-fA-F]{0,6}"));
        color.setValue(String.format(Locale.ROOT, "#%06X", hud.textColor));
        color.setResponder(v -> {
            String raw = v.startsWith("#") ? v.substring(1) : v;
            if(raw.length() == 6) {
                hud.textColor = Integer.parseInt(raw, 16);
                color.setTextColor(0xFFE0E0E0);
            } else color.setTextColor(0xFFFF7777);
        });
        color.setTooltip(Tooltip.create(Component.literal("Six-digit RGB colour, for example #FFFFFF for white.")));
        list.addSmall(color, (net.minecraft.client.gui.components.AbstractWidget)null);
    }

    private void bool(String id, String tooltip, boolean value, Consumer<Boolean> setter) {
        list.addBig(OptionInstance.createBoolean("itemtracker.option." + id,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)), value, setter));
    }

    private void integer(String id, String tooltip, int value, int min, int max, IntConsumer setter) {
        integer(id, tooltip, value, min, max, setter, Object::toString);
    }

    private void integer(String id, String tooltip, int value, int min, int max, IntConsumer setter, java.util.function.Function<Integer, String> format) {
        list.addBig(new OptionInstance<Integer>("itemtracker.option." + id,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            (caption, v) -> Component.literal(caption.getString() + ": " + format.apply(v)),
            new OptionInstance.IntRange(min, max, true), value, v -> setter.accept(v)));
    }

    @Override protected void addFooter() {
        LinearLayout footer = LinearLayout.vertical().spacing(5);
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new net.minecraft.client.gui.components.StringWidget(Component.translatable("itemtracker.controls_hint"), font));
        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        buttons.addChild(Button.builder(Component.translatable("itemtracker.defaults"), b -> {
            if(dia) draft.diasmp = new Settings.DiaSMP(); else draft.potion = new Settings.Potion();
            minecraft.setScreen(new ItemTrackerConfigScreen(lastScreen, draft, dia));
        }).width(151).build());
        buttons.addChild(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(151).build());
        footer.addChild(buttons);
        layout.addToFooter(footer);
    }

    @Override public void onClose() {
        if(list != null) list.applyUnsavedChanges();
        if(!ConfigStore.apply(draft)) { saveFailed = true; return; }
        minecraft.setScreen(lastScreen);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        if(saveFailed) graphics.drawCenteredString(font, Component.translatable("itemtracker.save_failed"), width / 2, height - 68, 0xFFFF7777);
    }
}
