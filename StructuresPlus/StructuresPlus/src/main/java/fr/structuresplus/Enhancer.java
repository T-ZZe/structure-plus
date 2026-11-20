package fr.structuresplus;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ajoute des détails et des ressources aux structures d'un chunk.
 * Ne touche JAMAIS à un autre chunk que celui traité (pas de chargement en cascade).
 */
public final class Enhancer {

    private static final BlockFace[] HORIZ = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final BlockFace[] ALL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

    private static final Material[] FLOWERS = {
            Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.CORNFLOWER,
            Material.OXEYE_DAISY, Material.ALLIUM, Material.RED_TULIP, Material.PINK_TULIP
    };

    private static final Set<Material> MOSS_HOSTS = EnumSet.of(
            Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE, Material.ANDESITE,
            Material.DEEPSLATE, Material.TUFF, Material.DIRT);

    private static final Set<Material> ORE_HOSTS = EnumSet.of(
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.TUFF, Material.DEEPSLATE, Material.NETHERRACK);

    private static final class Feature {
        double chance;
        int max;
        List<String> structures = List.of();

        boolean has(String key) {
            if (chance <= 0) return false;
            for (String s : structures) {
                if (key.equals(s) || key.startsWith(s)) return true;
            }
            return false;
        }
    }

    private static final class Ctx {
        final Chunk chunk;
        final World world;
        final Random rnd;
        final int[] surf = new int[256];
        int changes, chests, lanterns;

        Ctx(Chunk chunk) {
            this.chunk = chunk;
            this.world = chunk.getWorld();
            this.rnd = new Random(world.getSeed() ^ (chunk.getX() * 341873128712L) ^ (chunk.getZ() * 132897987541L));
            Arrays.fill(surf, Integer.MIN_VALUE);
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey doneKey;

    private Feature weather, cobweb, moss, lantern, flower, ore, chest;
    private List<String> disabled = List.of();
    private int maxChanges = 4000;

    public final AtomicLong statChunks = new AtomicLong();
    public final AtomicLong statBlocks = new AtomicLong();
    public final AtomicLong statChests = new AtomicLong();

    public Enhancer(JavaPlugin plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "enhanced");
    }

    public void reload(FileConfiguration cfg) {
        weather = load(cfg, "weathering");
        cobweb = load(cfg, "cobwebs");
        moss = load(cfg, "moss");
        lantern = load(cfg, "lanterns");
        flower = load(cfg, "flowers");
        ore = load(cfg, "ores");
        chest = load(cfg, "extra-chests");
        disabled = cfg.getStringList("disabled-structures");
        maxChanges = cfg.getInt("max-changes-per-chunk", 4000);
    }

    private Feature load(FileConfiguration cfg, String name) {
        Feature f = new Feature();
        String p = "details." + name + ".";
        f.chance = cfg.getDouble(p + "chance", 0);
        f.max = cfg.getInt(p + "max-per-chunk", 99);
        f.structures = cfg.getStringList(p + "structures");
        return f;
    }

    private static boolean matches(List<String> list, String key) {
        for (String s : list) {
            if (key.equals(s) || key.startsWith(s)) return true;
        }
        return false;
    }

    /** @return true si le chunk a été amélioré. */
    public boolean enhance(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return false;

        Collection<GeneratedStructure> structures = chunk.getStructures();
        if (structures.isEmpty()) return false;

        World world = chunk.getWorld();
        Ctx c = new Ctx(chunk);
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (GeneratedStructure gs : structures) {
            String key = gs.getStructure().key().value();
            if (matches(disabled, key)) continue;

            for (StructurePiece piece : gs.getPieces()) {
                BoundingBox bb = piece.getBoundingBox();
                int x0 = Math.max((int) Math.floor(bb.getMinX()), baseX);
                int x1 = Math.min((int) Math.ceil(bb.getMaxX()) - 1, baseX + 15);
                int z0 = Math.max((int) Math.floor(bb.getMinZ()), baseZ);
                int z1 = Math.min((int) Math.ceil(bb.getMaxZ()) - 1, baseZ + 15);
                int y0 = Math.max((int) Math.floor(bb.getMinY()), world.getMinHeight());
                int y1 = Math.min((int) Math.ceil(bb.getMaxY()) - 1, world.getMaxHeight() - 1);
                if (x0 > x1 || z0 > z1 || y0 > y1) continue;

                long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                if (volume > 100_000L) continue;

                scan(c, key, x0, x1, y0, y1, z0, z1);
                tryChest(c, key, x0, x1, y0, y1, z0, z1);
            }
        }

        pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        statChunks.incrementAndGet();
        statBlocks.addAndGet(c.changes);
        statChests.addAndGet(c.chests);
        return true;
    }

    // ------------------------------------------------------------------ scan

    private void scan(Ctx c, String key, int x0, int x1, int y0, int y1, int z0, int z1) {
        boolean fWeather = weather.has(key);
        boolean fCob = cobweb.has(key);
        boolean fMoss = moss.has(key);
        boolean fLan = lantern.has(key);
        boolean fFlo = flower.has(key);
        boolean fOre = ore.has(key);
        if (!(fWeather || fCob || fMoss || fLan || fFlo || fOre)) return;
        boolean air = fCob || fMoss || fLan || fFlo;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y1; y >= y0; y--) {
                    if (c.changes >= maxChanges) return;
                    Block b = c.chunk.getBlock(x & 15, y, z & 15);
                    Material m = b.getType();

                    if (m.isAir()) {
                        if (air) airPass(c, b, x, y, z, fCob, fMoss, fLan, fFlo);
                        continue;
                    }

                    if (fOre && ORE_HOSTS.contains(m) && c.rnd.nextDouble() < ore.chance && exposed(c, b)) {
                        set(c, b, pickOre(m, c.rnd));
                        continue;
                    }

                    if (fWeather && c.rnd.nextDouble() < weather.chance) {
                        Material w = weathered(m, c.rnd);
                        if (w != null) set(c, b, w);
                    }
                }
            }
        }
    }

    private void airPass(Ctx c, Block b, int x, int y, int z,
                         boolean fCob, boolean fMoss, boolean fLan, boolean fFlo) {
        Random r = c.rnd;
        Block up = nb(c, b, BlockFace.UP);
        Block down = nb(c, b, BlockFace.DOWN);
        if (up == null || down == null) return;

        int s = surface(c, x, z);
        Material um = up.getType();
        Material dm = down.getType();

        // Fleurs à l'air libre, sur l'herbe
        if (fFlo && y > s && dm == Material.GRASS_BLOCK && um.isAir() && r.nextDouble() < flower.chance) {
            set(c, b, FLOWERS[r.nextInt(FLOWERS.length)]);
            return;
        }

        boolean covered = y <= s; // il y a un toit / de la roche au-dessus
        if (!covered) return;

        // Toiles dans les coins sombres et profonds
        if (fCob && y < s - 3 && um.isSolid() && r.nextDouble() < cobweb.chance && solidSides(c, b) >= 2) {
            set(c, b, Material.COBWEB);
            return;
        }

        // Mousse au sol
        if (fMoss && MOSS_HOSTS.contains(dm) && r.nextDouble() < moss.chance) {
            set(c, b, Material.MOSS_CARPET);
            return;
        }

        // Lanterne suspendue
        if (fLan && c.lanterns < lantern.max && um.isOccluding() && dm.isAir()
                && r.nextDouble() < lantern.chance) {
            Block down2 = nb(c, down, BlockFace.DOWN);
            if (down2 != null && down2.getType().isAir()) {
                Lantern data = (Lantern) Material.LANTERN.createBlockData();
                data.setHanging(true);
                b.setBlockData(data, false);
                c.lanterns++;
                c.changes++;
            }
        }
    }

    // ----------------------------------------------------------- coffres bonus

    private void tryChest(Ctx c, String key, int x0, int x1, int y0, int y1, int z0, int z1) {
        if (!chest.has(key) || c.chests >= chest.max || c.rnd.nextDouble() >= chest.chance) return;

        for (int i = 0; i < 24; i++) {
            int x = x0 + c.rnd.nextInt(x1 - x0 + 1);
            int y = y0 + c.rnd.nextInt(y1 - y0 + 1);
            int z = z0 + c.rnd.nextInt(z1 - z0 + 1);
            Block b = c.chunk.getBlock(x & 15, y, z & 15);
            if (!b.getType().isAir()) continue;

            Block below = nb(c, b, BlockFace.DOWN);
            Block above = nb(c, b, BlockFace.UP);
            if (below == null || above == null) continue;
            if (!below.getType().isSolid() || !above.getType().isAir()) continue;

            boolean nearChest = false;
            for (BlockFace f : HORIZ) {
                Block n = nb(c, b, f);
                if (n != null && n.getType() == Material.CHEST) nearChest = true;
            }
            if (nearChest) continue;

            org.bukkit.block.data.type.Chest data =
                    (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
            data.setFacing(HORIZ[c.rnd.nextInt(4)]);
            b.setBlockData(data, false);

            if (b.getState() instanceof org.bukkit.block.Chest state) {
                state.setLootTable(tableFor(key));
                state.update(true, false);
            }
            c.chests++;
            c.changes++;
            return;
        }
    }

    private static LootTable tableFor(String key) {
        LootTables t;
        if (key.startsWith("mineshaft")) t = LootTables.ABANDONED_MINESHAFT;
        else if (key.startsWith("stronghold")) t = LootTables.STRONGHOLD_CORRIDOR;
        else if (key.startsWith("desert_pyramid")) t = LootTables.DESERT_PYRAMID;
        else if (key.startsWith("jungle_pyramid")) t = LootTables.JUNGLE_TEMPLE;
        else if (key.startsWith("village")) t = LootTables.VILLAGE_TOOLSMITH;
        else if (key.startsWith("pillager_outpost")) t = LootTables.PILLAGER_OUTPOST;
        else if (key.startsWith("ruined_portal")) t = LootTables.RUINED_PORTAL;
        else if (key.startsWith("ocean_ruin")) t = LootTables.UNDERWATER_RUIN_SMALL;
        else t = LootTables.SIMPLE_DUNGEON;
        return t.getLootTable();
    }

    // ----------------------------------------------------------------- helpers

    private void set(Ctx c, Block b, Material m) {
        b.setType(m, false);
        c.changes++;
    }

    /** Voisin dans le MÊME chunk uniquement (null sinon) pour ne jamais charger un autre chunk. */
    private static Block nb(Ctx c, Block b, BlockFace f) {
        int x = (b.getX() & 15) + f.getModX();
        int z = (b.getZ() & 15) + f.getModZ();
        int y = b.getY() + f.getModY();
        if (x < 0 || x > 15 || z < 0 || z > 15) return null;
        if (y < c.world.getMinHeight() || y >= c.world.getMaxHeight()) return null;
        return c.chunk.getBlock(x, y, z);
    }

    private static boolean exposed(Ctx c, Block b) {
        for (BlockFace f : ALL) {
            Block n = nb(c, b, f);
            if (n != null && n.getType().isAir()) return true;
        }
        return false;
    }

    private static int solidSides(Ctx c, Block b) {
        int n = 0;
        for (BlockFace f : HORIZ) {
            Block o = nb(c, b, f);
            if (o != null && o.getType().isSolid()) n++;
        }
        return n;
    }

    private static int surface(Ctx c, int wx, int wz) {
        int i = ((wx & 15) << 4) | (wz & 15);
        if (c.surf[i] == Integer.MIN_VALUE) c.surf[i] = c.world.getHighestBlockYAt(wx, wz);
        return c.surf[i];
    }

    private static Material weathered(Material m, Random r) {
        return switch (m) {
            case STONE_BRICKS -> r.nextBoolean() ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
            case COBBLESTONE -> Material.MOSSY_COBBLESTONE;
            case DEEPSLATE_BRICKS -> Material.CRACKED_DEEPSLATE_BRICKS;
            case DEEPSLATE_TILES -> Material.CRACKED_DEEPSLATE_TILES;
            case NETHER_BRICKS -> Material.CRACKED_NETHER_BRICKS;
            case POLISHED_BLACKSTONE_BRICKS -> Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
            default -> null;
        };
    }

    private static Material pickOre(Material host, Random r) {
        if (host == Material.NETHERRACK) {
            return r.nextInt(100) < 70 ? Material.NETHER_QUARTZ_ORE : Material.NETHER_GOLD_ORE;
        }
        boolean deep = host == Material.DEEPSLATE;
        int n = r.nextInt(100);
        if (n < 38) return deep ? Material.DEEPSLATE_COAL_ORE : Material.COAL_ORE;
        if (n < 66) return deep ? Material.DEEPSLATE_IRON_ORE : Material.IRON_ORE;
        if (n < 78) return deep ? Material.DEEPSLATE_COPPER_ORE : Material.COPPER_ORE;
        if (n < 86) return deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE;
        if (n < 93) return deep ? Material.DEEPSLATE_REDSTONE_ORE : Material.REDSTONE_ORE;
        if (n < 98) return deep ? Material.DEEPSLATE_LAPIS_ORE : Material.LAPIS_ORE;
        return deep ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
    }
}
