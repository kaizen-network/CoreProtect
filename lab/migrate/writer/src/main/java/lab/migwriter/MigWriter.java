package lab.migwriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

/**
 * Logs placements by the user "MigWriter" through the CoreProtect API at a steady rate, inside an 8x8 chunk area that
 * is force-loaded first, so no call waits for a chunk load. Logs the exact number of calls that returned true.
 *
 * With "blocks" as the last argument it cycles through every block material instead of four, so CoreProtect hands
 * out new material and block data ids while it runs, and writes plugins/MigWriter/expected-<world>.csv
 * (x,y,z,material as CoreProtect stores it) for every logged row.
 */
public final class MigWriter extends JavaPlugin {
    private static final int CHUNKS = 8;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean blocks = args.length == 4 && args[3].equals("blocks");
        if ((args.length != 3 && !blocks) || getServer().getWorld(args[2]) == null) {
            return false;
        }
        int rows = Integer.parseInt(args[0]);
        int perSecond = Integer.parseInt(args[1]);
        World world = getServer().getWorld(args[2]);
        for (int x = 0; x < CHUNKS; x++) {
            for (int z = 0; z < CHUNKS; z++) {
                world.setChunkForceLoaded(x, z, true);
                world.getChunkAt(x, z).load(true);
            }
        }
        CoreProtectAPI api = ((CoreProtect) getServer().getPluginManager().getPlugin("CoreProtect")).getAPI();
        List<Material> types = new ArrayList<>(List.of(Material.STONE, Material.DIRT, Material.OAK_PLANKS, Material.GLASS));
        if (blocks) {
            types.clear();
            for (Material material : Material.values()) {
                // Heads and skulls are left out: CoreProtect stored no block row for an API placement of them in the tests.
                String name = material.name();
                if (material.isBlock() && !material.isAir() && !material.isLegacy() && !name.endsWith("_HEAD") && !name.endsWith("_SKULL")) {
                    types.add(material);
                }
            }
        }
        Path expected = getDataFolder().toPath().resolve("expected-" + world.getName() + ".csv");
        Thread thread = new Thread(() -> {
            getLogger().info("[mig-writer] start rows=" + rows + " per_second=" + perSecond + " types=" + types.size());
            long start = System.nanoTime();
            int logged = 0;
            try (PrintWriter out = blocks ? new PrintWriter(Files.newBufferedWriter(prepare(expected), StandardCharsets.UTF_8)) : null) {
                for (int i = 0; i < rows; i++) {
                    Material type = types.get(i % types.size());
                    Location location = new Location(world, i % (CHUNKS * 16), 64 + (i / (CHUNKS * 16 * CHUNKS * 16)) % 200, (i / (CHUNKS * 16)) % (CHUNKS * 16));
                    if (api.logPlacement("MigWriter", location, type, type.createBlockData())) {
                        logged++;
                        if (out != null) {
                            out.println(location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ() + ",minecraft:" + type.name().toLowerCase(Locale.ROOT));
                        }
                    }
                    long wait = start + (i + 1L) * 1_000_000_000L / perSecond - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    }
                }
            }
            catch (IOException | InterruptedException e) {
                getLogger().warning("[mig-writer] stopped: " + e);
            }
            getLogger().info("[mig-writer] done logged=" + logged + " of " + rows + " ms=" + (System.nanoTime() - start) / 1_000_000L);
        }, "mig-writer");
        thread.start();
        return true;
    }

    private static Path prepare(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return file;
    }
}
