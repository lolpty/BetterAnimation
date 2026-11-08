package ru.betteranimation;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class BetterAnimation extends JavaPlugin implements Listener, TabExecutor {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("betteranimation:models".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private record Pack(Map<String, Model> models, byte[] zip, byte[] hash) {}
    private record Stamp(String name, long size, java.nio.file.attribute.FileTime modified) {}
    private final Map<UUID, Playback> active = new ConcurrentHashMap<>();
    private volatile Pack current;
    private ScheduledExecutorService worker;
    private HttpServer http;
    private Path models;
    private String publicUrl;
    private boolean regional;
    private List<Stamp> observed;
    private List<Stamp> imported;

    @Override public void onEnable() {
        saveDefaultConfig();
        models = getDataFolder().toPath().resolve("models");
        publicUrl = getConfig().getString("pack.public-url", "").strip();
        try {
            Files.createDirectories(models);
            try { Bukkit.class.getMethod("getRegionScheduler"); regional = true; }
            catch (NoSuchMethodException ignored) { regional = false; }
            if (!publicUrl.isEmpty()) {
                URI uri = URI.create(publicUrl);
                if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                        || uri.getUserInfo() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException("pack.public-url must be an HTTP(S) URL");
                }
                try {
                    Player.class.getMethod("addResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class);
                } catch (NoSuchMethodException ignored) {
                    getLogger().warning("This server API lacks addResourcePack. Install the generated ZIP manually.");
                    publicUrl = "";
                }
            }
            if (getConfig().getBoolean("pack.http.enabled")) startHttp();
        } catch (Exception error) {
            getLogger().severe("Startup failed: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("betteranimation").setExecutor(this);
        getCommand("betteranimation").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterAnimation-Pack");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> rebuild(false), 0, 2, TimeUnit.SECONDS);
    }

    private void startHttp() throws IOException {
        http = HttpServer.create(new InetSocketAddress(getConfig().getString("pack.http.bind", "0.0.0.0"),
            getConfig().getInt("pack.http.port", 8123)), 16);
        http.createContext("/betteranimation.zip", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/betteranimation.zip")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                Pack pack = current;
                if (pack == null) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, pack.zip().length);
                exchange.getResponseBody().write(pack.zip());
            }
        });
        http.start();
    }

    private void rebuild(boolean force) {
        try {
            List<Path> files;
            try (var stream = Files.list(models)) {
                files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.toString().endsWith(".bbmodel") || path.toString().endsWith(".ajblueprint"))
                    .sorted().toList();
            }
            List<Stamp> stamps = new ArrayList<>();
            for (Path file : files) {
                stamps.add(new Stamp(file.getFileName().toString(), Files.size(file), Files.getLastModifiedTime(file)));
            }
            boolean stable = stamps.equals(observed);
            observed = List.copyOf(stamps);
            if (!force && (stamps.equals(imported) || !stable && current != null)) return;
            Map<String, Model> loaded = new TreeMap<>();
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                put(zip, "pack.mcmeta", """
                    {"pack":{"description":"BetterAnimation models","pack_format":46,
                    "supported_formats":[46,97],"min_format":[46,0],"max_format":[97,1]}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (var icon = getResource("pack.png")) {
                    if (icon == null) throw new IOException("Missing bundled pack icon");
                    put(zip, "pack.png", icon.readAllBytes());
                }
                for (Path file : files) importModel(file, loaded, zip);
            }
            if (Thread.currentThread().isInterrupted() || !isEnabled()) return;
            byte[] data = bytes.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            Path target = getDataFolder().toPath().resolve("BetterAnimation.zip");
            Path temp = Files.createTempFile(target.getParent(), "pack-", ".tmp");
            try {
                Files.write(temp, data);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally {
                Files.deleteIfExists(temp);
            }
            Pack old = current;
            Pack pack = new Pack(Collections.unmodifiableMap(loaded), data, hash);
            current = pack;
            imported = List.copyOf(stamps);
            if (old == null || !Arrays.equals(old.hash(), hash)) {
                global(() -> Bukkit.getOnlinePlayers().forEach(player -> sendPack(player, pack)));
            }
            getLogger().info("Built BetterAnimation.zip with " + loaded.size() + " model(s). SHA-1 " + HexFormat.of().formatHex(hash));
        } catch (Exception e) {
            getLogger().warning("Pack build failed: " + e.getMessage());
        }
    }

    private void importModel(Path file, Map<String, Model> loaded, ZipOutputStream zip) throws IOException {
        Path stage = Files.createTempDirectory(getDataFolder().toPath(), "model-");
        try {
            Model model;
            try {
                model = Model.load(file, stage);
                if (loaded.containsKey(model.id)) throw new IOException("Duplicate model ID " + model.id);
            } catch (IOException | RuntimeException error) {
                getLogger().warning("Skipped " + file.getFileName() + ": " + error.getMessage());
                return;
            }
            try (var files = Files.walk(stage)) {
                for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                    put(zip, stage.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
            loaded.put(model.id, model);
        } finally {
            try (var files = Files.walk(stage)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    @EventHandler public void joined(PlayerJoinEvent event) {
        Pack pack = current;
        if (pack != null) sendPack(event.getPlayer(), pack);
    }

    private void sendPack(Player player, Pack pack) {
        if (publicUrl.isEmpty()) return;
        Runnable send = () -> {
            if (current != pack || !player.isOnline()) return;
            player.addResourcePack(PACK_ID, publicUrl, pack.hash().clone(), "BetterAnimation models", false);
        };
        if (regional) Region.player(this, player, send);
        else Bukkit.getScheduler().runTask(this, send);
    }

    private void global(Runnable action) {
        if (regional) Region.global(this, action);
        else Bukkit.getScheduler().runTask(this, action);
    }

    private void at(Location position, Runnable action) {
        if (regional) Region.at(this, position, action);
        else Bukkit.getScheduler().runTask(this, action);
    }

    private final class Playback {
        final UUID id = UUID.randomUUID();
        final Location position;
        final List<ItemDisplay> displays = new ArrayList<>();
        final List<Integer> bones = new ArrayList<>();
        final Model model;
        final Model.Animation animation;
        final float yaw;
        Runnable cancel = () -> {};
        int tick;

        Playback(Location position, Model model, Model.Animation animation, float yaw) {
            this.position = position;
            this.model = model;
            this.animation = animation;
            this.yaw = yaw;
        }

        void start() {
            try {
                var pose = model.pose(animation, 0, yaw);
                for (int index = 0; index < model.bones.size(); index++) {
                    String item = model.bones.get(index).item();
                    if (item == null) continue;
                    var stack = new ItemStack(Material.PAPER);
                    var meta = stack.getItemMeta();
                    meta.setItemModel(new NamespacedKey("betteranimation", item));
                    stack.setItemMeta(meta);
                    var transform = pose[index];
                    ItemDisplay display = position.getWorld().spawn(position, ItemDisplay.class, entity -> {
                        entity.setPersistent(false);
                        entity.setItemStack(stack);
                        entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                        entity.setTransformationMatrix(transform);
                        entity.setInterpolationDuration(1);
                    });
                    displays.add(display);
                    bones.add(index);
                }
                cancel = regional ? Region.repeat(BetterAnimation.this, position, this::advance)
                    : Bukkit.getScheduler().runTaskTimer(BetterAnimation.this, this::advance, 1, 1)::cancel;
            } catch (RuntimeException error) {
                stop();
                getLogger().warning("Playback failed: " + error.getMessage());
            }
        }

        void advance() {
            try {
                if (displays.stream().anyMatch(display -> !display.isValid())) { stop(); return; }
                if (++tick > animation.ticks()) {
                    if (!animation.loop()) { stop(); return; }
                    tick = 0;
                }
                var pose = model.pose(animation, tick, yaw);
                for (int i = 0; i < displays.size(); i++) {
                    var entity = displays.get(i);
                    entity.setInterpolationDelay(0);
                    entity.setTransformationMatrix(pose[bones.get(i)]);
                }
            } catch (RuntimeException error) {
                stop();
                getLogger().warning("Playback failed: " + error.getMessage());
            }
        }

        void stop() {
            active.remove(id, this);
            cancel.run();
            displays.forEach(Entity::remove);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/ba list | reload | pack | play <model> <animation> | stop <id|all>");
            return true;
        }
        Pack pack = current;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> { worker.execute(() -> rebuild(true)); sender.sendMessage("Pack rebuild queued. Check the server console for results."); }
            case "list" -> {
                if (pack == null || pack.models().isEmpty()) sender.sendMessage("No imported models.");
                else pack.models().forEach((id, model) -> sender.sendMessage(id + ": " + String.join(", ", model.animations.keySet())));
            }
            case "pack" -> {
                if (pack == null) sender.sendMessage("Pack is still building.");
                else if (!(sender instanceof Player player) || publicUrl.isEmpty()) sender.sendMessage("plugins/BetterAnimation/BetterAnimation.zip");
                else sendPack(player, pack);
            }
            case "play" -> {
                if (!(sender instanceof Player player) || args.length != 3) {
                    sender.sendMessage("In game: /ba play <model> <animation>");
                    return true;
                }
                Model model = pack == null ? null : pack.models().get(args[1]);
                Model.Animation animation = model == null ? null : model.animations.get(args[2]);
                if (animation == null) { sender.sendMessage("Unknown model or animation. Use /ba list."); return true; }
                Location position = player.getLocation();
                float yaw = position.getYaw() + 180;
                var direction = position.getDirection().setY(0);
                if (direction.lengthSquared() > 0) position.add(direction.normalize().multiply(3));
                position.setYaw(0);
                position.setPitch(0);
                var playback = new Playback(position, model, animation, yaw);
                active.put(playback.id, playback);
                at(position, () -> { if (active.containsKey(playback.id)) playback.start(); });
                sender.sendMessage("Playback queued: " + playback.id);
            }
            case "stop" -> {
                if (args.length != 2) { sender.sendMessage("/ba stop <id|all>"); return true; }
                try {
                    Collection<Playback> selected;
                    if (args[1].equalsIgnoreCase("all")) selected = List.copyOf(active.values());
                    else {
                        var playback = active.get(UUID.fromString(args[1]));
                        selected = playback == null ? List.of() : List.of(playback);
                    }
                    for (var playback : selected) {
                        active.remove(playback.id, playback);
                        at(playback.position, playback::stop);
                    }
                    sender.sendMessage("Stop queued for " + selected.size() + " playback(s).");
                } catch (IllegalArgumentException error) { sender.sendMessage("Invalid playback UUID."); }
            }
            default -> sender.sendMessage("Unknown subcommand. Use /ba.");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("betteranimation.admin") || args.length == 0) return List.of();
        Pack pack = current;
        Collection<String> choices = List.of();
        if (args.length == 1) choices = List.of("list", "reload", "pack", "play", "stop");
        else if (args[0].equalsIgnoreCase("play") && pack != null) {
            if (args.length == 2) choices = pack.models().keySet();
            if (args.length == 3 && pack.models().containsKey(args[1])) choices = pack.models().get(args[1]).animations.keySet();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            choices = new ArrayList<>(active.keySet().stream().map(UUID::toString).toList());
            choices.add("all");
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(option -> option.startsWith(prefix)).sorted().toList();
    }

    @Override public void onDisable() {
        if (worker != null) worker.shutdownNow();
        if (http != null) http.stop(0);
        for (Playback playback : active.values()) {
            playback.cancel.run();
            if (!regional || Region.owns(playback.position)) playback.stop();
        }
        active.clear();
    }

}
