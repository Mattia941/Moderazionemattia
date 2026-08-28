package com.aternixmc.moderation;

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
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AternixMC extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, List<String>> ipHistory = new HashMap<>();
    private final Map<UUID, MuteData> mutedPlayers = new HashMap<>();
    private final Map<UUID, Integer> warnMap = new HashMap<>();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

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

        getLogger().info("AternixMC Sistema di Moderazione Caricato!");
    }

    // ==========================================
    // CONTROLLO BAN AL RIENTRO (PRE-LOGIN)
    // ==========================================
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
            
            // Se il ban temporaneo è scaduto, lo rimuoviamo e facciamo entrare l'utente
            if (expiration != null && new Date().after(expiration)) {
                if (nameEntry != null) nameBanList.pardon(name);
                if (ipEntry != null) ipBanList.pardon(ip);
                return;
            }

            String reason = activeEntry.getReason() != null ? activeEntry.getReason() : "Nessun motivo specificato";
            String source = activeEntry.getSource() != null ? activeEntry.getSource() : "Console";
            String durationStr = expiration == null ? "Permanente" : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(expiration);

            Component kickScreen = getBanScreen(reason, source, durationStr);
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
            String timeStr = data.expiration == null ? "Permanente" : "Temporaneo";
            player.sendMessage(color("&5--------------------------------------------------\n" +
                    "&d&lATERNIX&5&lMC &8» &cSei attualmente silenziato!\n" +
                    "&fMotivo: &d" + data.reason + "\n" +
                    "&fDurata: &d" + timeStr + "\n" +
                    "&5--------------------------------------------------"));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String commandName = cmd.getName().toLowerCase();

        if (commandName.equals("ban") || commandName.equals("tempban") || commandName.equals("ipban") || commandName.equals("tempipban")) {
            if (!sender.hasPermission("aternixmc.admin")) {
                sender.sendMessage(color("&cNon hai il permesso per eseguire questo comando."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /" + commandName + " <giocatore/IP> [tempo] <motivo>"));
                return true;
            }

            String targetName = args[0];
            boolean isIpBan = commandName.contains("ip");
            long durationMillis = -1;
            int reasonStartIndex = 1;

            if (commandName.startsWith("temp") || parseTime(args[1]) > 0) {
                durationMillis = parseTime(args[1]);
                if (durationMillis > 0) {
                    reasonStartIndex = 2;
                }
            }

            if (reasonStartIndex >= args.length) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cDevi specificare un motivo per la sanzione!"));
                return true;
            }

            Date expiration = durationMillis > 0 ? new Date(System.currentTimeMillis() + durationMillis) : null;
            String durationString = durationMillis > 0 ? args[1] : "Permanente";
            String reason = buildReason(args, reasonStartIndex);
            String executor = sender.getName();

            if (isIpBan) {
                Player target = Bukkit.getPlayer(targetName);
                String ipToBan = target != null && target.getAddress() != null ? 
                        target.getAddress().getAddress().getHostAddress() : targetName;
                
                Bukkit.getBanList(BanList.Type.IP).addBan(ipToBan, reason, expiration, executor);
            } else {
                Bukkit.getBanList(BanList.Type.NAME).addBan(targetName, reason, expiration, executor);
            }

            // Annuncio Globale
            Component banBroadcast = color(
                "&5--------------------------------------------------\n" +
                "&d&lATERNIX&5&lMC &8» &fUn utente è stato sanzionato!\n \n" +
                "&c&lBAN &8» &fGiocatore: &d" + targetName + "\n" +
                "&c&lBAN &8» &fSanzionato da: &5" + executor + "\n" +
                "&c&lBAN &8» &fMotivo: &f" + reason + "\n" +
                "&c&lBAN &8» &fDurata: &d" + durationString + "\n" +
                "&5--------------------------------------------------"
            );
            Bukkit.broadcast(banBroadcast);

            notifyStaff("&d&lSTAFF ALERT &8» &5" + executor + " &fha bannato &c" + targetName + " &f(&d" + durationString + "&f) per: &c" + reason);

            // Kick se il giocatore è online al momento del ban
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                target.kick(getBanScreen(reason, executor, durationString));
            }
            return true;
        }

        if (commandName.equals("unban")) {
            if (!sender.hasPermission("aternixmc.admin")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /unban <giocatore/IP>"));
                return true;
            }

            Bukkit.getBanList(BanList.Type.NAME).pardon(args[0]);
            Bukkit.getBanList(BanList.Type.IP).pardon(args[0]);
            
            sender.sendMessage(color("&dGiocatore/IP " + args[0] + " sbannato con successo!"));
            notifyStaff("&d&lSTAFF ALERT &8» &5" + sender.getName() + " &fha sbannato &d" + args[0]);
            return true;
        }

        if (commandName.equals("mute") || commandName.equals("tempmute")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /" + commandName + " <giocatore> [tempo] <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

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
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cDevi specificare un motivo per il mute!"));
                return true;
            }

            String reason = buildReason(args, reasonStartIndex);
            mutedPlayers.put(target.getUniqueId(), new MuteData(reason, expiration));

            Component muteBroadcast = color(
                "&5--------------------------------------------------\n" +
                "&d&lATERNIX&5&lMC &8» &fUn utente è stato silenziato!\n \n" +
                "&d&lMUTE &8» &fGiocatore: &d" + target.getName() + "\n" +
                "&d&lMUTE &8» &fSanzionato da: &5" + sender.getName() + "\n" +
                "&d&lMUTE &8» &fMotivo: &f" + reason + "\n" +
                "&d&lMUTE &8» &fDurata: &d" + durationString + "\n" +
                "&5--------------------------------------------------"
            );
            Bukkit.broadcast(muteBroadcast);
            notifyStaff("&d&lSTAFF ALERT &8» &5" + sender.getName() + " &fha mutato &d" + target.getName() + " &f(&d" + durationString + "&f) per: &c" + reason);
            return true;
        }

        if (commandName.equals("unmute")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /unmute <giocatore>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target != null && mutedPlayers.containsKey(target.getUniqueId())) {
                mutedPlayers.remove(target.getUniqueId());
                sender.sendMessage(color("&dGiocatore " + target.getName() + " smutato con successo."));
                notifyStaff("&d&lSTAFF ALERT &8» &5" + sender.getName() + " &fha smutato &d" + target.getName());
            } else {
                sender.sendMessage(color("&cGiocatore non trovato nei mute attivi."));
            }
            return true;
        }

        if (commandName.equals("warn")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /warn <giocatore> <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String reason = buildReason(args, 1);
            int currentWarns = warnMap.getOrDefault(target.getUniqueId(), 0) + 1;
            warnMap.put(target.getUniqueId(), currentWarns);

            Component warnMessage = color(
                "&5--------------------------------------------------\n" +
                "&d&lATERNIX&5&lMC &8» &c&lSEI STATO WARNATO! (" + currentWarns + "/3)\n" +
                "&fMotivo: &d" + reason + "\n" +
                "&fStaffer: &5" + sender.getName() + "\n" +
                "&5--------------------------------------------------"
            );

            target.sendMessage(warnMessage);
            sender.sendMessage(color("&dHai inviato un warn a &5" + target.getName() + " &7(Warn totali: " + currentWarns + ")"));
            notifyStaff("&d&lSTAFF ALERT &8» &5" + sender.getName() + " &fha warnato &d" + target.getName() + " &f(Warn #" + currentWarns + ") per: &c" + reason);
            return true;
        }

        if (commandName.equals("kick")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /kick <giocatore> <motivo>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String reason = buildReason(args, 1);
            Component kickMessage = color(
                "&d&lATERNIX&5&lMC NETWORK\n\n" +
                "&7Sei stato espulso dal server.\n\n" +
                "&fMotivo: &c" + reason + "\n" +
                "&fEspulso da: &d" + sender.getName()
            );

            target.kick(kickMessage);
            notifyStaff("&d&lSTAFF ALERT &8» &5" + sender.getName() + " &fha espulso &c" + target.getName() + " &fper: &c" + reason);
            return true;
        }

        if (commandName.equals("clearchat")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }

            for (int i = 0; i < 100; i++) {
                Bukkit.broadcast(Component.text(" "));
            }

            Bukkit.broadcast(color("&5--------------------------------------------------"));
            Bukkit.broadcast(color("&d&lATERNIX&5&lMC &8» &fLa chat è stata pulita da &d" + sender.getName()));
            Bukkit.broadcast(color("&5--------------------------------------------------"));
            return true;
        }

        if (commandName.equals("dupeip")) {
            if (!sender.hasPermission("aternixmc.staff")) {
                sender.sendMessage(color("&cNon hai il permesso."));
                return true;
            }

            if (args.length < 1) {
                sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &cUso: /dupeip <giocatore>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || target.getAddress() == null) {
                sender.sendMessage(color("&cGiocatore non trovato o offline."));
                return true;
            }

            String ip = target.getAddress().getAddress().getHostAddress();
            List<String> accounts = ipHistory.getOrDefault(ip, Collections.singletonList(target.getName()));

            sender.sendMessage(color("&5--------------------------------------------------"));
            sender.sendMessage(color("&d&lATERNIX&5&lMC &8» &fAccount collegati a &d" + target.getName() + "&f:"));

            for (String acc : accounts) {
                boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(acc);
                Player pAcc = Bukkit.getPlayer(acc);
                boolean isMuted = pAcc != null && mutedPlayers.containsKey(pAcc.getUniqueId());
                int warns = pAcc != null ? warnMap.getOrDefault(pAcc.getUniqueId(), 0) : 0;

                String status = "&d[PULITO]";
                if (isBanned) {
                    status = "&c[BANNATO]";
                } else if (isMuted) {
                    status = "&5[MUTATO]";
                } else if (warns > 0) {
                    status = "&e[WARNATO (" + warns + ")]";
                }

                sender.sendMessage(color("&8» &f" + acc + " " + status));
            }

            sender.sendMessage(color("&5--------------------------------------------------"));
            return true;
        }

        return true;
    }

    // Metodo unico per generare la schermata grafica di ban
    private Component getBanScreen(String reason, String executor, String duration) {
        return color(
            "&d&lATERNIX&5&lMC NETWORK\n\n" +
            "&7Sei attualmente bannato dal nostro server.\n\n" +
            "&f&lINFORMAZIONI SANZIONE\n" +
            "&8» &fMotivo: &c" + reason + "\n" +
            "&8» &fSanzionato da: &d" + executor + "\n" +
            "&8» &fScadenza/Durata: &d" + duration + "\n\n" +
            "&7Se ritieni che si tratti di un errore, apri un ticket sul nostro Discord."
        );
    }

    private void notifyStaff(String message) {
        Component comp = color(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("aternixmc.staff")) {
                p.sendMessage(comp);
            }
        }
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
