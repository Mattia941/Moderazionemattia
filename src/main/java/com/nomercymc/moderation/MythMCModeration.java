package com.mythmc.moderation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.*;

public class MythMCModeration extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, List<String>> ipHistory = new HashMap<>();
    private final Map<UUID, MuteData> mutedPlayers = new HashMap<>();
    private final Map<UUID, Integer> warnMap = new HashMap<>();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final String PREFIX = "&4&l[ᴍʏᴛʜᴍᴄ] &8»&7 ";

    private static class MuteData {
        String reason;
        Date expiration;

        MuteData(String reason, Date expiration) {
            this.reason = reason;
            this.expiration = expiration;
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        String[] commands = {
            "ban", "tempban", "ipban", "tempipban", "unban",
            "mute", "tempmute", "unmute", "warn", "kick", "clearchat", "dupeip"
        };

        for (String cmd : commands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }

        getLogger().info("MythMC Sistema di Moderazione Caricato con successo!");
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("kick")) {
            if (!sender.hasPermission("mythmc.kick")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color(PREFIX + "&cUso: /kick <giocatore> [motivo]"));
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(color(PREFIX + "&cGiocatore non trovato."));
                return true;
            }
            String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
            target.kick(LEGACY.deserialize(color("&4&lMYTHMC — KICK\n\n&fSei stato cacciato!\n&fMotivo: &c" + reason)));
            sender.sendMessage(color(PREFIX + "&aHai cacciato &e" + target.getName()));
            return true;
        }

        if (cmd.equals("ban")) {
            if (!sender.hasPermission("mythmc.ban")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color(PREFIX + "&cUso: /ban <giocatore> [motivo]"));
                return true;
            }
            String targetName = args[0];
            String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
            
            Bukkit.getBanList(BanList.Type.NAME).addBan(targetName, reason, (Date) null, sender.getName());
            Player target = Bukkit.getPlayer(targetName);
            if (target != null && target.isOnline()) {
                target.kick(LEGACY.deserialize(color("&4&lMYTHMC — BAN\n\n&fSei stato bannato!\n&fMotivo: &c" + reason)));
            }
            sender.sendMessage(color(PREFIX + "&aHai bannato &e" + targetName));
            return true;
        }

        if (cmd.equals("tempban")) {
            if (!sender.hasPermission("mythmc.tempban")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(color(PREFIX + "&cUso: /tempban <giocatore> <1d/2h/30m> [motivo]"));
                return true;
            }
            String targetName = args[0];
            long durationMillis = parseTime(args[1]);
            if (durationMillis <= 0) {
                sender.sendMessage(color(PREFIX + "&cFormato tempo non valido!"));
                return true;
            }
            Date expiration = new Date(System.currentTimeMillis() + durationMillis);
            String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";

            Bukkit.getBanList(BanList.Type.NAME).addBan(targetName, reason, expiration, sender.getName());
            Player target = Bukkit.getPlayer(targetName);
            if (target != null && target.isOnline()) {
                target.kick(LEGACY.deserialize(color("&4&lMYTHMC — TEMPBAN\n\n&fBannato temporaneamente!\n&fMotivo: &c" + reason)));
            }
            sender.sendMessage(color(PREFIX + "&aHai tempbannato &e" + targetName));
            return true;
        }

        if (cmd.equals("unban")) {
            if (!sender.hasPermission("mythmc.unban")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color(PREFIX + "&cUso: /unban <giocatore>"));
                return true;
            }
            Bukkit.getBanList(BanList.Type.NAME).pardon(args[0]);
            sender.sendMessage(color(PREFIX + "&aHai sbannato &e" + args[0]));
            return true;
        }

        if (cmd.equals("mute") || cmd.equals("tempmute")) {
            if (!sender.hasPermission("mythmc.mute")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < (cmd.equals("tempmute") ? 2 : 1)) {
                sender.sendMessage(color(PREFIX + "&cUso: /" + cmd + " <giocatore> " + (cmd.equals("tempmute") ? "<tempo> " : "") + "[motivo]"));
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color(PREFIX + "&cGiocatore offline."));
                return true;
            }

            Date expiration = null;
            String reason;
            if (cmd.equals("tempmute")) {
                long duration = parseTime(args[1]);
                if (duration <= 0) {
                    sender.sendMessage(color(PREFIX + "&cTempo non valido!"));
                    return true;
                }
                expiration = new Date(System.currentTimeMillis() + duration);
                reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";
            } else {
                reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
            }

            mutedPlayers.put(target.getUniqueId(), new MuteData(reason, expiration));
            target.sendMessage(color(PREFIX + "&cSei stato mutato per: &f" + reason));
            sender.sendMessage(color(PREFIX + "&aHai mutato &e" + target.getName()));
            return true;
        }

        if (cmd.equals("unmute")) {
            if (!sender.hasPermission("mythmc.unmute")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color(PREFIX + "&cUso: /unmute <giocatore>"));
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null && mutedPlayers.containsKey(target.getUniqueId())) {
                mutedPlayers.remove(target.getUniqueId());
                target.sendMessage(color(PREFIX + "&aSei stato smutato!"));
                sender.sendMessage(color(PREFIX + "&aHai smutato &e" + target.getName()));
            } else {
                sender.sendMessage(color(PREFIX + "&cGiocatore non mutato o offline."));
            }
            return true;
        }

        if (cmd.equals("warn")) {
            if (!sender.hasPermission("mythmc.warn")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(color(PREFIX + "&cUso: /warn <giocatore> <motivo>"));
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color(PREFIX + "&cGiocatore non trovato."));
                return true;
            }
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            int warns = warnMap.getOrDefault(target.getUniqueId(), 0) + 1;
            warnMap.put(target.getUniqueId(), warns);

            target.sendMessage(color("&4&lMYTHMC &8» &cSei stato ammonito (#" + warns + ") per: &f" + reason));
            sender.sendMessage(color(PREFIX + "&aHai warnato &e" + target.getName()));
            return true;
        }

        if (cmd.equals("clearchat")) {
            if (!sender.hasPermission("mythmc.clearchat")) {
                sender.sendMessage(color(PREFIX + "&cNon hai il permesso."));
                return true;
            }
            for (int i = 0; i < 100; i++) {
                Bukkit.broadcast(Component.text(""));
            }
            Bukkit.broadcast(LEGACY.deserialize(color("&4&lMYTHMC &8» &eChat pulita da &c" + sender.getName() + "&e!")));
            return true;
        }

        return false;
    }

    private long parseTime(String timeStr) {
        try {
            char unit = timeStr.charAt(timeStr.length() - 1);
            long amount = Long.parseLong(timeStr.substring(0, timeStr.length() - 1));
            switch (unit) {
                case 's': return amount * 1000;
                case 'm': return amount * 60 * 1000;
                case 'h': return amount * 60 * 60 * 1000;
                case 'd': return amount * 24 * 60 * 60 * 1000;
                case 'w': return amount * 7 * 24 * 60 * 60 * 1000;
                default: return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        String ip = event.getAddress().getHostAddress();

        var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
        var ipBanList = Bukkit.getBanList(BanList.Type.IP);

        org.bukkit.BanEntry nameEntry = nameBanList.getBanEntry(name);
        org.bukkit.BanEntry ipEntry = ipBanList.getBanEntry(ip);

        org.bukkit.BanEntry activeEntry = nameEntry != null ? nameEntry : ipEntry;

        if (activeEntry != null) {
            Date expiration = activeEntry.getExpiration();
            
            if (expiration != null && new Date().after(expiration)) {
                if (nameEntry != null) nameBanList.pardon(name);
                if (ipEntry != null) ipBanList.pardon(ip);
                return;
            }

            String reason = activeEntry.getReason() != null ? activeEntry.getReason() : "Nessun motivo";
            String source = activeEntry.getSource() != null ? activeEntry.getSource() : "Console";
            String durationStr = expiration == null ? "Permanente" : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(expiration);

            Component kickScreen = LEGACY.deserialize(color(
                "&4&lMYTHMC — BAN\n\n&fBannato dal server!\n\n&fMotivo: &c" + reason + "\n&fScadenza: &e" + durationStr + "\n&fStaffer: &b" + source
            ));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickScreen);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getAddress() != null) {
            String ip = player.getAddress().getAddress().getHostAddress();
            ipHistory.putIfAbsent(ip, new ArrayList<>());
            if (!ipHistory.get(ip).contains(player.getName())) {
                ipHistory.get(ip).add(player.getName());
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (mutedPlayers.containsKey(uuid)) {
            MuteData data = mutedPlayers.get(uuid);

            if (data.expiration != null && new Date().after(data.expiration)) {
                mutedPlayers.remove(uuid);
                return;
            }

            event.setCancelled(true);
            player.sendMessage(color(PREFIX + "&cSei attualmente mutato per: &f" + data.reason));
        }
    }
}
