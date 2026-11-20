package fr.structuresplus;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Ajoute des objets bonus au loot généré dans les structures. */
public final class LootBonus implements Listener {

    private record Entry(Material material, double chance, int min, int max) {}

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private int maxExtra = 6;
    private List<Entry> defaults = List.of();
    private final Map<String, List<Entry>> byStructure = new LinkedHashMap<>();

    public LootBonus(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(FileConfiguration cfg) {
        enabled = cfg.getBoolean("loot.enabled", true);
        maxExtra = cfg.getInt("loot.max-extra-items", 6);
        defaults = parse(cfg.getStringList("loot.default"));
        byStructure.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("loot.structures");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                byStructure.put(key, parse(sec.getStringList(key)));
            }
        }
    }

    private List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        for (String line : lines) {
            try {
                String[] p = line.split(":");
                Material m = Material.matchMaterial(p[0].trim());
                if (m == null) {
                    plugin.getLogger().warning("Objet inconnu dans la config : " + line);
                    continue;
                }
                double chance = Double.parseDouble(p[1].trim());
                int min = Integer.parseInt(p[2].trim());
                int max = Integer.parseInt(p[3].trim());
                out.add(new Entry(m, chance, Math.max(1, min), Math.max(min, max)));
            } catch (Exception ex) {
                plugin.getLogger().warning("Ligne de loot invalide : " + line);
            }
        }
        return out;
    }

    /** Nom de la structure qui contient ce point, ou null. */
    public static String structureAt(Location loc) {
        if (loc.getWorld() == null) return null;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!loc.getWorld().isChunkLoaded(cx, cz)) return null;

        Chunk chunk = loc.getWorld().getChunkAt(cx, cz);
        double x = loc.getBlockX() + 0.5;
        double y = loc.getBlockY() + 0.5;
        double z = loc.getBlockZ() + 0.5;

        for (GeneratedStructure gs : chunk.getStructures()) {
            for (StructurePiece piece : gs.getPieces()) {
                BoundingBox bb = piece.getBoundingBox();
                if (bb.contains(x, y, z)) {
                    return gs.getStructure().key().value();
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onLoot(LootGenerateEvent event) {
        if (!enabled) return;
        Location loc = event.getLootContext().getLocation();
        if (loc == null) return;

        String key = structureAt(loc);
        if (key == null) return;

        List<Entry> candidates = new ArrayList<>(defaults);
        for (Map.Entry<String, List<Entry>> e : byStructure.entrySet()) {
            if (key.equals(e.getKey()) || key.startsWith(e.getKey())) {
                candidates.addAll(e.getValue());
            }
        }
        if (candidates.isEmpty()) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<ItemStack> loot = new ArrayList<>(event.getLoot());
        int added = 0;
        for (Entry en : candidates) {
            if (added >= maxExtra) break;
            if (rnd.nextDouble() < en.chance()) {
                int amount = en.min() + rnd.nextInt(en.max() - en.min() + 1);
                loot.add(new ItemStack(en.material(), amount));
                added++;
            }
        }
        event.setLoot(loot);
    }
}
