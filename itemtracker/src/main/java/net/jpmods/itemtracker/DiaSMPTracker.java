/*
 * DiaSMPTracker for the custom client, 2026-10-30.
 * Distributed under the GNU General Public License, version 3 or later.
 */
package net.jpmods.itemtracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.jpmods.itemtracker.diasmp.DiaSMPProtocol;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.jpmods.itemtracker.diasmp.ClientWebTracker;

public final class DiaSMPTracker
{
    private static final int MAX_SEEN_PEARLS = 65536;
    private static final int MAX_PENDING_PEARLS = 2048;
    private static final int MAX_PLAYERS = 1024;

    private static final net.minecraft.client.Minecraft MC = net.minecraft.client.Minecraft.getInstance();
    private Settings.DiaSMP options;
    private final LinkedHashMap<UUID, Player> knownPlayers = new LinkedHashMap<>();
    private final ItemStack pearlIcon = new ItemStack(Items.ENDER_PEARL);

    private final ItemStack webIcon = new ItemStack(Items.COBWEB);
    private final ClientWebTracker webTracker = new ClientWebTracker(this);
    private UUID serverSession;
    private long lastWebSequence;

    private final LinkedHashMap<UUID, PlayerCount> counts = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> seenPearls = new LinkedHashSet<>();
    private final LinkedHashMap<UUID, PendingPearl> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Player> deadPlayers = new LinkedHashMap<>();
    private ClientLevel currentWorld;
    private long ticks;

    public DiaSMPTracker(Settings.DiaSMP options)
    {
        this.options = options;
    }

    public boolean isEnabled() { return options.enabled; }

    public void applyOptions(Settings.DiaSMP value)
    {
        boolean wasEnabled = isEnabled();
        options = value;
        if(!wasEnabled && isEnabled()) onEnable();
        else if(wasEnabled && !isEnabled()) onDisable();
    }

    protected void onEnable()
    {
        syncWorld();
        if(options.resetOnEnable) counts.clear();
        pending.clear();
        ignoreExistingPearls();
        webTracker.enable();
    }

    protected void onDisable()
    {
        pending.clear();
        webTracker.clearPending();
    }

    private boolean syncWorld()
    {
        if(currentWorld != MC.level)
        {
            currentWorld = MC.level;
            counts.clear();
            seenPearls.clear();
            pending.clear();
            deadPlayers.clear();
            knownPlayers.clear();
            ticks = 0;
            webTracker.worldChanged();
        }
        return currentWorld != null && MC.player != null;
    }

    /** Invoked after vanilla applies a projectile spawn packet, even off screen. */
    public void observeEntity(Entity entity, int ownerId)
    {
        if(!isEnabled() || !syncWorld() || !(entity instanceof ThrownEnderpearl pearl)
            || seenPearls.contains(pearl.getUUID())) return;
        refreshLocalLife();
        if(localPlayerDead())
        {
            remember(pearl.getUUID());
            return;
        }
        PendingPearl candidate = pending.get(pearl.getUUID());
        if(candidate == null)
        {
            candidate = new PendingPearl(pearl, ownerId, pearl.position(), ticks);
            pending.put(pearl.getUUID(), candidate);
        }
        else if(ownerId > 0) candidate.ownerId = ownerId;
        if(finish(candidate)) pending.remove(pearl.getUUID());
        while(pending.size() > MAX_PENDING_PEARLS)
        {
            UUID oldest = pending.keySet().iterator().next();
            remember(oldest);
            pending.remove(oldest);
        }
    }

    private boolean finish(PendingPearl candidate)
    {
        ThrownEnderpearl pearl = candidate.pearl;
        if(!withinRange(candidate.spawnPosition)) return done(pearl);

        Entity owner = pearl.getOwner();
        if(owner == null && candidate.ownerId > 0) owner = currentWorld.getEntity(candidate.ownerId);
        for(Player dead : deadPlayers.values())
            if(candidate.ownerId > 0 && candidate.ownerId == dead.getId()) return done(pearl);
        if(owner == null) return false; // Never guess ownership from proximity or hand changes.
        if(!(owner instanceof Player player) || isSelf(player) || !withinRange(player.position())
            || player.isDeadOrDying() || deadPlayers.containsKey(player.getUUID()))
            return done(pearl);

        addCount(player, false);
        return done(pearl);
    }

    private void addCount(Player player, boolean web)
    {
        observePlayerSpawn(player);
        PlayerCount previous = counts.get(player.getUUID());
        int pearls = previous == null ? 0 : previous.pearls();
        int webs = previous == null ? 0 : previous.webs();
        if(web) webs = webs == Integer.MAX_VALUE ? webs : webs + 1;
        else pearls = pearls == Integer.MAX_VALUE ? pearls : pearls + 1;
        counts.put(player.getUUID(), new PlayerCount(player.getUUID(), player.getScoreboardName(), pearls, webs));
        while(counts.size() > MAX_PLAYERS) counts.remove(counts.keySet().iterator().next());
    }

    public void observeServerReady(DiaSMPProtocol.Ready payload)
    {
        if(payload.version() != DiaSMPProtocol.VERSION) return;
        if(!payload.session().equals(serverSession))
        {
            serverSession = payload.session();
            lastWebSequence = 0;
            resetCounts();
        }
    }

    public void disconnectServer()
    {
        serverSession = null;
        lastWebSequence = 0;
        resetCounts();
    }

    public boolean hasConfirmedWebSupport() { return serverSession != null; }

    public String getWebStatus()
    {
        return hasConfirmedWebSupport() ? "Server-confirmed cobweb tracking connected (maximum 94 blocks)."
            : options.clientWebEstimates ? "Client cobweb estimates selected (maximum 94 blocks): held items, own inventory changes and coordinate history. Newly discovered webs assume the opponent placed them. Exact server ticks/ownership are not available."
            : "Cobweb tracking unavailable: enable Client web estimates or use the optional server companion.";
    }

    public boolean hasWebTracking() { return options.clientWebEstimates || hasConfirmedWebSupport(); }
    public boolean canEstimateWebs()
    {
        return isEnabled() && options.clientWebEstimates && !hasConfirmedWebSupport()
            && MC.player != null && currentWorld != null && currentWorld == MC.level && !localPlayerDead();
    }
    public int webTimingGrace() { return options.timingGrace; }
    public boolean countUnseenWebs() { return options.unseenWebs; }
    public boolean inWebRange(Vec3 position) { return MC.player != null && withinWebRange(position); }
    public boolean isWebOpponent(Player player)
    {
        return MC.player != null && !isSelf(player) && !player.isDeadOrDying()
            && !deadPlayers.containsKey(player.getUUID()) && withinWebRange(player.position())
            && (options.webOpponent.isEmpty() || options.webOpponent.equalsIgnoreCase(player.getScoreboardName()));
    }
    public void addEstimatedWeb(Player player)
    {
        if(canEstimateWebs() && isWebOpponent(player)) addCount(player, true);
    }
    public void observeWebContext()
    {
        if(syncWorld()) webTracker.captureContext();
    }
    public void observeWebBlock(BlockPos pos, BlockState state)
    {
        if(!syncWorld()) return;
        refreshLocalLife();
        webTracker.blockUpdate(pos, state);
    }
    public void observeOwnWeb(BlockPos pos)
    {
        if(syncWorld()) webTracker.ownPlacement(pos);
    }
    public void observeWebChunk(int x, int z)
    {
        if(syncWorld()) webTracker.queueChunk(x, z);
    }

    public void observeConfirmedWeb(DiaSMPProtocol.WebPlacement payload)
    {
        if(serverSession == null || !serverSession.equals(payload.session())
            || payload.sequence() <= lastWebSequence || payload.sequence() <= 0) return;
        // Advance even while paused or dead. Replays must not populate a new round.
        lastWebSequence = payload.sequence();
        if(!isEnabled() || !syncWorld()
            || !payload.dimension().equals(currentWorld.dimension().identifier())) return;
        refreshLocalLife();
        if(localPlayerDead()) return;
        Entity entity = currentWorld.getEntity(payload.entityId());
        if(!(entity instanceof Player player) || !player.getUUID().equals(payload.player())
            || isSelf(player) || player.isDeadOrDying() || deadPlayers.containsKey(player.getUUID())) return;
        if(!withinWebRange(payload.playerPosition())
            || !withinWebRange(Vec3.atCenterOf(payload.position()))) return;
        addCount(player, true);
        webTracker.confirmedPlacement(payload.position());
    }

    private boolean withinWebRange(Vec3 position)
    {
        return position.distanceToSqr(MC.player.position()) <= (options.webRange * options.webRange) + 1e-7;
    }

    private boolean withinHudRange(Vec3 position)
    {
        return withinRange(position) || (hasWebTracking() && withinWebRange(position));
    }

    public int getWebCount(UUID player)
    {
        syncWorld();
        PlayerCount count = counts.get(player);
        return count == null ? 0 : count.webs();
    }

    private String webText(PlayerCount row)
    {
        return "- " + (hasWebTracking() ? Integer.toString(row.webs()) : "?");
    }

    private boolean done(ThrownEnderpearl pearl)
    {
        remember(pearl.getUUID());
        return true;
    }

    private void remember(UUID id)
    {
        seenPearls.add(id);
        if(seenPearls.size() > MAX_SEEN_PEARLS) seenPearls.remove(seenPearls.iterator().next());
    }

    private boolean isSelf(Player player)
    {
        return player == MC.player || player.getUUID().equals(MC.player.getUUID())
            || player.getGameProfile().id().equals(MC.player.getGameProfile().id());
    }

    private boolean withinRange(Vec3 position)
    {
        return position.subtract(MC.player.position()).horizontalDistanceSqr() <= (options.range * options.range) + 1e-7;
    }

    private void ignoreExistingPearls()
    {
        if(currentWorld == null) return;
        for(Entity entity : currentWorld.entitiesForRendering())
            if(entity instanceof ThrownEnderpearl) remember(entity.getUUID());
    }

    public void onUpdate()
    {
        if(!syncWorld() || !isEnabled()) return;
        refreshLocalLife();
        for(Player player : currentWorld.players())
        {
            observePlayerSpawn(player);
            if(player.isDeadOrDying()) observePlayerDeath(player);
        }
        if(localPlayerDead()) return;
        webTracker.tick();
        ticks++;
        var iterator = pending.values().iterator();
        while(iterator.hasNext())
        {
            PendingPearl candidate = iterator.next();
            if(finish(candidate) || ticks - candidate.firstTick >= 100)
            {
                remember(candidate.pearl.getUUID());
                iterator.remove();
            }
        }
    }

    /** Death status packets are needed even if a server hides remote health. */
    public void observePlayerDeath(Entity entity)
    {
        if(!syncWorld() || !(entity instanceof Player player)
            || (isSelf(player) && player != MC.player)) return;
        if(deadPlayers.get(player.getUUID()) == player) return;
        knownPlayers.put(player.getUUID(), player);
        deadPlayers.put(player.getUUID(), player);
        while(deadPlayers.size() > MAX_PLAYERS)
            deadPlayers.remove(deadPlayers.keySet().iterator().next());
        if(player == MC.player) resetCounts();
        else
        {
            counts.remove(player.getUUID());
            ignorePlayerPearls(player);
            webTracker.clearPending();
        }
    }

    /** Only explicit health updates revive an existing entity. A death event
     * does not set a remote player's displayed health to zero in vanilla. */
    public void observePlayerHealth(Entity entity)
    {
        if(!syncWorld() || !(entity instanceof Player player)
            || (isSelf(player) && player != MC.player)) return;
        if(player.isDeadOrDying()) observePlayerDeath(player);
        else if(deadPlayers.remove(player.getUUID()) != null)
        {
            if(player == MC.player) ignoreExistingPearls();
            else ignorePlayerPearls(player);
        }
    }

    public void observePlayerSpawn(Entity entity)
    {
        if(!syncWorld() || !(entity instanceof Player player)
            || (isSelf(player) && player != MC.player)) return;
        Player previous = knownPlayers.put(player.getUUID(), player);
        if(previous != null && previous != player)
        {
            // A new client player entity starts a new observed life. This also
            // handles fast server respawns that omit health/death metadata.
            if(isSelf(player)) resetCounts();
            else
            {
                counts.remove(player.getUUID());
                ignorePlayerPearls(previous);
                webTracker.clearPending();
            }
            deadPlayers.remove(player.getUUID());
        }
        while(knownPlayers.size() > MAX_PLAYERS)
            knownPlayers.remove(knownPlayers.keySet().iterator().next());
    }

    private void refreshLocalLife()
    {
        observePlayerSpawn(MC.player); // Respawn can replace the local player without a spawn packet.
        if(MC.player.isDeadOrDying()) observePlayerDeath(MC.player);
    }

    private boolean localPlayerDead()
    {
        return MC.player.isDeadOrDying() || deadPlayers.get(MC.player.getUUID()) == MC.player;
    }

    private void ignorePlayerPearls(Player player)
    {
        var iterator = pending.entrySet().iterator();
        while(iterator.hasNext())
        {
            var entry = iterator.next();
            PendingPearl candidate = entry.getValue();
            Entity owner = candidate.pearl.getOwner();
            if(candidate.ownerId == player.getId()
                || (owner != null && owner.getUUID().equals(player.getUUID())))
            {
                remember(entry.getKey());
                iterator.remove();
            }
        }
        for(Entity entity : currentWorld.entitiesForRendering())
            if(entity instanceof ThrownEnderpearl pearl)
            {
                Entity owner = pearl.getOwner();
                if(owner != null && owner.getUUID().equals(player.getUUID())) remember(pearl.getUUID());
            }
    }

    public void resetCounts()
    {
        syncWorld();
        counts.clear();
        for(UUID id : pending.keySet()) remember(id);
        pending.clear();
        ignoreExistingPearls();
        webTracker.clearPending();
    }

    public int getCount(UUID player)
    {
        syncWorld();
        PlayerCount count = counts.get(player);
        return count == null ? 0 : count.pearls();
    }

    public List<PlayerCount> getCounts()
    {
        syncWorld();
        return List.copyOf(counts.values());
    }

    public List<PlayerCount> getHudRows()
    {
        if(!syncWorld()) return List.of();
        List<Player> players = new ArrayList<>();
        for(Player player : currentWorld.players())
            if(!isSelf(player) && !player.isDeadOrDying() && !deadPlayers.containsKey(player.getUUID()) && withinHudRange(player.position())
                && (options.showZeroCounts || getCount(player.getUUID()) > 0
                    || getWebCount(player.getUUID()) > 0)) players.add(player);
        players.sort(Comparator.<Player>comparingLong(p -> (long)getCount(p.getUUID()) + getWebCount(p.getUUID())).reversed()
            .thenComparing(Player::getScoreboardName, String.CASE_INSENSITIVE_ORDER));
        List<PlayerCount> rows = new ArrayList<>();
        for(Player player : players)
        {
            if(rows.size() >= options.maxRows) break;
            rows.add(new PlayerCount(player.getUUID(), player.getScoreboardName(), getCount(player.getUUID()), getWebCount(player.getUUID())));
        }
        return List.copyOf(rows);
    }

    public void onRenderGUI(GuiGraphics context, float partialTicks)
    {
        if(!isEnabled() || !options.showHud || !syncWorld()) return;
        List<PlayerCount> rows = getHudRows();
        if(rows.isEmpty()) rows = List.of(new PlayerCount(new UUID(0, 0), "", 0, 0));
        int width = 0;
        int height = 8 + Math.max(0, rows.size() - 1) * 4;
        for(PlayerCount row : rows)
        {
            int rowWidth = 20 + Math.max(MC.font.width("- " + row.pearls()), MC.font.width(webText(row)));
            height += 36;
            if(options.showPlayerNames && !row.name().isEmpty())
            {
                rowWidth = Math.max(rowWidth, MC.font.width(row.name() + ":"));
                height += 12;
            }
            width = Math.max(width, rowWidth);
        }
        width += 8;
        float scale = (float)options.hudScale;
        int x = Math.max(0, Math.round((context.guiWidth() / scale - width) * (float)options.hudX / 100));
        int y = Math.max(0, Math.round((context.guiHeight() / scale - height) * (float)options.hudY / 100));
        var pose = context.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        if(options.background) context.fill(x, y, x + width, y + height, 0x99000000);
        int rowY = y + 4;
        for(PlayerCount row : rows)
        {
            int rowX = x + 4;
            if(options.showPlayerNames && !row.name().isEmpty())
            {
                String name = row.name() + ":";
                context.drawString(MC.font, name, rowX, rowY + 1, (0xFF000000 | options.textColor), options.textShadow);
                rowY += 12;
            }
            // Both icons use the current resource-pack models and textures.
            context.renderItem(pearlIcon, rowX, rowY);
            String pearlText = "- " + row.pearls();
            context.drawString(MC.font, pearlText, rowX + 20, rowY + 4,
                (0xFF000000 | options.textColor), options.textShadow);
            context.renderItem(webIcon, rowX, rowY + 18);
            context.drawString(MC.font, webText(row), rowX + 20, rowY + 22,
                (0xFF000000 | options.textColor), options.textShadow);
            rowY += 40;
        }
        pose.popMatrix();
    }

    public record PlayerCount(UUID uuid, String name, int pearls, int webs) {}

    private static final class PendingPearl
    {
        final ThrownEnderpearl pearl;
        int ownerId;
        final Vec3 spawnPosition;
        final long firstTick;

        PendingPearl(ThrownEnderpearl pearl, int ownerId, Vec3 spawnPosition, long firstTick)
        {
            this.pearl = pearl;
            this.ownerId = ownerId;
            this.spawnPosition = spawnPosition;
            this.firstTick = firstTick;
        }
    }
}
