/* DiaSMPTracker confirmed-placement protocol, 2026-10-30. GPL-3.0-or-later. */
package net.jpmods.itemtracker.diasmp;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Shared by the client and the optional server companion; no client classes. */
public final class DiaSMPProtocol
{
    public static final int VERSION = 1;
    private static boolean registered;
    private DiaSMPProtocol() {}

    public static synchronized void register()
    {
        if(registered) return;
        PayloadTypeRegistry.playS2C().register(Ready.TYPE, Ready.CODEC);
        PayloadTypeRegistry.playS2C().register(WebPlacement.TYPE, WebPlacement.CODEC);
        registered = true;
    }

    public record Ready(int version, UUID session) implements CustomPacketPayload
    {
        public static final Type<Ready> TYPE = new Type<>(Identifier.fromNamespaceAndPath("diasmptracker", "ready_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ready> CODEC = new StreamCodec<>()
        {
            public Ready decode(RegistryFriendlyByteBuf buf) { return new Ready(buf.readVarInt(), buf.readUUID()); }
            public void encode(RegistryFriendlyByteBuf buf, Ready value)
            {
                buf.writeVarInt(value.version());
                buf.writeUUID(value.session());
            }
        };
        @Override public Type<Ready> type() { return TYPE; }
    }

    /** Sent only after a real player's successful server-side BlockItem placement. */
    public record WebPlacement(UUID session, long sequence, Identifier dimension,
        UUID player, int entityId, Vec3 playerPosition, BlockPos position) implements CustomPacketPayload
    {
        public static final Type<WebPlacement> TYPE = new Type<>(Identifier.fromNamespaceAndPath("diasmptracker", "web_placement_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WebPlacement> CODEC = new StreamCodec<>()
        {
            public WebPlacement decode(RegistryFriendlyByteBuf buf)
            {
                return new WebPlacement(buf.readUUID(), buf.readVarLong(), buf.readIdentifier(),
                    buf.readUUID(), buf.readVarInt(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    buf.readBlockPos());
            }
            public void encode(RegistryFriendlyByteBuf buf, WebPlacement value)
            {
                buf.writeUUID(value.session());
                buf.writeVarLong(value.sequence());
                buf.writeIdentifier(value.dimension());
                buf.writeUUID(value.player());
                buf.writeVarInt(value.entityId());
                buf.writeDouble(value.playerPosition().x);
                buf.writeDouble(value.playerPosition().y);
                buf.writeDouble(value.playerPosition().z);
                buf.writeBlockPos(value.position());
            }
        };
        @Override public Type<WebPlacement> type() { return TYPE; }
    }
}
