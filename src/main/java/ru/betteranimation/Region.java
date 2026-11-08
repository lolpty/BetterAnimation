package ru.betteranimation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Loaded only on servers exposing the Paper regional scheduler API. */
final class Region {
    private Region() {}

    static void global(Plugin plugin, Runnable action) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, action);
    }

    static void at(Plugin plugin, Location location, Runnable action) {
        Bukkit.getRegionScheduler().execute(plugin, location, action);
    }

    static Runnable repeat(Plugin plugin, Location location, Runnable action) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task -> action.run(), 1, 1)::cancel;
    }

    static void player(Plugin plugin, Player player, Runnable action) {
        player.getScheduler().execute(plugin, action, null, 1);
    }

    static boolean owns(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }
}
