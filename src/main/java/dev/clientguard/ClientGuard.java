package dev.clientguard;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class ClientGuard extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Set<UUID> handled = new HashSet<>();

    private List<String> brandBlacklist, channelBlacklist, brandWhitelist, commands;
    private List<Integer> checkDelays;
    private String action, kickMessage;
    private boolean allowMissingBrand, alertStaff, logConsole;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ClientGuard enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        action = c.getString("action", "KICK").toUpperCase(Locale.ROOT);
        kickMessage = c.getString("kick-message", "&cUnauthorized client detected.");
        commands = c.getStringList("commands");
        checkDelays = c.getIntegerList("check-delays");
        if (checkDelays.isEmpty()) checkDelays = List.of(40, 100, 200);
        brandBlacklist = lower(c.getStringList("brand-blacklist"));
        channelBlacklist = lower(c.getStringList("channel-blacklist"));
        brandWhitelist = lower(c.getStringList("brand-whitelist"));
        allowMissingBrand = c.getBoolean("allow-missing-brand", true);
        alertStaff = c.getBoolean("alert-staff", true);
        logConsole = c.getBoolean("log-to-console", true);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (int delay : checkDelays) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) check(p);
            }, delay);
        }
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        String ch = e.getChannel().toLowerCase(Locale.ROOT);
        Player p = e.getPlayer();
        for (String bad : channelBlacklist) {
            if (ch.contains(bad)) {
                punish(p, "channel:" + e.getChannel());
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        handled.remove(e.getPlayer().getUniqueId());
    }

    private void check(Player p) {
        if (handled.contains(p.getUniqueId()) || p.hasPermission("clientguard.bypass")) return;

        String brand = p.getClientBrandName();
        if (brand == null) {
            if (!allowMissingBrand) punish(p, "no client brand");
        } else {
            String b = brand.toLowerCase(Locale.ROOT);
            boolean whitelisted = brandWhitelist.stream().anyMatch(w -> b.equals(w) || b.startsWith(w));
            if (!whitelisted) {
                for (String bad : brandBlacklist) {
                    if (b.contains(bad)) {
                        punish(p, "brand:" + brand);
                        return;
                    }
                }
            }
        }

        for (String ch : p.getListeningPluginChannels()) {
            String c = ch.toLowerCase(Locale.ROOT);
            for (String bad : channelBlacklist) {
                if (c.contains(bad)) {
                    punish(p, "channel:" + ch);
                    return;
                }
            }
        }
    }

    private void punish(Player p, String reason) {
        if (p.hasPermission("clientguard.bypass") || !handled.add(p.getUniqueId())) return;

        String msg = "§c[ClientGuard] §f" + p.getName() + " §7flagged: §e" + reason;
        if (logConsole) getLogger().warning(p.getName() + " flagged: " + reason);
        if (alertStaff) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("clientguard.alerts")) staff.sendMessage(msg);
            }
        }

        switch (action) {
            case "KICK" -> p.kick(LEGACY.deserialize(kickMessage.replace("%reason%", reason)));
            case "COMMAND" -> {
                for (String cmd : commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            cmd.replace("%player%", p.getName()).replace("%reason%", reason));
                }
            }
            default -> { }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/clientguard check <player> | reload");
            return true;
        }
        if (a[0].equalsIgnoreCase("reload")) {
            loadSettings();
            s.sendMessage("§aClientGuard config reloaded.");
            return true;
        }
        if (a[0].equalsIgnoreCase("check") && a.length >= 2) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                s.sendMessage("§cPlayer not found.");
                return true;
            }
            s.sendMessage("§6Brand: §f" + t.getClientBrandName());
            s.sendMessage("§6Channels: §f" + String.join(", ", t.getListeningPluginChannels()));
            return true;
        }
        return false;
    }
}
