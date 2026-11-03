/*
 * PotionTracker for the custom client, 2026-10-29.
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;

public final class PotionTracker
{
    private static final int MAX_SEEN_POTIONS = 65536;
    private static final int MAX_PENDING_POTIONS = 2048;
    private static final int MAX_PLAYERS = 1024;

    private static final net.minecraft.client.Minecraft MC = net.minecraft.client.Minecraft.getInstance();
    private Settings.Potion options;
    private final LinkedHashMap<UUID, Player> knownPlayers = new LinkedHashMap<>();
    private final ItemStack potionIcon = new ItemStack(Items.SPLASH_POTION);

    private final LinkedHashMap<UUID, PlayerCount> counts = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> seenPotions = new LinkedHashSet<>();
    private final LinkedHashMap<UUID, PendingPotion> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Player> deadPlayers = new LinkedHashMap<>();
    private ClientLevel currentWorld;
    private long ticks;

    public PotionTracker(Settings.Potion options)
    {
        this.options = options;
        potionIcon.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_HEALING));
    }

    public boolean isEnabled() { return options.enabled; }

    public void applyOptions(Settings.Potion value)
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
        ignoreExistingPotions();
    }

    protected void onDisable()
    {
        pending.clear();
    }

    private boolean syncWorld()
    {
        if(currentWorld != MC.level)
        {
            currentWorld = MC.level;
            counts.clear();
            seenPotions.clear();
            pending.clear();
            deadPlayers.clear();
            knownPlayers.clear();
            ticks = 0;
        }
        return currentWorld != null && MC.player != null;
    }

    /** Invoked on the client thread AFTER vanilla applies spawn/item data. */
    public void observeEntity(Entity entity, int ownerId)
    {
        if(!isEnabled() || !syncWorld() || !(entity instanceof AbstractThrownPotion potion)
            || seenPotions.contains(potion.getUUID())) return;
        refreshLocalLife();
        if(localPlayerDead())
        {
            remember(potion.getUUID());
            return;
        }
        PendingPotion candidate = pending.get(potion.getUUID());
        if(candidate == null)
        {
            candidate = new PendingPotion(potion, ownerId, potion.position(), ticks);
            pending.put(potion.getUUID(), candidate);
        }
        else if(ownerId > 0) candidate.ownerId = ownerId;
        if(finish(candidate)) pending.remove(potion.getUUID());
        while(pending.size() > MAX_PENDING_POTIONS)
        {
            UUID oldest = pending.keySet().iterator().next();
            remember(oldest);
            pending.remove(oldest);
        }
    }

    private boolean finish(PendingPotion candidate)
    {
        AbstractThrownPotion potion = candidate.potion;
        if(!withinRange(candidate.spawnPosition)
            || (!options.countLingering && potion.getType() == EntityType.LINGERING_POTION))
            return done(potion);

        PotionContents contents = potion.getItem().get(DataComponents.POTION_CONTENTS);
        if(contents == null) return false; // Spawn and item metadata are separate packets.
        boolean healingII = false;
        for(var effect : contents.getAllEffects())
            if(effect.getEffect().equals(MobEffects.INSTANT_HEALTH) && effect.getAmplifier() == 1)
                healingII = true;
        if(!healingII)
        {
            // The initial default stack has no potion type yet. Do not discard
            // that candidate before its authoritative item data arrives.
            if(contents.potion().isPresent() || contents.hasEffects()) return done(potion);
            return false;
        }

        Entity owner = potion.getOwner();
        if(owner == null && candidate.ownerId > 0) owner = currentWorld.getEntity(candidate.ownerId);
        for(Player dead : deadPlayers.values())
            if(candidate.ownerId > 0 && candidate.ownerId == dead.getId()) return done(potion);
        if(owner == null) return false; // Never guess ownership from proximity or hand changes.
        if(!(owner instanceof Player player) || isSelf(player) || !withinRange(player.position())
            || player.isDeadOrDying() || deadPlayers.containsKey(player.getUUID()))
            return done(potion);

        observePlayerSpawn(player);
        PlayerCount previous = counts.get(player.getUUID());
        int count = previous == null ? 1 : previous.throwsCount() == Integer.MAX_VALUE
            ? Integer.MAX_VALUE : previous.throwsCount() + 1;
        counts.put(player.getUUID(), new PlayerCount(player.getUUID(), player.getScoreboardName(), count));
        while(counts.size() > MAX_PLAYERS) counts.remove(counts.keySet().iterator().next());
        return done(potion);
    }

    private boolean done(AbstractThrownPotion potion)
    {
        remember(potion.getUUID());
        return true;
    }

    private void remember(UUID id)
    {
        seenPotions.add(id);
        if(seenPotions.size() > MAX_SEEN_POTIONS) seenPotions.remove(seenPotions.iterator().next());
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

    private void ignoreExistingPotions()
    {
        if(currentWorld == null) return;
        for(Entity entity : currentWorld.entitiesForRendering())
            if(entity instanceof AbstractThrownPotion) remember(entity.getUUID());
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
        ticks++;
        var iterator = pending.values().iterator();
        while(iterator.hasNext())
        {
            PendingPotion candidate = iterator.next();
            if(finish(candidate) || ticks - candidate.firstTick >= 100)
            {
                remember(candidate.potion.getUUID());
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
            ignorePlayerPotions(player);
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
            if(player == MC.player) ignoreExistingPotions();
            else ignorePlayerPotions(player);
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
                ignorePlayerPotions(previous);
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

    private void ignorePlayerPotions(Player player)
    {
        var iterator = pending.entrySet().iterator();
        while(iterator.hasNext())
        {
            var entry = iterator.next();
            PendingPotion candidate = entry.getValue();
            Entity owner = candidate.potion.getOwner();
            if(candidate.ownerId == player.getId()
                || (owner != null && owner.getUUID().equals(player.getUUID())))
            {
                remember(entry.getKey());
                iterator.remove();
            }
        }
        for(Entity entity : currentWorld.entitiesForRendering())
            if(entity instanceof AbstractThrownPotion potion)
            {
                Entity owner = potion.getOwner();
                if(owner != null && owner.getUUID().equals(player.getUUID())) remember(potion.getUUID());
            }
    }

    public void resetCounts()
    {
        syncWorld();
        counts.clear();
        for(UUID id : pending.keySet()) remember(id);
        pending.clear();
        ignoreExistingPotions();
    }

    public int getCount(UUID player)
    {
        syncWorld();
        PlayerCount count = counts.get(player);
        return count == null ? 0 : count.throwsCount();
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
            if(!isSelf(player) && !player.isDeadOrDying() && !deadPlayers.containsKey(player.getUUID()) && withinRange(player.position())
                && (options.showZeroCounts || getCount(player.getUUID()) > 0)) players.add(player);
        players.sort(Comparator.<Player>comparingInt(p -> getCount(p.getUUID())).reversed()
            .thenComparing(Player::getScoreboardName, String.CASE_INSENSITIVE_ORDER));
        List<PlayerCount> rows = new ArrayList<>();
        for(Player player : players)
        {
            if(rows.size() >= options.maxRows) break;
            rows.add(new PlayerCount(player.getUUID(), player.getScoreboardName(), getCount(player.getUUID())));
        }
        return List.copyOf(rows);
    }

    public void onRenderGUI(GuiGraphics context, float partialTicks)
    {
        if(!isEnabled() || !options.showHud || !syncWorld()) return;
        List<PlayerCount> rows = getHudRows();
        if(rows.isEmpty()) rows = List.of(new PlayerCount(new UUID(0, 0), "", 0));
        int width = 0;
        for(PlayerCount row : rows)
        {
            int rowWidth = 20 + MC.font.width("- " + row.throwsCount());
            if(options.showPlayerNames && !row.name().isEmpty())
                rowWidth += MC.font.width(row.name() + ":") + 4;
            width = Math.max(width, rowWidth);
        }
        width += 8;
        int height = 8 + rows.size() * 18;
        float scale = (float)options.hudScale;
        int x = Math.max(0, Math.round((context.guiWidth() / scale - width) * (float)options.hudX / 100));
        int y = Math.max(0, Math.round((context.guiHeight() / scale - height) * (float)options.hudY / 100));
        var pose = context.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        if(options.background) context.fill(x, y, x + width, y + height, 0x99000000);
        for(int i = 0; i < rows.size(); i++)
        {
            PlayerCount row = rows.get(i);
            int rowX = x + 4;
            int rowY = y + 4 + i * 18;
            if(options.showPlayerNames && !row.name().isEmpty())
            {
                String name = row.name() + ":";
                context.drawString(MC.font, name, rowX, rowY + 4, (0xFF000000 | options.textColor), options.textShadow);
                rowX += MC.font.width(name) + 4;
            }
            // Render the actual Healing II item through Minecraft's current
            // item models/textures. Resource-pack reloads update this icon;
            // no baked image or cached sprite is used.
            context.renderItem(potionIcon, rowX, rowY);
            context.drawString(MC.font, "- " + row.throwsCount(), rowX + 20, rowY + 4,
                (0xFF000000 | options.textColor), options.textShadow);
        }
        pose.popMatrix();
    }

    public record PlayerCount(UUID uuid, String name, int throwsCount) {}

    private static final class PendingPotion
    {
        final AbstractThrownPotion potion;
        int ownerId;
        final Vec3 spawnPosition;
        final long firstTick;

        PendingPotion(AbstractThrownPotion potion, int ownerId, Vec3 spawnPosition, long firstTick)
        {
            this.potion = potion;
            this.ownerId = ownerId;
            this.spawnPosition = spawnPosition;
            this.firstTick = firstTick;
        }
    }
}
