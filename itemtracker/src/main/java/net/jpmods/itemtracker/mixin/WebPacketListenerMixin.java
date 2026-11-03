/* Client-side web evidence, 2026-10-30. GPL-3.0-or-later. */
package net.jpmods.itemtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.jpmods.itemtracker.ItemTrackerClient;

@Mixin(ClientPacketListener.class)
public abstract class WebPacketListenerMixin
{
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void afterWebUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci)
    {
        ItemTrackerClient.diasmp().observeWebBlock(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void afterWebSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci)
    {
        packet.runUpdates((pos, state) -> ItemTrackerClient.diasmp().observeWebBlock(pos, state));
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void afterWebChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci)
    {
        ItemTrackerClient.diasmp().observeWebChunk(packet.getX(), packet.getZ());
    }

    @Inject(method = {"handleSetEquipment", "handleSetPlayerInventory", "handleContainerSetSlot", "handleContainerContent", "handleSetCursorItem"}, at = @At("HEAD"))
    private void beforeWebInventoryOrHands(CallbackInfo ci)
    {
        // PacketUtils may initially enter a handler on Netty before scheduling
        // it again on the client. Only the client-thread invocation is sampled.
        if(net.minecraft.client.Minecraft.getInstance() != null && net.minecraft.client.Minecraft.getInstance().isSameThread())
            ItemTrackerClient.diasmp().observeWebContext();
    }

    @Inject(method = {"handleSetEquipment", "handleSetPlayerInventory", "handleContainerSetSlot", "handleContainerContent", "handleSetCursorItem"}, at = @At("TAIL"))
    private void afterWebInventoryOrHands(CallbackInfo ci)
    {
        ItemTrackerClient.diasmp().observeWebContext();
    }
}
