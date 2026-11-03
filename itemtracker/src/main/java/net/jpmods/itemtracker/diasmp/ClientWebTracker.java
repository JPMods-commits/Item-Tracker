/* DiaSMPTracker client-side cobweb estimates, 2026-10-30. GPL-3.0-or-later. */
package net.jpmods.itemtracker.diasmp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.jpmods.itemtracker.DiaSMPTracker;

/**
 * Client receipt ticks are not server placement ticks. This class deliberately
 * exposes its result as an estimate: equipment/inventory updates may be delayed,
 * and a newly received chunk cannot establish a historical block's owner.
 * Coordinates and evidence stay internal; no world markers are rendered.
 */
public final class ClientWebTracker
{
    private static final int MAX_POSITIONS = 262144;
    private static final int MAX_PENDING = 8192;
    private static final int MAX_CHUNKS = 512;
    private final DiaSMPTracker feature;
    private final LinkedHashMap<Long, Cell> positions = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Appearance> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, ArrayDeque<Hold>> held = new LinkedHashMap<>();
    private final ArrayDeque<Debit> debits = new ArrayDeque<>();
    private final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
    private long tick;
    private int inventory = -1;
    private BlockPos lastScanPosition;

    public ClientWebTracker(DiaSMPTracker feature) { this.feature = feature; }

    public void worldChanged()
    {
        positions.clear();
        clearPending();
        tick = 0;
        lastScanPosition = null;
    }

    public void clearPending()
    {
        for(Cell cell : positions.values()) cell.handled = true;
        pending.clear();
        held.clear();
        debits.clear();
        chunks.clear();
        inventory = -1;
    }

    public void enable()
    {
        captureContext();
        queueNearbyChunks();
    }

    public void captureContext()
    {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if(mc.level == null || mc.player == null || !feature.canEstimateWebs()) return;
        int total = 0;
        var inv = mc.player.getInventory();
        for(int i = 0; i < inv.getContainerSize(); i++)
            if(inv.getItem(i).is(Items.COBWEB)) total += inv.getItem(i).getCount();
        // Include a cursor-held stack so ordinary inventory rearrangements do
        // not appear to consume webs. getContainerSize already includes offhand.
        var cursor = mc.player.containerMenu.getCarried();
        if(cursor.is(Items.COBWEB)) total += cursor.getCount();
        if(inventory >= 0 && total != inventory)
        {
            int change = inventory - total;
            if(change > 0) debits.addLast(new Debit(tick, change));
            else
            {
                // Cancel temporary slot-move decreases before attributing webs.
                var iterator = debits.descendingIterator();
                int restored = -change;
                while(iterator.hasNext() && restored > 0)
                {
                    Debit debit = iterator.next();
                    if(tick - debit.tick > 2 * feature.webTimingGrace() + 2) break;
                    int amount = Math.min(restored, debit.remaining);
                    debit.remaining -= amount;
                    restored -= amount;
                }
            }
        }
        inventory = total;
        for(Player player : mc.level.players())
        {
            if(!player.getMainHandItem().is(Items.COBWEB) && !player.getOffhandItem().is(Items.COBWEB)) continue;
            if(player != mc.player && !feature.isWebOpponent(player)) continue;
            var history = held.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
            if(history.isEmpty() || history.getLast().tick != tick)
                history.addLast(new Hold(tick, player.getId()));
            while(history.size() > 32) history.removeFirst();
        }
        while(held.size() > 1024) held.remove(held.keySet().iterator().next());
        while(!debits.isEmpty() && tick - debits.getFirst().tick > 32) debits.removeFirst();
    }

    /** A real client prediction identifies the coordinate of our own placement. */
    public void ownPlacement(BlockPos pos)
    {
        captureContext();
        Cell cell = positions.get(pos.asLong());
        if(cell == null) cell = new Cell(pos.immutable());
        positions.put(pos.asLong(), cell);
        cell.present = true;
        cell.handled = true;
        if(feature.canEstimateWebs())
        {
            // Process known-own appearances first, so their inventory debit is
            // never subtracted again from an opponent's simultaneous placement.
            Appearance appearance = new Appearance(cell, tick, false, true);
            pending.put(pos.asLong(), appearance);
        }
        trim();
    }

    public void confirmedPlacement(BlockPos pos)
    {
        Cell cell = positions.computeIfAbsent(pos.asLong(), key -> new Cell(pos.immutable()));
        cell.present = true;
        cell.handled = true;
        pending.remove(pos.asLong());
        trim();
    }

    public void blockUpdate(BlockPos pos, BlockState state)
    {
        captureContext();
        if(!state.is(Blocks.COBWEB))
        {
            Cell previous = positions.get(pos.asLong());
            if(previous != null)
            {
                previous.present = false;
                previous.handled = true;
            }
            pending.remove(pos.asLong());
            return;
        }
        appearance(pos, false);
    }

    private void appearance(BlockPos pos, boolean discovered)
    {
        Cell cell = positions.get(pos.asLong());
        if(cell != null && cell.present) return; // Replay/reload of a known web.
        if(cell == null)
        {
            cell = new Cell(pos.immutable());
            positions.put(pos.asLong(), cell);
        }
        cell.present = true;
        cell.handled = !feature.canEstimateWebs()
            || ((discovered || !feature.inWebRange(Vec3.atCenterOf(pos))) && !feature.countUnseenWebs());
        if(!cell.handled && feature.inWebRange(Vec3.atCenterOf(pos)))
            pending.put(pos.asLong(), new Appearance(cell, tick, discovered, false));
        trim();
    }

    public void queueChunk(int x, int z)
    {
        if(!feature.canEstimateWebs()) return;
        int px = net.minecraft.client.Minecraft.getInstance().player.blockPosition().getX() >> 4;
        int pz = net.minecraft.client.Minecraft.getInstance().player.blockPosition().getZ() >> 4;
        if(Math.abs(x - px) > 6 || Math.abs(z - pz) > 6) return;
        chunks.add(ChunkPos.asLong(x, z));
        if(chunks.size() > MAX_CHUNKS) chunks.remove(chunks.iterator().next());
    }

    private void queueNearbyChunks()
    {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if(mc.player == null || mc.level == null) return;
        int px = mc.player.blockPosition().getX() >> 4;
        int pz = mc.player.blockPosition().getZ() >> 4;
        for(int x = px - 6; x <= px + 6; x++)
            for(int z = pz - 6; z <= pz + 6; z++)
                if(mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) queueChunk(x, z);
        lastScanPosition = mc.player.blockPosition();
    }

    private void scanChunk(long packed)
    {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if(chunk == null) return; // Never request or load an unavailable chunk.
        for(Cell cell : positions.values())
            if(cell.present && (cell.pos.getX() >> 4) == x && (cell.pos.getZ() >> 4) == z
                && !chunk.getBlockState(cell.pos).is(Blocks.COBWEB))
            {
                cell.present = false;
                cell.handled = true;
                pending.remove(cell.pos.asLong());
            }
        var sections = chunk.getSections();
        for(int i = 0; i < sections.length; i++)
        {
            var section = sections[i];
            // Skip palettes containing no cobweb: ordinary stone/air chunks
            // cost a palette check, not a scan of every block in render distance.
            if(!section.maybeHas(state -> state.is(Blocks.COBWEB))) continue;
            int baseY = chunk.getMinY() + i * 16;
            for(int sy = 0; sy < 16; sy++)
                for(int sx = 0; sx < 16; sx++)
                    for(int sz = 0; sz < 16; sz++)
                        if(section.getBlockState(sx, sy, sz).is(Blocks.COBWEB))
                            appearance(new BlockPos(x * 16 + sx, baseY + sy, z * 16 + sz), true);
        }
    }

    public void tick()
    {
        if(!feature.canEstimateWebs()) return;
        captureContext();
        if(lastScanPosition == null || (tick % 20 == 0 && !lastScanPosition.equals(net.minecraft.client.Minecraft.getInstance().player.blockPosition())))
            queueNearbyChunks();
        // Four chunks per tick bounds work when a large area loads at once.
        for(int i = 0; i < 4 && !chunks.isEmpty(); i++)
        {
            long chunk = chunks.iterator().next();
            chunks.remove(chunk);
            scanChunk(chunk);
        }
        for(Cell cell : positions.values())
            if(cell.present && !cell.handled && !pending.containsKey(cell.pos.asLong())
                && feature.inWebRange(Vec3.atCenterOf(cell.pos)))
                pending.put(cell.pos.asLong(), new Appearance(cell, tick, true, false));
        trim();
        List<Appearance> mature = new ArrayList<>();
        for(Appearance event : pending.values())
            if(tick > event.tick + feature.webTimingGrace()) mature.add(event);
        // Known own predictions claim their matching inventory decrease first.
        mature.sort((a, b) -> Boolean.compare(b.own, a.own));
        for(Appearance event : mature)
        {
            if(!event.cell.present || !feature.inWebRange(Vec3.atCenterOf(event.cell.pos))
                || finish(event) || tick - event.tick >= 100)
            {
                event.cell.handled = true;
                pending.remove(event.cell.pos.asLong());
            }
        }
        tick++;
    }

    private boolean finish(Appearance event)
    {
        if(event.own)
        {
            spendDebit(event);
            return true;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean selfHeld = heldNear(mc.player, event.tick);
        if(!event.discovered && selfHeld && spendDebit(event)) return true;
        List<Player> candidates = new ArrayList<>();
        for(Player player : mc.level.players())
            if(feature.isWebOpponent(player) && heldNear(player, event.tick)) candidates.add(player);
        if(candidates.size() == 1)
        {
            feature.addEstimatedWeb(candidates.getFirst());
            return true;
        }
        if(event.discovered && feature.countUnseenWebs())
        {
            // The requested 1v1 assumption. No placement time/owner was supplied
            // with this chunk; pre-existing arena webs can inflate this estimate.
            var opponents = mc.level.players().stream().filter(feature::isWebOpponent).toList();
            if(opponents.size() == 1)
            {
                feature.addEstimatedWeb(opponents.getFirst());
                return true;
            }
            return false; // Wait briefly for the sole opponent's spawn packet.
        }
        // Multiple remote holders are not distinguishable from client evidence.
        return true;
    }

    private boolean heldNear(Player player, long eventTick)
    {
        var history = held.get(player.getUUID());
        if(history == null) return false;
        for(Hold sample : history)
            if(sample.entityId == player.getId() && Math.abs(sample.tick - eventTick) <= feature.webTimingGrace()) return true;
        return false;
    }

    private boolean spendDebit(Appearance event)
    {
        for(Debit debit : debits)
            if(debit.remaining > 0 && Math.abs(debit.tick - event.tick) <= feature.webTimingGrace())
            {
                debit.remaining--;
                return true;
            }
        return false;
    }

    private void trim()
    {
        while(positions.size() > MAX_POSITIONS)
        {
            long oldest = positions.keySet().iterator().next();
            positions.remove(oldest);
            pending.remove(oldest);
        }
        while(pending.size() > MAX_PENDING)
        {
            long oldest = pending.keySet().iterator().next();
            pending.remove(oldest).cell.handled = true;
        }
    }

    private static final class Cell
    {
        final BlockPos pos;
        boolean present, handled;
        Cell(BlockPos pos) { this.pos = pos; }
    }
    private record Appearance(Cell cell, long tick, boolean discovered, boolean own) {}
    private record Hold(long tick, int entityId) {}
    private static final class Debit
    {
        final long tick;
        int remaining;
        Debit(long tick, int remaining) { this.tick = tick; this.remaining = remaining; }
    }
}
