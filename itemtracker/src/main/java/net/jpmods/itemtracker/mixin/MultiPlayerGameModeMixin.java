/* Remember our own predicted web coordinates. GPL-3.0-or-later. */
package net.jpmods.itemtracker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.jpmods.itemtracker.ItemTrackerClient;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin
{
    @WrapMethod(method = "useItemOn")
    private InteractionResult rememberOwnWeb(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
        Operation<InteractionResult> original)
    {
        var tracker = ItemTrackerClient.diasmp();
        var stack = player.getItemInHand(hand);
        boolean holdingWeb = stack.is(Items.COBWEB);
        var pos = new BlockPlaceContext(player, hand, stack, hit).getClickedPos().immutable();
        boolean wasWeb = player.level().getBlockState(pos).is(Blocks.COBWEB);
        tracker.observeWebContext();
        InteractionResult result = original.call(player, hand, hit);
        if(holdingWeb && !wasWeb && result.consumesAction() && player.level().getBlockState(pos).is(Blocks.COBWEB))
            tracker.observeOwnWeb(pos);
        else tracker.observeWebContext();
        return result;
    }
}
