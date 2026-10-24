package com.mrx.logger;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class MRXLogger extends JavaPlugin implements Listener {
    private JDA jda;
    private YamlConfiguration messages;
    private final Map<String, Long> lastMovement = new ConcurrentHashMap<>();
    private final StringBuilder consoleBuffer = new StringBuilder();
    private volatile boolean closing = false;
    private Handler consoleHandler;
    private Path consoleMessageFile;
    private String consoleMessageId = "";

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        consoleMessageFile = getDataFolder().toPath().resolve("console-message-id.txt");
        if (!Files.exists(getDataFolder().toPath().resolve("messages.yml"))) saveResource("messages.yml", false);
        loadMessages();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MRX-Logger enabled. Configure Discord token and channel IDs in config.yml.");
        startDiscord();
        installConsoleCapture();
    }

    private void loadMessages() { messages = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("messages.yml").toFile()); }

    private synchronized void startDiscord() {
        String token = getConfig().getString("discord.token", "").trim();
        if (token.isEmpty() || token.startsWith("ENTER ")) { getLogger().warning("Discord token is not configured; Discord integration is disabled."); return; }
        try {
            if (jda != null) return;
            jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new DiscordListener()).build();
            jda.awaitReady();
            if (Files.exists(consoleMessageFile)) consoleMessageId = Files.readString(consoleMessageFile).trim();
            getLogger().info("Discord bot connected.");
        } catch (Exception ex) { getLogger().severe("Could not connect Discord bot: " + ex.getMessage()); jda = null; }
    }

    private void loadConfigFiles() {
        reloadConfig(); loadMessages();
        // Keep the existing Discord session so reload does not interrupt the bot connection.
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mrxlogger")) return false;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mrxlogger.reload")) { sender.sendMessage("§cYou do not have permission to use this command."); return true; }
            try { loadConfigFiles(); sender.sendMessage(messages.getString("messages.reload-success", "§a[MRX-Logger] Configuration reloaded successfully.")); }
            catch (Exception ex) { sender.sendMessage(messages.getString("messages.reload-failed", "§c[MRX-Logger] Reload failed. Check the server console.")); getLogger().warning("Reload failed: " + ex.getMessage()); }
            return true;
        }
        sender.sendMessage("§eUsage: /mrxlogger reload"); return true;
    }

    private String time() { return new SimpleDateFormat(getConfig().getString("activity.timestamp-format", "yyyy-MM-dd HH:mm:ss")).format(new Date()); }
    private String template(String key, Map<String, String> values) {
        String result = messages.getString("messages." + key, "[" + "{time}" + "] **" + key + "**");
        result = result.replace("{time}", time());
        for (var entry : values.entrySet()) result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        return result;
    }
    private String clean(String s) { return s == null ? "unknown" : s.replace("@", "＠").replace("\n", " ").replace("\r", " "); }
    private void activity(String category, String template, Map<String, String> values) {
        if (closing || !getConfig().getBoolean("activity.enabled", true)) return;
        String message = template(template, values);
        String channelId = "";
        ConfigurationSection section = getConfig().getConfigurationSection("discord.channels." + category);
        if (section != null && section.getBoolean("enabled", true)) channelId = section.getString("id", "").trim();
        if (channelId.isEmpty()) {
            ConfigurationSection all = getConfig().getConfigurationSection("discord.channels.all-activity");
            if (all == null || !all.getBoolean("enabled", true)) return;
            channelId = all.getString("id", "").trim();
        }
        send(channelId, message, false);
    }
    private void send(String channelId, String message, boolean console) {
        JDA bot = jda;
        if (bot == null || channelId == null || channelId.isBlank() || channelId.startsWith("ENTER ")) return;
        var channel = bot.getTextChannelById(channelId);
        if (channel == null) return;
        if (console) appendConsole(channel, message); else channel.sendMessage(truncate(message, 1900)).queue(null, e -> getLogger().fine("Discord send failed: " + e.getMessage()));
    }
    private String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 20) + "… [truncated]"; }

    private void installConsoleCapture() {
        consoleHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null || !isLoggable(record) || closing) return;
                String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date(record.getMillis())) + "] [" + record.getLevel().getName() + "] " + String.valueOf(record.getMessage());
                Bukkit.getScheduler().runTaskAsynchronously(MRXLogger.this, () -> {
                    if (getConfig().getBoolean("console.enabled", true)) {
                        ConfigurationSection c = getConfig().getConfigurationSection("discord.channels.console");
                        if (c != null && c.getBoolean("enabled", true)) send(c.getString("id", ""), line, true);
                    }
                    if (getConfig().getBoolean("console.activity-filter", true) && looksLikeEntityActivity(line)) {
                        ConfigurationSection all = getConfig().getConfigurationSection("discord.channels.all-activity");
                        if (all != null && all.getBoolean("enabled", true)) send(all.getString("id", ""), "```\n" + truncate(line, 1700) + "\n```", false);
                    }
                });
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger.getLogger("").addHandler(consoleHandler);
    }
    private boolean looksLikeEntityActivity(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        return x.contains("logged in") || x.contains("joined the game") || x.contains("left the game") || x.contains("issued server command") || x.contains("was slain") || x.contains("was shot") || x.contains("was blown up") || x.contains("died") || x.contains("advancement");
    }
    private synchronized void appendConsole(TextChannel channel, String line) {
        int max = Math.max(500, getConfig().getInt("console.max-message-length", 1800));
        if (consoleBuffer.length() > 0) consoleBuffer.append('\n');
        consoleBuffer.append(line);
        if (consoleBuffer.length() > max) consoleBuffer.delete(0, consoleBuffer.length() - max);
        String content = "```\n" + consoleBuffer + "\n```";
        if (content.length() > 1990) content = content.substring(content.length() - 1985);
        String finalContent = content;
        if (getConfig().getBoolean("console.edit-existing-message", true) && !consoleMessageId.isBlank()) {
            channel.editMessageById(consoleMessageId, finalContent).queue(m -> {}, err -> channel.sendMessage(finalContent).queue(m -> rememberConsoleMessage(m.getId())));
        } else channel.sendMessage(finalContent).queue(m -> rememberConsoleMessage(m.getId()));
    }
    private synchronized void rememberConsoleMessage(String id) { consoleMessageId = id; try { Files.writeString(consoleMessageFile, id); } catch (IOException ignored) {} }

    private Map<String,String> vals(String... pairs) { Map<String,String> m = new java.util.HashMap<>(); for (int i=0;i+1<pairs.length;i+=2) m.put(pairs[i], clean(pairs[i+1])); return m; }
    private String loc(Location l) { return l.getWorld().getName()+" "+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ(); }
    @EventHandler public void join(PlayerJoinEvent e) { activity("player", "player-join", vals("player",e.getPlayer().getName())); }
    @EventHandler public void quit(PlayerQuitEvent e) { activity("player", "player-leave", vals("player",e.getPlayer().getName())); }
    @EventHandler public void death(PlayerDeathEvent e) { activity("death", "player-death", vals("player",e.getEntity().getName())); }
    @EventHandler public void respawn(PlayerRespawnEvent e) { activity("respawn", "player-respawn", vals("player",e.getPlayer().getName())); }
    @EventHandler public void blockPlace(BlockPlaceEvent e) { activity("block", "block-place", vals("player",e.getPlayer().getName(),"block",e.getBlockPlaced().getType().name(),"location",loc(e.getBlockPlaced().getLocation()))); }
    @EventHandler public void blockBreak(BlockBreakEvent e) { activity("block", "block-break", vals("player",e.getPlayer().getName(),"block",e.getBlock().getType().name(),"location",loc(e.getBlock().getLocation()))); }
    @EventHandler public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) activity("damage", "damage", vals("victim",p.getName(),"damage",String.format(Locale.US,"%.1f",e.getFinalDamage()))); }
    @EventHandler public void entityDeath(EntityDeathEvent e) { if (!(e.getEntity() instanceof Player)) activity("mob", "mob-death", vals("entity",e.getEntity().getType().name(),"world",e.getEntity().getWorld().getName())); }
    @EventHandler public void spawn(EntitySpawnEvent e) { if (!(e.getEntity() instanceof Player)) activity("mob", "mob-spawn", vals("entity",e.getEntity().getType().name(),"world",e.getLocation().getWorld().getName())); }
    @EventHandler public void drop(PlayerDropItemEvent e) { ItemStack i=e.getItemDrop().getItemStack(); activity("item", "item-drop", vals("player",e.getPlayer().getName(),"item",i.getType().name(),"amount",String.valueOf(i.getAmount()))); }
    @EventHandler public void pickup(PlayerPickupItemEvent e) { ItemStack i=e.getItem().getItemStack(); activity("item", "item-pickup", vals("player",e.getPlayer().getName(),"item",i.getType().name(),"amount",String.valueOf(i.getAmount()))); }
    @EventHandler public void consume(PlayerItemConsumeEvent e) { activity("food", "food-eat", vals("player",e.getPlayer().getName(),"item",e.getItem().getType().name())); }
    @EventHandler public void inventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) activity("inventory", "inventory-change", vals("player",p.getName())); }
    @EventHandler public void interact(PlayerInteractEvent e) { if (e.getClickedBlock()!=null) activity("interactions", "interaction", vals("player",e.getPlayer().getName(),"target",e.getClickedBlock().getType().name())); }
    @EventHandler public void advancement(PlayerAdvancementDoneEvent e) { activity("advancements", "advancement", vals("player",e.getPlayer().getName(),"advancement",e.getAdvancement().getKey().toString())); }
    @EventHandler public void command(PlayerCommandPreprocessEvent e) { activity("commands", "player-command", vals("player",e.getPlayer().getName(),"command",e.getMessage().replaceFirst("^/", ""))); }
    @EventHandler public void chat(AsyncPlayerChatEvent e) { activity("chat", "player-chat", vals("player",e.getPlayer().getName(),"message",e.getMessage())); }
    @EventHandler public void food(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && e.getFoodLevel() < p.getFoodLevel()) activity("food", "food-eat", vals("player",p.getName(),"item","food level changed")); }
    @EventHandler public void move(PlayerMoveEvent e) {
        if (e.getTo()==null || e.getFrom().getBlockX()==e.getTo().getBlockX() && e.getFrom().getBlockY()==e.getTo().getBlockY() && e.getFrom().getBlockZ()==e.getTo().getBlockZ()) return;
        if (!getConfig().getBoolean("activity.player-movement.enabled", true)) return;
        long now=System.currentTimeMillis(); long interval=getConfig().getLong("activity.player-movement.interval-seconds",5)*1000L;
        Long old=lastMovement.put(e.getPlayer().getUniqueId().toString(),now); if(old!=null && now-old<interval)return;
        activity("movement", "interaction", vals("player",e.getPlayer().getName(),"target","moved to "+loc(e.getTo())));
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromGuild()) return;
            ConfigurationSection c=getConfig().getConfigurationSection("discord.channels.console");
            if(c==null || !c.getBoolean("enabled",true) || !e.getChannel().getId().equals(c.getString("id",""))) return;
            if(!getConfig().getBoolean("security.discord-command-enabled",true)) return;
            String content=e.getMessage().getContentRaw().trim();
            if(content.startsWith("!")) content=content.substring(1).trim();
            if(content.isBlank())return;
            boolean authorized=false;
            if(getConfig().getBoolean("security.require-admin-role",true)) {
                List<String> ids=getConfig().getStringList("security.allowed-role-ids");
                authorized=e.getMember()!=null && e.getMember().getRoles().stream().anyMatch(r->ids.contains(r.getId()));
            } else authorized=e.getMember()!=null && e.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR);
            if(!authorized) { e.getChannel().sendMessage("⛔ You are not authorized to run console commands.").queue(); return; }
            if(content.equalsIgnoreCase("help")) { e.getChannel().sendMessage("Send `!op Steve` or `!list` to run a server console command. Only authorized roles may use this channel.").queue(); return; }
            String cmd=content;
            Bukkit.getScheduler().runTask(MRXLogger.this, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);
                if(getConfig().getBoolean("security.audit-commands",true)) getLogger().info("Discord console command by "+e.getAuthor().getName()+": "+cmd);
            });
        }
    }
    @Override public void onDisable() {
        closing=true;
        if(consoleHandler!=null) Logger.getLogger("").removeHandler(consoleHandler);
        if(jda!=null) { jda.shutdown(); jda=null; }
    }
}
