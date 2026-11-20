package fr.structuresplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class StructuresPlus extends JavaPlugin implements Listener, TabExecutor {

    private record ChunkRef(UUID world, int x, int z) {}

    private Enhancer enhancer;
    private LootBonus lootBonus;
    private final ArrayDeque<ChunkRef> queue = new ArrayDeque<>();
    private final Set<ChunkRef> queued = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enhancer = new Enhancer(this);
        lootBonus = new LootBonus(this);
        reloadAll();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(lootBonus, this);

        var cmd = getCommand("structuresplus");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                processQueue();
            }
        }.runTaskTimer(this, 40L, 1L);

        getLogger().info("StructuresPlus activé.");
    }

    private void reloadAll() {
        reloadConfig();
        enhancer.reload(getConfig());
        lootBonus.reload(getConfig());
    }

    // ------------------------------------------------------------------ chunks

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() && !getConfig().getBoolean("enhance-existing-chunks", false)) return;
        enqueue(event.getChunk().getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private boolean worldAllowed(World world) {
        List<String> worlds = getConfig().getStringList("worlds");
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    private boolean enqueue(World world, int x, int z) {
        if (!worldAllowed(world)) return false;
        ChunkRef ref = new ChunkRef(world.getUID(), x, z);
        if (!queued.add(ref)) return false;
        queue.add(ref);
        return true;
    }

    private void processQueue() {
        int n = Math.max(1, getConfig().getInt("chunks-per-tick", 1));
        while (n-- > 0 && !queue.isEmpty()) {
            ChunkRef ref = queue.poll();
            queued.remove(ref);
            World world = Bukkit.getWorld(ref.world());
            if (world == null || !world.isChunkLoaded(ref.x(), ref.z())) continue;
            try {
                enhancer.enhance(world.getChunkAt(ref.x(), ref.z()));
            } catch (Exception ex) {
                getLogger().warning("Erreur sur le chunk " + ref.x() + "," + ref.z() + " : " + ex);
            }
        }
    }

    // ---------------------------------------------------------------- commande

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadAll();
                msg(sender, "Configuration rechargée.", NamedTextColor.GREEN);
            }
            case "enhance" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Commande réservée aux joueurs.", NamedTextColor.RED);
                    return true;
                }
                int radius = 4;
                if (args.length > 1) {
                    try {
                        radius = Math.max(1, Math.min(16, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                        msg(sender, "Rayon invalide (1 à 16).", NamedTextColor.RED);
                        return true;
                    }
                }
                World w = p.getWorld();
                int cx = p.getLocation().getBlockX() >> 4;
                int cz = p.getLocation().getBlockZ() >> 4;
                int added = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (w.isChunkLoaded(cx + dx, cz + dz) && enqueue(w, cx + dx, cz + dz)) added++;
                    }
                }
                msg(sender, added + " chunks chargés ajoutés à la file d'amélioration.", NamedTextColor.GREEN);
            }
            default -> {
                msg(sender, "StructuresPlus — chunks améliorés : " + enhancer.statChunks.get()
                        + " | blocs modifiés : " + enhancer.statBlocks.get()
                        + " | coffres bonus : " + enhancer.statChests.get()
                        + " | file : " + queue.size(), NamedTextColor.GOLD);
                if (sender instanceof Player p) {
                    Chunk chunk = p.getLocation().getChunk();
                    List<String> names = new ArrayList<>();
                    for (GeneratedStructure gs : chunk.getStructures()) {
                        names.add(gs.getStructure().key().value());
                    }
                    msg(sender, names.isEmpty()
                            ? "Aucune structure dans ce chunk."
                            : "Structures dans ce chunk : " + String.join(", ", names), NamedTextColor.YELLOW);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("info", "reload", "enhance")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private static void msg(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }
}
