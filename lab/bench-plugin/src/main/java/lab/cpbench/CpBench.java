package lab.cpbench;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.LookupOptions;

public final class CpBench extends JavaPlugin {
    // One worker thread, so benchmark runs never overlap each other.
    private static final int WRITE_CHUNKS = 8;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "cpbench"));

    @Override
    public void onDisable() {
        worker.shutdownNow();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 2 && args[0].equals("world")) {
                boolean existed = Bukkit.getWorld(args[1]) != null;
                if (!existed) {
                    new WorldCreator(args[1]).generator(new ChunkGenerator() { }).createWorld(); // void world
                }
                log("world " + args[1] + (existed ? " existing" : " created") + " loaded=" + (Bukkit.getWorld(args[1]) != null));
                return true;
            }
            if (args.length == 3 && args[0].equals("write")) {
                int rows = num(args[1]);
                World world = world(args[2]);
                // An API call on a chunk that is not loaded waits for a chunk load (about 30 rows/s), so write inside a force-loaded area.
                for (int x = 0; x < WRITE_CHUNKS; x++) {
                    for (int z = 0; z < WRITE_CHUNKS; z++) {
                        world.setChunkForceLoaded(x, z, true);
                    }
                }
                worker.submit(() -> write(rows, world));
                return true;
            }
            int count = 1;
            String[] scenario = args;
            if (args.length > 2 && args[0].equals("repeat")) {
                count = Integer.parseInt(args[1]);
                if (count < 1) {
                    throw new IllegalArgumentException("count must be at least 1");
                }
                scenario = Arrays.copyOfRange(args, 2, args.length);
            }
            Supplier<List<?>> lookup = prepare(scenario);
            if (lookup == null) {
                return false; // Bukkit prints the usage from plugin.yml
            }
            String name = String.join(" ", scenario);
            int runs = count;
            worker.submit(() -> run(name, lookup, runs));
            return true;
        }
        catch (IllegalArgumentException e) {
            sender.sendMessage("cpbench: " + e.getMessage());
            return false;
        }
    }

    // Main thread: resolve worlds, locations and blocks. The returned lookup runs on the worker.
    private Supplier<List<?>> prepare(String[] a) {
        CoreProtectAPI api = ((CoreProtect) getServer().getPluginManager().getPlugin("CoreProtect")).getAPI();
        int limit = -1;
        if (a.length > 0 && a[a.length - 1].startsWith("limit=")) {
            limit = Integer.parseInt(a[a.length - 1].substring("limit=".length()));
            a = Arrays.copyOf(a, a.length - 1);
        }
        int max = limit;
        String scenario = a.length == 0 ? "" : a[0];
        if (scenario.equals("radius") && a.length == 7) {
            Location location = new Location(world(a[1]), num(a[2]), num(a[3]), num(a[4]));
            int radius = num(a[5]);
            int time = num(a[6]);
            return max < 0 ? () -> api.performLookup(time, null, null, null, null, null, radius, location)
                    : () -> api.performPartialLookup(time, null, null, null, null, null, radius, location, 0, max);
        }
        if (scenario.equals("user") && a.length == 3) {
            List<String> users = List.of(a[1]);
            int time = num(a[2]);
            return max < 0 ? () -> api.performLookup(time, users, null, null, null, null, 0, null)
                    : () -> api.performPartialLookup(time, users, null, null, null, null, 0, null, 0, max);
        }
        if (scenario.equals("time") && a.length == 2) {
            // The legacy performLookup returns null for #global without a radius, so this uses the typed API (all worlds).
            LookupOptions.Builder options = LookupOptions.builder().time(num(a[1]));
            if (max >= 0) {
                options.limit(0, max);
            }
            LookupOptions built = options.build();
            return () -> api.blockLookup(built);
        }
        if (scenario.equals("block") && a.length == 5) {
            Block block = world(a[1]).getBlockAt(num(a[2]), num(a[3]), num(a[4]));
            return () -> api.blockLookup(block, 0); // 0 = no time limit
        }
        return null;
    }

    private void run(String name, Supplier<List<?>> lookup, int count) {
        double[] ms = new double[count];
        for (int i = 0; i < count; i++) {
            try {
                long start = System.nanoTime();
                List<?> rows = lookup.get();
                ms[i] = (System.nanoTime() - start) / 1e6;
                log(name + " rows=" + (rows == null ? "null" : rows.size()) + " ms=" + String.format(Locale.ROOT, "%.3f", ms[i]));
            }
            catch (Throwable t) {
                getLogger().log(Level.SEVERE, "[cp-bench] " + name + " error=" + t, t);
                return;
            }
        }
        if (count > 1) {
            Arrays.sort(ms);
            double median = (ms[(count - 1) / 2] + ms[count / 2]) / 2;
            log("repeat " + count + " " + name + String.format(Locale.ROOT, " min=%.3f median=%.3f max=%.3f", ms[0], median, ms[count - 1]));
        }
    }

    // Log `rows` placements through the API, then one at a fixed sentinel spot. The consumer writes in order,
    // so the sentinel becoming visible to a lookup means every earlier row is stored.
    private void write(int rows, World world) {
        CoreProtectAPI api = ((CoreProtect) getServer().getPluginManager().getPlugin("CoreProtect")).getAPI();
        Material[] types = { Material.STONE, Material.DIRT, Material.COBBLESTONE, Material.OAK_PLANKS, Material.GLASS };
        java.util.Random random = new java.util.Random(42);
        int span = WRITE_CHUNKS * 16;
        Block sentinel = world.getBlockAt(span - 1, 300, span - 1);
        long start = System.nanoTime();
        for (int i = 0; i < rows; i++) {
            Material type = types[i % types.length];
            Location location = new Location(world, random.nextInt(span), 40 + random.nextInt(60), random.nextInt(span));
            api.logPlacement("BenchWriter", location, type, type.createBlockData());
        }
        api.logPlacement("BenchWriter", sentinel.getLocation(), Material.STONE, Material.STONE.createBlockData());
        double queued = (System.nanoTime() - start) / 1e6;
        try {
            while (api.blockLookup(sentinel, 0).isEmpty()) {
                if (System.nanoTime() - start > 600_000_000_000L) {
                    log("write " + rows + " timeout after 600 s");
                    return;
                }
                Thread.sleep(50);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        double stored = (System.nanoTime() - start) / 1e6;
        log("write " + rows + String.format(Locale.ROOT, " queued_ms=%.0f ms=%.0f rows_per_s=%.0f", queued, stored, rows / (stored / 1000)));
    }

    private static World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new IllegalArgumentException("world not loaded: " + name + " (run /cpbench world " + name + ")");
        }
        return world;
    }

    private static int num(String value) {
        return Integer.parseInt(value);
    }

    private void log(String message) {
        getLogger().info("[cp-bench] " + message);
    }
}
