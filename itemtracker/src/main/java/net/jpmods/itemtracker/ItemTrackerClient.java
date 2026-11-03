package net.jpmods.itemtracker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.jpmods.itemtracker.diasmp.DiaSMPProtocol;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ItemTrackerClient implements ClientModInitializer {
    private static PotionTracker potions;
    private static DiaSMPTracker diasmp;
    private static KeyMapping resetPotions;
    private static KeyMapping resetDiaSMP;
    private static KeyMapping togglePotions;
    private static KeyMapping toggleDiaSMP;

    public static PotionTracker potions() { return potions; }
    public static DiaSMPTracker diasmp() { return diasmp; }

    @Override public void onInitializeClient() {
        Settings settings = ConfigStore.current();
        potions = new PotionTracker(settings.potion);
        diasmp = new DiaSMPTracker(settings.diasmp);
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("itemtracker", "controls"));
        resetPotions = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.itemtracker.reset_potions", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        resetDiaSMP = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.itemtracker.reset_diasmp", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        togglePotions = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.itemtracker.toggle_potions", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        toggleDiaSMP = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.itemtracker.toggle_diasmp", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            potions.onUpdate();
            diasmp.onUpdate();
            while(resetPotions.consumeClick()) {
                if(client.player == null || client.screen != null) continue;
                potions.resetCounts();
                client.player.displayClientMessage(Component.translatable("itemtracker.potions_reset"), true);
            }
            while(resetDiaSMP.consumeClick()) {
                if(client.player == null || client.screen != null) continue;
                diasmp.resetCounts();
                client.player.displayClientMessage(Component.translatable("itemtracker.diasmp_reset"), true);
            }
            while(togglePotions.consumeClick()) {
                if(client.player == null || client.screen != null) continue;
                toggle(true, client.player);
            }
            while(toggleDiaSMP.consumeClick()) {
                if(client.player == null || client.screen != null) continue;
                toggle(false, client.player);
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("itemtracker", "counters"), (graphics, delta) -> {
            potions.onRenderGUI(graphics, 0);
            diasmp.onRenderGUI(graphics, 0);
        });

        // Retain the extracted tracker's optional confirmed-cobweb protocol.
        DiaSMPProtocol.register();
        ClientPlayConnectionEvents.INIT.register((handler, client) -> diasmp.disconnectServer());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            diasmp.disconnectServer();
            potions.resetCounts();
        });
        ClientPlayNetworking.registerGlobalReceiver(DiaSMPProtocol.Ready.TYPE, (payload, context) -> diasmp.observeServerReady(payload));
        ClientPlayNetworking.registerGlobalReceiver(DiaSMPProtocol.WebPlacement.TYPE, (payload, context) -> diasmp.observeConfirmedWeb(payload));
    }

    private static void toggle(boolean potion, LocalPlayer player) {
        Settings settings = ConfigStore.copy();
        Settings.Hud tracker = potion ? settings.potion : settings.diasmp;
        tracker.enabled = !tracker.enabled;
        if(!ConfigStore.apply(settings)) {
            player.displayClientMessage(Component.translatable("itemtracker.save_failed"), true);
            return;
        }
        String key = "itemtracker." + (potion ? "potions" : "diasmp")
            + (tracker.enabled ? "_enabled" : "_disabled");
        player.displayClientMessage(Component.translatable(key), true);
    }
}
