package com.nomercymc.bans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoMercyBans extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, List<String>> ipHistory = new HashMap<>();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ban")).setExecutor(this);
        Objects.requireNonNull(getCommand("dupeip")).setExecutor(this);
        getLogger().info("NoMercyBans attivato per Purpur 1.21.1!");
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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // ==========================================
        // COMANDO /BAN (PERMANENTE O TEMPORANEO)
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("ban")) {
            if (!sender.hasPermission("nomercy.admin")) {
                sender.sendMessage(color("&cNon hai il permesso per eseguire questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUso corretto: /ban <giocatore> [tempo] <motivo>"));
                sender.sendMessage(color("&7Esempi tempo: 1d (1 giorno), 12h (12 ore), 30m (30 min)"));
                return true;
            }

            String targetName = args[0];
            long durationMillis = parseTime(args[1]);

            Date expiration = null;
            String durationString = "Permanente";
            int reasonStartIndex = 1;

            if (durationMillis > 0) {
                expiration = new Date(System.currentTimeMillis() + durationMillis);
                durationString = args[1];
                reasonStartIndex = 2;
            }

            if (reasonStartIndex >= args.length) {
                sender.sendMessage(color("&cDevi specificare un motivo per il ban!"));
                return true;
            }

            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = reasonStartIndex; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();
            String executor = sender.getName();

            // API 1.21: Usa BanList.Type.PROFILE anziché NAME
            Bukkit.getBanList(BanList.Type.PROFILE).addBan(
                    Bukkit.createProfile(targetName),
                    reason,
                    expiration,
                    executor
            );

            // Annuncio broadcast CoralMC con Adventure API
            Component banBroadcast = color(
                "&c--------------------------------------------------\n" +
                "&c&lNOMERCYMC &8» &fUn utente è stato sanzionato!\n \n" +
                "&c&lBAN &8» &fGiocatore: &e" + targetName + "\n" +
                "&c&lBAN &8» &fSanzionato da: &c" + executor + "\n" +
                "&c&lBAN &8» &fMotivo: &f" + reason + "\n" +
                "&c&lBAN &8» &fDurata: &e" + durationString + "\n" +
                "&c--------------------------------------------------"
            );

            Bukkit.broadcast(banBroadcast);

            // Disconnessione utente se online
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                Component kickScreen = color(
                    "&c&lNOMERCYMC NETWORK\n\n" +
                    "&7Sei stato sospeso dal nostro server.\n\n" +
                    "&f&lINFORMAZIONI SANZIONE\n" +
                    "&8» &fMotivo: &c" + reason + "\n" +
                    "&8» &fSanzionato da: &e" + executor + "\n" +
                    "&8» &fScadenza: &e" + durationString + "\n\n" +
                    "&7Se ritieni che sia un errore, fai ricorso su:\n" +
                    "&c&ndiscord.gg/nomercymc"
                );
                target.kick(kickScreen);
            }
            return true;
        }

        // ==========================================
        // COMANDO /DUPEIP
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("dupeip")) {
            if (!sender.hasPermission("nomercy.staff")) {
                sender.sendMessage(color("&cNon hai il permesso per usare questo comando."));
                return true;
            }

            if (args.length < 1) {
                sender.sendMessage(color("&cUso corretto: /dupeip <giocatore>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || target.getAddress() == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String ip = target.getAddress().getAddress().getHostAddress();
            List<String> accounts = ipHistory.getOrDefault(ip, Collections.singletonList(target.getName()));

            sender.sendMessage(color("&c--------------------------------------------------"));
            sender.sendMessage(color("&c&lNOMERCYMC &8» &fAccount collegati a &e" + target.getName() + "&f:"));

            for (String acc : accounts) {
                boolean isBanned = Bukkit.getBanList(BanList.Type.PROFILE).isBanned(Bukkit.createProfile(acc));
                String status = isBanned ? "&c[BANNATO]" : "&a[PULITO]";
                sender.sendMessage(color("&8» &f" + acc + " " + status));
            }

            sender.sendMessage(color("&c--------------------------------------------------"));
            return true;
        }

        return false;
    }

    private long parseTime(String input) {
        Pattern pattern = Pattern.compile("^(\\d+)([smhd])$");
        Matcher matcher = pattern.matcher(input.toLowerCase());

        if (!matcher.matches()) {
            return -1;
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);

        return switch (unit) {
            case "s" -> value * 1000L;
            case "m" -> value * 1000L * 60;
            case "h" -> value * 1000L * 60 * 60;
            case "d" -> value * 1000L * 60 * 60 * 24;
            default -> -1;
        };
    }

    // Helper per convertire i codici colore legacy in Adventure Component (1.21+)
    private Component color(String text) {
        return LEGACY.deserialize(text);
    }
}w
