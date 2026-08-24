package com.nomercymc.moderation;

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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoMercyMC extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, List<String>> ipHistory = new HashMap<>();
    private final Map<UUID, String> mutedPlayers = new HashMap<>(); // UUID -> Motivo Mute
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        
        Objects.requireNonNull(getCommand("ban")).setExecutor(this);
        Objects.requireNonNull(getCommand("mute")).setExecutor(this);
        Objects.requireNonNull(getCommand("warn")).setExecutor(this);
        Objects.requireNonNull(getCommand("kick")).setExecutor(this);
        Objects.requireNonNull(getCommand("clearchat")).setExecutor(this);
        Objects.requireNonNull(getCommand("dupeip")).setExecutor(this);

        getLogger().info("NoMercyMC attivato con successo su Purpur 1.21.1!");
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

    // Blocco chat per giocatori mutati
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (mutedPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            String reason = mutedPlayers.get(player.getUniqueId());
            player.sendMessage(color("&cSei attualmente silenziato per: &e" + reason));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // ==========================================
        // COMANDO /BAN
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("ban")) {
            if (!sender.hasPermission("nomercy.admin")) {
                sender.sendMessage(color("&cNon hai il permesso per eseguire questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUso corretto: /ban <giocatore> [tempo] <motivo>"));
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

            String reason = buildReason(args, reasonStartIndex);
            String executor = sender.getName();

            Bukkit.getBanList(BanList.Type.NAME).addBan(targetName, reason, expiration, executor);

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

            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                Component kickScreen = color(
                    "&c&lNOMERCYMC NETWORK\n\n" +
                    "&7Sei stato bannato dal nostro server.\n\n" +
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
        // COMANDO /MUTE
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("mute")) {
            if (!sender.hasPermission("nomercy.staff")) {
                sender.sendMessage(color("&cNon hai il permesso per usare questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUso corretto: /mute <giocatore> <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String reason = buildReason(args, 1);
            mutedPlayers.put(target.getUniqueId(), reason);

            Component muteBroadcast = color(
                "&c--------------------------------------------------\n" +
                "&c&lNOMERCYMC &8» &fUn utente è stato silenziato!\n \n" +
                "&e&lMUTE &8» &fGiocatore: &e" + target.getName() + "\n" +
                "&e&lMUTE &8» &fSanzionato da: &c" + sender.getName() + "\n" +
                "&e&lMUTE &8» &fMotivo: &f" + reason + "\n" +
                "&c--------------------------------------------------"
            );

            Bukkit.broadcast(muteBroadcast);
            return true;
        }

        // ==========================================
        // COMANDO /WARN
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("warn")) {
            if (!sender.hasPermission("nomercy.staff")) {
                sender.sendMessage(color("&cNon hai il permesso per usare questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUso corretto: /warn <giocatore> <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String reason = buildReason(args, 1);

            Component warnMessage = color(
                "&c--------------------------------------------------\n" +
                "&c&lNOMERCYMC &8» &c&lSEI STATO WARNATO!\n" +
                "&fMotivo: &e" + reason + "\n" +
                "&fStaffer: &c" + sender.getName() + "\n" +
                "&c--------------------------------------------------"
            );

            target.sendMessage(warnMessage);
            sender.sendMessage(color("&aHai inviato un warn a &e" + target.getName()));
            return true;
        }

        // ==========================================
        // COMANDO /KICK
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("kick")) {
            if (!sender.hasPermission("nomercy.staff")) {
                sender.sendMessage(color("&cNon hai il permesso per usare questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUso corretto: /kick <giocatore> <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String reason = buildReason(args, 1);
            Component kickMessage = color(
                "&c&lNOMERCYMC NETWORK\n\n" +
                "&7Sei stato espulso dal server.\n\n" +
                "&fMotivo: &c" + reason + "\n" +
                "&fEspulso da: &e" + sender.getName()
            );

            target.kick(kickMessage);
            Bukkit.broadcast(color("&8[&cNoMercyMC&8] &e" + target.getName() + " &fè stato espulso per: &c" + reason));
            return true;
        }

        // ==========================================
        // COMANDO /CLEARCHAT
        // ==========================================
        if (cmd.getName().equalsIgnoreCase("clearchat")) {
            if (!sender.hasPermission("nomercy.staff")) {
                sender.sendMessage(color("&cNon hai il permesso per usare questo comando."));
                return true;
            }

            for (int i = 0; i < 100; i++) {
                Bukkit.broadcast(Component.text(" "));
            }

            Bukkit.broadcast(color("&c--------------------------------------------------"));
            Bukkit.broadcast(color("&c&lNOMERCYMC &8» &fLa chat è stata pulita da &e" + sender.getName()));
            Bukkit.broadcast(color("&c--------------------------------------------------"));
            return true;
        }

        // ==========================================
        // COMANDO /DUPEIP (RILEVA BAN E MUTE)
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
                boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(acc);
                Player pAcc = Bukkit.getPlayer(acc);
                boolean isMuted = pAcc != null && mutedPlayers.containsKey(pAcc.getUniqueId());

                String status = "&a[PULITO]";
                if (isBanned) {
                    status = "&c[BANNATO]";
                } else if (isMuted) {
                    status = "&e[MUTATO]";
                }

                sender.sendMessage(color("&8» &f" + acc + " " + status));
            }

            sender.sendMessage(color("&c--------------------------------------------------"));
            return true;
        }

        return false;
    }

    private String buildReason(String[] args, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        return sb.toString().trim();
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

    private Component color(String text) {
        return LEGACY.deserialize(text);
    }
}
