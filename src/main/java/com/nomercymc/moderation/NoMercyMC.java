package com.nomercymc.moderation;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

public class NoMercyMC extends JavaPlugin implements Listener, CommandExecutor {

    private final String PREFIX = ChatColor.DARK_RED + "[" + ChatColor.RED + "NoMercyMC" + ChatColor.DARK_RED + "] " + ChatColor.RESET;
    private final String STAFF_PERM = "nomercymc.staff";

    private final Map<UUID, Long> mutedPlayers = new HashMap<>();
    private final Map<String, Set<String>> ipToPlayersMap = new HashMap<>();
    private final Map<String, String> playerToIpMap = new HashMap<>();
    private final Map<String, List<String>> historyMap = new HashMap<>();
    private final Map<UUID, List<WarnData>> warnMap = new HashMap<>();
    private boolean globalChatMuted = false;

    public static class WarnData {
        private final String reason;
        private final String staff;
        private final long expireTime; // -1 se permanente

        public WarnData(String reason, String staff, long expireTime) {
            this.reason = reason;
            this.staff = staff;
            this.expireTime = expireTime;
        }

        public boolean isExpired() {
            return expireTime != -1 && System.currentTimeMillis() > expireTime;
        }

        public String getReason() { return reason; }
        public String getStaff() { return staff; }
        public long getExpireTime() { return expireTime; }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        String[] cmds = {
            "ban", "tempban", "ipban", "tempipban", "unban", 
            "mute", "tempmute", "unmute", "kick", "warn", "tempwarn",
            "unwarn", "check", "history", "dupeip", "clearchat", "mutechat"
        };
        for (String cmd : cmds) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        getLogger().info("NoMercyMC v1.21.11 di mattia attivato con successo!");
    }

    private void broadcastToStaff(String message) {
        String fullMsg = PREFIX + ChatColor.GRAY + "[STAFF] " + message;
        Bukkit.getConsoleSender().sendMessage(fullMsg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(STAFF_PERM)) {
                p.sendMessage(fullMsg);
            }
        }
    }

    private int getActiveWarnCount(UUID uuid) {
        List<WarnData> warns = warnMap.get(uuid);
        if (warns == null) return 0;
        int count = 0;
        for (WarnData w : warns) {
            if (!w.isExpired()) count++;
        }
        return count;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getAddress() != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            playerToIpMap.put(p.getName().toLowerCase(), ip);
            ipToPlayersMap.computeIfAbsent(ip, k -> new HashSet<>()).add(p.getName());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        
        if (globalChatMuted && !p.hasPermission(STAFF_PERM)) {
            e.setCancelled(true);
            p.sendMessage(PREFIX + ChatColor.RED + "La chat globale e' attualmente silenziata dallo staff.");
            return;
        }

        UUID uuid = p.getUniqueId();
        if (mutedPlayers.containsKey(uuid)) {
            long expire = mutedPlayers.get(uuid);
            if (expire != -1 && System.currentTimeMillis() > expire) {
                mutedPlayers.remove(uuid);
                return;
            }
            e.setCancelled(true);
            String dur = expire == -1 ? "permanente" : "ancora " + formatTime((expire - System.currentTimeMillis()) / 1000);
            p.sendMessage(PREFIX + ChatColor.RED + "Sei silenziato (" + dur + ")!");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cName = cmd.getName().toLowerCase();

        // Comandi per gestione chat
        if (cName.equals("clearchat")) {
            for (int i = 0; i < 100; i++) Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(PREFIX + ChatColor.YELLOW + "La chat e' stata pulita da " + ChatColor.WHITE + sender.getName());
            return true;
        }
        if (cName.equals("mutechat")) {
            globalChatMuted = !globalChatMuted;
            Bukkit.broadcastMessage(PREFIX + ChatColor.YELLOW + "La chat globale e' stata " + (globalChatMuted ? ChatColor.RED + "SILENZIATA" : ChatColor.GREEN + "RIATTIVATA") + ChatColor.YELLOW + " da " + ChatColor.WHITE + sender.getName());
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /" + label + " <giocatore> [tempo/motivo]");
            return true;
        }

        String target = args[0];
        Player pTarget = Bukkit.getPlayer(target);

        switch (cName) {
            case "ban": {
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
                Bukkit.getBanList(BanList.Type.NAME).addBan(target, reason, null, sender.getName());
                addHistory(target, "BAN: " + reason + " (" + sender.getName() + ")");
                if (pTarget != null) pTarget.kickPlayer(PREFIX + ChatColor.RED + "Sei stato bannato permanentemente!\nMotivo: " + reason);
                broadcastToStaff(ChatColor.RED + target + ChatColor.GRAY + " e' stato bannato da " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ". Motivo: " + ChatColor.YELLOW + reason);
                break;
            }
            case "tempban": {
                if (args.length < 2) { sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /tempban <player> <tempo> [motivo]"); return true; }
                long millis = parseTime(args[1]);
                if (millis <= 0) { sender.sendMessage(PREFIX + ChatColor.RED + "Formato tempo errato (es. 1d, 12h, 30m)"); return true; }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";
                Date expire = new Date(System.currentTimeMillis() + millis);
                Bukkit.getBanList(BanList.Type.NAME).addBan(target, reason, expire, sender.getName());
                addHistory(target, "TEMPBAN (" + args[1] + "): " + reason + " (" + sender.getName() + ")");
                if (pTarget != null) pTarget.kickPlayer(PREFIX + ChatColor.RED + "Bannato per " + args[1] + "!\nMotivo: " + reason);
                broadcastToStaff(ChatColor.RED + target + ChatColor.GRAY + " bannato per " + ChatColor.YELLOW + args[1] + ChatColor.GRAY + " da " + ChatColor.WHITE + sender.getName() + ". Motivo: " + reason);
                break;
            }
            case "ipban": {
                String ip = pTarget != null && pTarget.getAddress() != null ? pTarget.getAddress().getAddress().getHostAddress() : playerToIpMap.get(target.toLowerCase());
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
                if (ip != null) Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, null, sender.getName());
                Bukkit.getBanList(BanList.Type.NAME).addBan(target, reason, null, sender.getName());
                addHistory(target, "IPBAN: " + reason + " (" + sender.getName() + ")");
                if (pTarget != null) pTarget.kickPlayer(PREFIX + ChatColor.RED + "Il tuo IP e' stato bannato!\nMotivo: " + reason);
                broadcastToStaff(ChatColor.DARK_RED + target + " (IP-BAN)" + ChatColor.GRAY + " bannato da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "tempipban": {
                if (args.length < 2) { sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /tempipban <player> <tempo> [motivo]"); return true; }
                long millis = parseTime(args[1]);
                if (millis <= 0) { sender.sendMessage(PREFIX + ChatColor.RED + "Tempo non valido."); return true; }
                String ip = pTarget != null && pTarget.getAddress() != null ? pTarget.getAddress().getAddress().getHostAddress() : playerToIpMap.get(target.toLowerCase());
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";
                Date expire = new Date(System.currentTimeMillis() + millis);
                if (ip != null) Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, expire, sender.getName());
                Bukkit.getBanList(BanList.Type.NAME).addBan(target, reason, expire, sender.getName());
                addHistory(target, "TEMPIPBAN (" + args[1] + "): " + reason);
                if (pTarget != null) pTarget.kickPlayer(PREFIX + ChatColor.RED + "IP Bannato per " + args[1] + "!");
                broadcastToStaff(ChatColor.DARK_RED + target + " (TEMP-IPBAN " + args[1] + ")" + ChatColor.GRAY + " da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "unban": {
                Bukkit.getBanList(BanList.Type.NAME).pardon(target);
                String ip = playerToIpMap.get(target.toLowerCase());
                if (ip != null) Bukkit.getBanList(BanList.Type.IP).pardon(ip);
                broadcastToStaff(ChatColor.GREEN + target + ChatColor.GRAY + " e' stato sbannato da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "mute": {
                if (pTarget == null) { sender.sendMessage(PREFIX + ChatColor.RED + "Player non online."); return true; }
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
                mutedPlayers.put(pTarget.getUniqueId(), -1L);
                addHistory(target, "MUTE: " + reason);
                broadcastToStaff(ChatColor.YELLOW + target + ChatColor.GRAY + " e' stato silenziato da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "tempmute": {
                if (pTarget == null || args.length < 2) { sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /tempmute <player> <tempo> [motivo]"); return true; }
                long millis = parseTime(args[1]);
                if (millis <= 0) { sender.sendMessage(PREFIX + ChatColor.RED + "Tempo non valido."); return true; }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";
                mutedPlayers.put(pTarget.getUniqueId(), System.currentTimeMillis() + millis);
                addHistory(target, "TEMPMUTE (" + args[1] + "): " + reason);
                broadcastToStaff(ChatColor.YELLOW + target + ChatColor.GRAY + " silenziato per " + args[1] + " da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "unmute": {
                if (pTarget != null) mutedPlayers.remove(pTarget.getUniqueId());
                broadcastToStaff(ChatColor.GREEN + target + ChatColor.GRAY + " non e' piu' silenziato.");
                break;
            }
            case "kick": {
                if (pTarget == null) { sender.sendMessage(PREFIX + ChatColor.RED + "Player non online."); return true; }
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
                pTarget.kickPlayer(PREFIX + ChatColor.RED + "Sei stato cacciato!\nMotivo: " + reason);
                broadcastToStaff(ChatColor.GOLD + target + ChatColor.GRAY + " e' stato espulso da " + ChatColor.WHITE + sender.getName());
                break;
            }
            case "warn": {
                if (pTarget == null) { sender.sendMessage(PREFIX + ChatColor.RED + "Player non online."); return true; }
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Nessun motivo";
                warnMap.computeIfAbsent(pTarget.getUniqueId(), k -> new ArrayList<>()).add(new WarnData(reason, sender.getName(), -1L));
                int totalActive = getActiveWarnCount(pTarget.getUniqueId());
                addHistory(target, "WARN (#" + totalActive + "): " + reason);
                pTarget.sendMessage(PREFIX + ChatColor.RED + "Hai ricevuto un WARN (" + totalActive + "): " + ChatColor.YELLOW + reason);
                broadcastToStaff(ChatColor.GOLD + target + ChatColor.GRAY + " ha ricevuto un warn da " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + " (Totale attivi: " + totalActive + ")");
                break;
            }
            case "tempwarn": {
                if (pTarget == null || args.length < 2) { sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /tempwarn <player> <tempo> [motivo]"); return true; }
                long millis = parseTime(args[1]);
                if (millis <= 0) { sender.sendMessage(PREFIX + ChatColor.RED + "Tempo non valido (es. 1d, 12h, 30m)."); return true; }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Nessun motivo";
                long expire = System.currentTimeMillis() + millis;
                warnMap.computeIfAbsent(pTarget.getUniqueId(), k -> new ArrayList<>()).add(new WarnData(reason, sender.getName(), expire));
                int totalActive = getActiveWarnCount(pTarget.getUniqueId());
                addHistory(target, "TEMPWARN (" + args[1] + "): " + reason);
                pTarget.sendMessage(PREFIX + ChatColor.RED + "Hai ricevuto un TEMPWARN (" + args[1] + "): " + ChatColor.YELLOW + reason);
                broadcastToStaff(ChatColor.GOLD + target + ChatColor.GRAY + " ha ricevuto un tempwarn (" + args[1] + ") da " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + " (Totale attivi: " + totalActive + ")");
                break;
            }
            case "unwarn": {
                if (pTarget == null) { sender.sendMessage(PREFIX + ChatColor.RED + "Player non online."); return true; }
                List<WarnData> warns = warnMap.get(pTarget.getUniqueId());
                boolean removed = false;
                if (warns != null && !warns.isEmpty()) {
                    for (int i = warns.size() - 1; i >= 0; i--) {
                        if (!warns.get(i).isExpired()) {
                            warns.remove(i);
                            removed = true;
                            break;
                        }
                    }
                }
                if (removed) {
                    int totalActive = getActiveWarnCount(pTarget.getUniqueId());
                    broadcastToStaff(ChatColor.GREEN + "Rimosso un warn a " + target + ". Warn attivi rimanenti: " + totalActive);
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + target + " non ha warn attivi da rimuovere.");
                }
                break;
            }
            case "check": {
                boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(target);
                boolean isMuted = pTarget != null && mutedPlayers.containsKey(pTarget.getUniqueId());
                int activeWarns = pTarget != null ? getActiveWarnCount(pTarget.getUniqueId()) : 0;
                sender.sendMessage(PREFIX + ChatColor.GOLD + "--- Stato " + target + " ---");
                sender.sendMessage(ChatColor.GRAY + "Bannato: " + (isBanned ? ChatColor.RED + "Si" : ChatColor.GREEN + "No"));
                sender.sendMessage(ChatColor.GRAY + "Silenziato: " + (isMuted ? ChatColor.RED + "Si" : ChatColor.GREEN + "No"));
                sender.sendMessage(ChatColor.GRAY + "Warn Attivi: " + ChatColor.YELLOW + activeWarns);
                break;
            }
            case "history": {
                List<String> hist = historyMap.getOrDefault(target.toLowerCase(), Collections.emptyList());
                sender.sendMessage(PREFIX + ChatColor.GOLD + "Storico per " + target + ":");
                if (hist.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Nessun record.");
                else hist.forEach(e -> sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.YELLOW + e));
                break;
            }
            case "dupeip": {
                String ip = pTarget != null && pTarget.getAddress() != null ? pTarget.getAddress().getAddress().getHostAddress() : playerToIpMap.get(target.toLowerCase());
                if (ip == null) { sender.sendMessage(PREFIX + ChatColor.RED + "IP sconosciuto."); return true; }
                sender.sendMessage(PREFIX + ChatColor.GOLD + "Account su " + ip + ": " + ChatColor.WHITE + String.join(", ", ipToPlayersMap.getOrDefault(ip, new HashSet<>())));
                break;
            }
        }
        return true;
    }

    private void addHistory(String player, String entry) {
        historyMap.computeIfAbsent(player.toLowerCase(), k -> new ArrayList<>()).add(entry);
    }

    private long parseTime(String input) {
        try {
            char unit = input.charAt(input.length() - 1);
            long val = Long.parseLong(input.substring(0, input.length() - 1));
            return switch (unit) {
                case 's' -> val * 1000;
                case 'm' -> val * 60 * 1000;
                case 'h' -> val * 3600 * 1000;
                case 'd' -> val * 86400 * 1000;
                default -> -1;
            };
        } catch (Exception e) { return -1; }
    }

    private String formatTime(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
