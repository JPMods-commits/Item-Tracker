package net.jpmods.itemtracker.test;

import com.mojang.authlib.GameProfile;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.jpmods.itemtracker.*;
import net.jpmods.itemtracker.config.ItemTrackerModMenu;
import net.jpmods.itemtracker.mixin.LivingEntityAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;

/** Runs against the production-remapped JAR and real vanilla packet handlers. */
public final class ItemTrackerGameTest implements FabricClientGameTest {
    private static int nextId = 4000000;
    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static void require(boolean result, String message) { if(!result) throw new AssertionError(message); }

    @Override public void runTest(ClientGameTestContext context) {
        try { performTest(context); }
        catch(Throwable failure) {
            try(var writer = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of("itemtracker-test-failure.txt")))) {
                failure.printStackTrace(writer);
            } catch(java.io.IOException e) { failure.addSuppressed(e); }
            throw failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
        }
    }

    private static void step(String message) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("itemtracker-test-progress.txt"), message + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch(java.io.IOException e) { throw new RuntimeException(e); }
    }

    private void performTest(ClientGameTestContext context) {
        step("Beginning production integration checks");
        context.waitFor(client -> client.screen instanceof TitleScreen);
        context.waitFor(client -> client.getOverlay() == null);
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            require(ConfigStore.apply(new Settings()), "Could not save default config");
            var parent = client.screen;
            client.setScreen(new ItemTrackerModMenu().getModConfigScreenFactory().create(parent));
        });
        context.waitTicks(2);
        context.takeScreenshot("itemtracker_potion_settings");
        context.runOnClient(client -> {
            Button dia = (Button)client.screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals("DiaSMPTracker")).findFirst().orElseThrow();
            dia.onPress(null);
        });
        context.waitTicks(2);
        context.takeScreenshot("itemtracker_diasmp_settings");
        context.runOnClient(client -> client.screen.onClose());
        step("Both Mod Menu tabs opened and closed");
        var builder = context.worldBuilder();
        builder.adjustSettings(creator -> {
            creator.setName("Item Tracker integration tests");
            creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        });
        try(var world = builder.create()) {
            step("Singleplayer world created");
            context.waitTicks(5);
            RemotePlayer alice = context.computeOnClient(client -> player("TrackerAlice", UUID.randomUUID()));
            RemotePlayer bob = context.computeOnClient(client -> player("TrackerBob", UUID.randomUUID()));
            context.runOnClient(client -> {
                client.setScreen(null);
                var p = ItemTrackerClient.potions();
                var d = ItemTrackerClient.diasmp();
                p.onUpdate();d.onUpdate();
                p.resetCounts();d.resetCounts();
                potion(alice, false, false);pearl(alice);
                potion(bob, false, false);pearl(bob);
                require(p.getCount(alice.getUUID()) == 1 && d.getCount(alice.getUUID()) == 1, "Remote throws not counted");
                potion(client.player, false, false);pearl(client.player);
                require(p.getCount(client.player.getUUID()) == 0 && d.getCount(client.player.getUUID()) == 0, "Counted local throws");
                potion(alice, false, true);
                require(p.getCount(alice.getUUID()) == 1, "Counted Healing I");
                potion(alice, true, false);
                require(p.getCount(alice.getUUID()) == 2, "Missed lingering Healing II");

                // Totem pops and damage are not deaths.
                client.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(alice, (byte)2));
                client.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(alice, (byte)35));
                require(p.getCount(alice.getUUID()) == 2 && d.getCount(alice.getUUID()) == 1, "Damage or totem reset a tracker");

                // A death event is definitive despite stale positive remote health.
                AbstractThrownPotion oldFlight = pendingPotion(alice);
                client.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(alice, (byte)3));
                require(alice.getHealth() > 0, "Fixture must hide the remote death health");
                require(p.getCount(alice.getUUID()) == 0 && d.getCount(alice.getUUID()) == 0, "Opponent death did not clear both trackers");
                require(p.getCount(bob.getUUID()) == 1 && d.getCount(bob.getUUID()) == 1, "Opponent death cleared another opponent");
                p.onUpdate();d.onUpdate();data(oldFlight);remove(oldFlight);
                potion(alice, false, false);pearl(alice);
                require(p.getCount(alice.getUUID()) == 0 && d.getCount(alice.getUUID()) == 0, "Old-life data repopulated counts");
                health(alice, 20);
                potion(alice, false, false);pearl(alice);
                require(p.getCount(alice.getUUID()) == 1 && d.getCount(alice.getUUID()) == 1, "Tracking failed to resume after revival");
                health(bob, 0);
                require(p.getCount(bob.getUUID()) == 0 && d.getCount(bob.getUUID()) == 0, "Zero health did not reset opponent");
                health(bob, 20);

                // New entity, same UUID, with no death packet between lives.
                UUID id = alice.getUUID(); remove(alice);
                RemotePlayer replacement = player("TrackerAlice", id);
                p.onUpdate();d.onUpdate();
                require(p.getCount(id) == 0 && d.getCount(id) == 0, "Immediate respawn retained old counters");
                potion(replacement, false, false);pearl(replacement);
                require(p.getCount(id) == 1 && d.getCount(id) == 1, "New life could not count");

                float hp = client.player.getHealth();
                int food = client.player.getFoodData().getFoodLevel();
                float sat = client.player.getFoodData().getSaturationLevel();
                oldFlight = pendingPotion(bob);
                client.getConnection().handleSetHealth(new ClientboundSetHealthPacket(0, food, sat));
                require(p.getCounts().isEmpty() && d.getCounts().isEmpty(), "Own death did not clear all counters");
                data(oldFlight);remove(oldFlight);
                potion(bob, false, false);pearl(bob);
                require(p.getCounts().isEmpty() && d.getCounts().isEmpty(), "Counted while locally dead");
                client.getConnection().handleSetHealth(new ClientboundSetHealthPacket(hp, food, sat));
                client.player.deathTime = 0;
                potion(bob, false, false);pearl(bob);
                require(p.getCount(bob.getUUID()) == 1 && d.getCount(bob.getUUID()) == 1, "Local revival did not resume counting");

                // Cobweb block/equipment packet hooks remain functional.
                Settings settings = ConfigStore.copy();settings.diasmp.unseenWebs = false;
                require(ConfigStore.apply(settings), "Could not apply web settings");
                client.getConnection().handleSetEquipment(new ClientboundSetEquipmentPacket(bob.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, new ItemStack(Items.COBWEB)))));
                BlockPos web = client.player.blockPosition().offset(1, 3, 1);
                client.getConnection().handleBlockUpdate(new ClientboundBlockUpdatePacket(web, Blocks.COBWEB.defaultBlockState()));
                for(int i = 0; i < 5; i++) d.onUpdate();
                require(d.getWebCount(bob.getUUID()) == 1, "Client cobweb estimate was lost during extraction");
                client.getConnection().handleBlockUpdate(new ClientboundBlockUpdatePacket(web, Blocks.COBWEB.defaultBlockState()));
                for(int i = 0; i < 5; i++) d.onUpdate();
                require(d.getWebCount(bob.getUUID()) == 1, "Cobweb duplicate counted twice");
                client.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(bob, (byte)3));
                require(d.getWebCount(bob.getUUID()) == 0 && d.getCount(bob.getUUID()) == 0, "Death did not clear pearls and webs together");
                health(bob, 20);
                potion(bob, false, false);pearl(bob);
                remove(replacement);

                List<KeyMapping> keys = Arrays.stream(client.options.keyMappings).filter(k -> k.getName().startsWith("key.itemtracker.")).toList();
                require(keys.size() == 4 && keys.stream().allMatch(KeyMapping::isUnbound), "Expected four unbound tracker keys");
                keys.stream().filter(k -> k.getName().endsWith("reset_potions")).findFirst().orElseThrow().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F7));
                keys.stream().filter(k -> k.getName().endsWith("reset_diasmp")).findFirst().orElseThrow().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
                keys.stream().filter(k -> k.getName().endsWith("toggle_potions")).findFirst().orElseThrow().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9));
                keys.stream().filter(k -> k.getName().endsWith("toggle_diasmp")).findFirst().orElseThrow().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F10));
                KeyMapping.resetMapping();
                step("Packet counters and death/respawn checks passed");
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTicks(2);
            context.runOnClient(client -> require(ItemTrackerClient.potions().getCounts().isEmpty() && !ItemTrackerClient.diasmp().getCounts().isEmpty(), "Potion reset key did not reset only potions"));
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            context.waitTicks(2);
            context.runOnClient(client -> require(ItemTrackerClient.diasmp().getCounts().isEmpty(), "DiaSMP reset key failed"));
            step("Both live reset keybinds passed");

            context.runOnClient(client -> { potion(bob, false, false);pearl(bob); });
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(!ItemTrackerClient.potions().isEnabled() && ItemTrackerClient.diasmp().isEnabled(), "Potion toggle affected the wrong tracker");
                require(!ConfigStore.current().potion.enabled && ConfigStore.current().diasmp.enabled, "Potion toggle did not update preferences");
                potion(bob, false, false);pearl(bob);
                require(ItemTrackerClient.potions().getCount(bob.getUUID()) == 1 && ItemTrackerClient.diasmp().getCount(bob.getUUID()) == 2, "Disabled potion tracker kept counting or disabled pearls");
                try {
                    var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("itemtracker.json"))).getAsJsonObject();
                    require(!saved.getAsJsonObject("potion").get("enabled").getAsBoolean(), "Toggle state was not saved to disk");
                } catch(java.io.IOException e) { throw new RuntimeException(e); }
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(!ItemTrackerClient.potions().isEnabled() && !ItemTrackerClient.diasmp().isEnabled(), "DiaSMP toggle failed to disable independently");
                potion(bob, false, false);pearl(bob);
                require(ItemTrackerClient.potions().getCount(bob.getUUID()) == 1 && ItemTrackerClient.diasmp().getCount(bob.getUUID()) == 2, "A disabled tracker kept counting");
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(ItemTrackerClient.potions().isEnabled() && !ItemTrackerClient.diasmp().isEnabled(), "Potion toggle failed to re-enable independently");
                require(ItemTrackerClient.potions().getCounts().isEmpty(), "Potion toggle ignored Reset on enable");
                potion(bob, false, false);
                require(ItemTrackerClient.potions().getCount(bob.getUUID()) == 1, "Potion counting failed after re-enabling");
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(ItemTrackerClient.potions().isEnabled() && ItemTrackerClient.diasmp().isEnabled(), "DiaSMP toggle failed to re-enable");
                require(ItemTrackerClient.diasmp().getCounts().isEmpty(), "DiaSMP toggle ignored Reset on enable");
                pearl(bob);
                require(ItemTrackerClient.diasmp().getCount(bob.getUUID()) == 1, "Pearl counting failed after re-enabling");
            });
            step("Both independent toggle keybinds and persisted states passed");

            // Exercise the respawn packet hook against a real vanilla spawn descriptor.
            CommonPlayerSpawnInfo info = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().createCommonSpawnInfo(server.overworld()));
            context.runOnClient(client -> {
                potion(bob, false, false);pearl(bob);
                client.getConnection().handleRespawn(new ClientboundRespawnPacket(info, ClientboundRespawnPacket.KEEP_ALL_DATA));
                require(ItemTrackerClient.potions().getCounts().isEmpty() && ItemTrackerClient.diasmp().getCounts().isEmpty(), "Respawn packet did not clear counters");
                remove(bob);client.setScreen(null);
            });
            context.waitTicks(2);
            context.takeScreenshot("itemtracker_hud");
        }
        context.runOnClient(client -> {
            require(ItemTrackerClient.potions().getCounts().isEmpty() && ItemTrackerClient.diasmp().getCounts().isEmpty(), "World disconnect retained counts");
            Arrays.stream(client.options.keyMappings).filter(k -> k.getName().startsWith("key.itemtracker.")).forEach(k -> k.setKey(InputConstants.UNKNOWN));
            KeyMapping.resetMapping();
        });
        System.out.println("ITEMTRACKER_INTEGRATION_TEST_PASSED: production mixins; Mod Menu tabs; HUD; Healing II, pearls, webs; own/opponent deaths; stale health; immediate respawns; delayed metadata; totem exclusions; distinct reset and toggle keybinds; toggle persistence; world cleanup");
        step("ITEMTRACKER_INTEGRATION_TEST_PASSED");
    }

    private static RemotePlayer player(String name, UUID uuid) {
        RemotePlayer player = new RemotePlayer(mc().level, new GameProfile(uuid, name));
        player.setId(nextId++);player.setPos(mc().player.position().add(0, 0, -10));
        mc().level.addEntity(player);return player;
    }
    private static ItemStack potionStack(boolean lingering, boolean weak) {
        ItemStack stack = new ItemStack(lingering ? Items.LINGERING_POTION : Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(weak ? Potions.HEALING : Potions.STRONG_HEALING));
        return stack;
    }
    private static void potion(Player owner, boolean lingering, boolean weak) {
        ItemStack stack = potionStack(lingering, weak);
        AbstractThrownPotion potion = lingering ? new ThrownLingeringPotion(mc().level, owner, stack) : new ThrownSplashPotion(mc().level, owner, stack);
        potion.setId(nextId++);potion.setPos(owner.position());spawn(potion, owner.getId());
        data(potion);data(potion);remove(potion);
    }
    private static AbstractThrownPotion pendingPotion(Player owner) {
        AbstractThrownPotion potion = new ThrownSplashPotion(mc().level, owner, potionStack(false, false));
        potion.setId(nextId++);potion.setPos(owner.position());spawn(potion, owner.getId());return potion;
    }
    private static void pearl(Player owner) {
        ThrownEnderpearl pearl = new ThrownEnderpearl(mc().level, owner, new ItemStack(Items.ENDER_PEARL));
        pearl.setId(nextId++);pearl.setPos(owner.position());spawn(pearl, owner.getId());remove(pearl);
    }
    private static void spawn(Entity entity, int owner) {
        mc().getConnection().handleAddEntity(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(), 0, 0, entity.getType(), owner, Vec3.ZERO, 0));
    }
    private static void data(Entity entity) {
        mc().getConnection().handleSetEntityData(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().getNonDefaultValues()));
    }
    private static void remove(Entity entity) { mc().getConnection().handleRemoveEntities(new ClientboundRemoveEntitiesPacket(entity.getId())); }
    private static void health(Player player, float hp) {
        mc().getConnection().handleSetEntityData(new ClientboundSetEntityDataPacket(player.getId(), List.of(SynchedEntityData.DataValue.create(LivingEntityAccessor.getHealthDataAccessor(), hp))));
    }
}
