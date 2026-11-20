package com.structuresplus;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("deprecation")
public final class StructuresPlus extends JavaPlugin implements Listener, TabExecutor {

    private record Entry(Material material, int min, int max, int weight) {}

    private static final Material[] FLOWERS = {
            Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
            Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM
    };

    private NamespacedKey doneKey;
    private final List<Entry> low = new ArrayList<>();
    private final List<Entry> high = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        doneKey = new NamespacedKey(this, "enhanced");
        loadPools();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("structuresplus");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("StructuresPlus active.");
    }

    private void loadPools() {
        low.clear();
        high.clear();
        parse(getConfig().getStringList("pools.low"), low);
        parse(getConfig().getStringList("pools.high"), high);
    }

    private void parse(List<String> raw, List<Entry> out) {
        for (String s : raw) {
            try {
                String[] p = s.split(":");
                Material m = Material.matchMaterial(p[0]);
                if (m == null) {
                    getLogger().warning("Materiau inconnu: " + p[0]);
                    continue;
                }
                String[] r = p[1].split("-");
                int min = Integer.parseInt(r[0].trim());
                int max = r.length > 1 ? Integer.parseInt(r[1].trim()) : min;
                int w = p.length > 2 ? Integer.parseInt(p[2].trim()) : 1;
                out.add(new Entry(m, min, Math.max(min, max), Math.max(1, w)));
            } catch (Exception ex) {
                getLogger().warning("Entree de pool invalide: " + s);
            }
        }
    }

    private ItemStack roll(List<Entry> pool) {
        if (pool.isEmpty()) return null;
        int total = 0;
        for (Entry e : pool) total += e.weight();
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (Entry e : pool) {
            pick -= e.weight();
            if (pick < 0) {
                int amt = ThreadLocalRandom.current().nextInt(e.min(), e.max() + 1);
                return new ItemStack(e.material(), amt);
            }
        }
        return null;
    }

    private static boolean matches(String name, List<String> keys) {
        for (String k : keys) if (name.contains(k)) return true;
        return false;
    }

    private static boolean chance(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    // ---------- Evenements ----------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk() && !getConfig().getBoolean("process-existing-chunks")) return;
        if (getConfig().getStringList("disabled-worlds").contains(e.getWorld().getName())) return;
        Chunk chunk = e.getChunk();
        getServer().getScheduler().runTask(this, () -> {
            if (chunk.isLoaded()) enhance(chunk, false);
        });
    }

    @EventHandler
    public void onLoot(LootGenerateEvent e) {
        if (!getConfig().getBoolean("bonus-loot.enabled")) return;
        String key = e.getLootTable().getKey().getKey();
        if (!key.startsWith("chests/")) return;
        if (!chance(getConfig().getDouble("bonus-loot.chance", 0.7))) return;

        boolean rich = matches(key, getConfig().getStringList("bonus-loot.high-keywords"));
        int max = Math.max(1, getConfig().getInt(rich ? "bonus-loot.rolls-high" : "bonus-loot.rolls-low", 2));
        int rolls = ThreadLocalRandom.current().nextInt(1, max + 1);

        List<ItemStack> loot = new ArrayList<>(e.getLoot());
        for (int i = 0; i < rolls; i++) {
            ItemStack it = roll(rich ? high : low);
            if (it != null) loot.add(it);
        }
        e.setLoot(loot);
    }

    // ---------- Amelioration d'un chunk ----------

    /** @return nombre de structures traitees */
    public int enhance(Chunk chunk, boolean force) {
        var pdc = chunk.getPersistentDataContainer();
        if (!force && pdc.has(doneKey, PersistentDataType.BYTE)) return 0;
        pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);

        int done = 0;
        World world = chunk.getWorld();
        for (GeneratedStructure gs : chunk.getStructures()) {
            String name = gs.getStructure().getKey().getKey();
            BoundingBox bb = gs.getBoundingBox();

            int minX = Math.max((int) Math.floor(bb.getMinX()), chunk.getX() << 4);
            int maxX = Math.min((int) Math.ceil(bb.getMaxX()) - 1, (chunk.getX() << 4) + 15);
            int minZ = Math.max((int) Math.floor(bb.getMinZ()), chunk.getZ() << 4);
            int maxZ = Math.min((int) Math.ceil(bb.getMaxZ()) - 1, (chunk.getZ() << 4) + 15);
            int minY = Math.max((int) Math.floor(bb.getMinY()), world.getMinHeight());
            int maxY = Math.min((int) Math.ceil(bb.getMaxY()) - 1, world.getMaxHeight() - 2);
            if (minX > maxX || minZ > maxZ || minY > maxY) continue;

            boolean weather = getConfig().getBoolean("weathering.enabled")
                    && matches(name, getConfig().getStringList("weathering.structures"));
            boolean webs = getConfig().getBoolean("cobwebs.enabled")
                    && matches(name, getConfig().getStringList("cobwebs.structures"));
            boolean village = getConfig().getBoolean("village-decor.enabled") && name.startsWith("village");

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block b = world.getBlockAt(x, y, z);
                        Material t = b.getType();
                        if (t.isAir()) {
                            if (webs) tryCobweb(b);
                            if (weather) tryMossCarpet(b);
                        } else {
                            if (weather) tryWeather(b, t);
                            if (village) tryVillageDecor(b, t);
                        }
                    }
                }
            }

            if (getConfig().getBoolean("fill-empty-containers.enabled")) fillContainers(chunk, bb);
            done++;
        }
        return done;
    }

    private void tryWeather(Block b, Material t) {
        if (!chance(getConfig().getDouble("weathering.chance", 0.08))) return;
        switch (t) {
            case STONE_BRICKS -> b.setType(chance(0.5) ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS, false);
            case COBBLESTONE -> b.setType(Material.MOSSY_COBBLESTONE, false);
            default -> { }
        }
    }

    private void tryMossCarpet(Block b) {
        if (!chance(getConfig().getDouble("weathering.moss-carpet-chance", 0.03))) return;
        Block below = b.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid() && below.getType().isOccluding()) {
            b.setType(Material.MOSS_CARPET, false);
        }
    }

    private void tryCobweb(Block b) {
        if (!chance(getConfig().getDouble("cobwebs.chance", 0.12))) return;
        if (!b.getRelative(BlockFace.UP).getType().isOccluding()) return;
        int walls = 0;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (b.getRelative(f).getType().isOccluding()) walls++;
        }
        if (walls >= 2) b.setType(Material.COBWEB, false);
    }

    private void tryVillageDecor(Block b, Material t) {
        Block above = b.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) return;
        if (Tag.WOODEN_FENCES.isTagged(t)) {
            if (chance(getConfig().getDouble("village-decor.lantern-chance", 0.08))) {
                above.setType(Material.LANTERN, false);
                if (above.getBlockData() instanceof Lantern l) {
                    l.setHanging(false);
                    above.setBlockData(l, false);
                }
            }
        } else if (t == Material.GRASS_BLOCK) {
            if (chance(getConfig().getDouble("village-decor.flower-chance", 0.04))) {
                above.setType(FLOWERS[ThreadLocalRandom.current().nextInt(FLOWERS.length)], false);
            }
        }
    }

    private void fillContainers(Chunk chunk, BoundingBox bb) {
        double p = getConfig().getDouble("fill-empty-containers.chance", 0.5);
        for (BlockState st : chunk.getTileEntities()) {
            if (!(st instanceof Container c)) continue;
            if (!bb.contains(st.getX() + 0.5, st.getY() + 0.5, st.getZ() + 0.5)) continue;
            if (st instanceof Lootable l && l.hasLootTable()) continue;
            if (!c.getInventory().isEmpty()) continue;
            if (!chance(p)) continue;
            int n = ThreadLocalRandom.current().nextInt(1, 4);
            for (int i = 0; i < n; i++) {
                ItemStack it = roll(low);
                if (it != null) c.getInventory().addItem(it);
            }
            c.update();
        }
    }

    // ---------- Commandes ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/" + label + " <reload|scan [rayon]>");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                loadPools();
                s.sendMessage("StructuresPlus : config rechargee.");
            }
            case "scan" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("Commande reservee aux joueurs.");
                    return true;
                }
                int radius = 2;
                if (a.length > 1) {
                    try {
                        radius = Math.max(0, Math.min(8, Integer.parseInt(a[1])));
                    } catch (NumberFormatException ignored) { }
                }
                Chunk c = p.getLocation().getChunk();
                World w = p.getWorld();
                int total = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (w.isChunkLoaded(c.getX() + dx, c.getZ() + dz)) {
                            total += enhance(w.getChunkAt(c.getX() + dx, c.getZ() + dz), true);
                        }
                    }
                }
                s.sendMessage("StructuresPlus : " + total + " structure(s) amelioree(s).");
            }
            default -> s.sendMessage("/" + label + " <reload|scan [rayon]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("reload", "scan") : List.of();
    }
}
