/* PotionTracker packet hooks, 2026-10-29. GPL-3.0-or-later. */
package net.jpmods.itemtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.Entity;
import net.jpmods.itemtracker.ItemTrackerClient;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin
{
    // TAIL runs on the client thread, after vanilla has spawned the entity or
    // applied its item stack. This also catches spawn/data/remove sequences
    // delivered in one tick, independent of frustum culling and line of sight.
    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void afterPotionSpawn(ClientboundAddEntityPacket packet, CallbackInfo ci)
    {
        if(net.minecraft.client.Minecraft.getInstance().level != null)
        {
            Entity entity = net.minecraft.client.Minecraft.getInstance().level.getEntity(packet.getId());
            var tracker = ItemTrackerClient.potions();
            tracker.observePlayerSpawn(entity);
            tracker.observeEntity(entity, packet.getData());
            var dia = ItemTrackerClient.diasmp();
            dia.observePlayerSpawn(entity);
            dia.observeEntity(entity, packet.getData());
        }
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void afterPotionItemData(ClientboundSetEntityDataPacket packet, CallbackInfo ci)
    {
        if(net.minecraft.client.Minecraft.getInstance().level != null)
        {
            Entity entity = net.minecraft.client.Minecraft.getInstance().level.getEntity(packet.id());
            var tracker = ItemTrackerClient.potions();
            int healthId = LivingEntityAccessor.getHealthDataAccessor().id();
            if(packet.packedItems().stream().anyMatch(value -> value.id() == healthId))
                tracker.observePlayerHealth(entity);
            tracker.observeEntity(entity, 0);
            var dia = ItemTrackerClient.diasmp();
            if(packet.packedItems().stream().anyMatch(value -> value.id() == healthId))
                dia.observePlayerHealth(entity);
            dia.observeEntity(entity, 0);
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void afterPlayerDeathEvent(ClientboundEntityEventPacket packet, CallbackInfo ci)
    {
        if(packet.getEventId() == 3 && net.minecraft.client.Minecraft.getInstance().level != null)
        {
            ItemTrackerClient.potions().observePlayerDeath(
                packet.getEntity(net.minecraft.client.Minecraft.getInstance().level));
            ItemTrackerClient.diasmp().observePlayerDeath(
                packet.getEntity(net.minecraft.client.Minecraft.getInstance().level));
        }
    }

    @Inject(method = "handleSetHealth", at = @At("TAIL"))
    private void afterOwnHealth(ClientboundSetHealthPacket packet, CallbackInfo ci)
    {
        ItemTrackerClient.potions().observePlayerHealth(net.minecraft.client.Minecraft.getInstance().player);
        ItemTrackerClient.diasmp().observePlayerHealth(net.minecraft.client.Minecraft.getInstance().player);
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
    private void afterCombatDeath(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci)
    {
        if(net.minecraft.client.Minecraft.getInstance().level != null)
        {
            ItemTrackerClient.potions().observePlayerDeath(
                net.minecraft.client.Minecraft.getInstance().level.getEntity(packet.playerId()));
            ItemTrackerClient.diasmp().observePlayerDeath(
                net.minecraft.client.Minecraft.getInstance().level.getEntity(packet.playerId()));
        }
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void afterRespawn(ClientboundRespawnPacket packet, CallbackInfo ci)
    {
        // Respawn is definitive even when a server omits the preceding death
        // status or sends health and respawn together within one client tick.
        ItemTrackerClient.potions().resetCounts();
        ItemTrackerClient.diasmp().resetCounts();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        ItemTrackerClient.potions().observePlayerSpawn(player);
        ItemTrackerClient.diasmp().observePlayerSpawn(player);
    }
}
