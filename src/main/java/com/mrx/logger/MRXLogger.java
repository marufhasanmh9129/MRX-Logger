package com.mrx.logger;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class MRXLogger extends JavaPlugin implements Listener {
    private JDA jda;
    private YamlConfiguration messages;
    private YamlConfiguration help;
    private String activeToken = "";
    private final Set<String> reportedConnectionIssues = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean firstReadySeen = new AtomicBoolean(false);
    private final AtomicBoolean connectionTestSent = new AtomicBoolean(false);
    private final Map<String, Long> lastMovement = new ConcurrentHashMap<>();
    private final StringBuilder consoleBuffer = new StringBuilder();
    private final ThreadLocal<StringBuilder> commandOutputCapture = new ThreadLocal<>();
    private volatile boolean closing = false;
    private Handler consoleHandler;
    private Path consoleMessageFile;
    private String consoleMessageId = "";

    @Override public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        saveDefaultConfig();
        reloadConfig();
        migrateLegacyConfigToken();
        mergeDefaultConfig();
        consoleMessageFile = getDataFolder().toPath().resolve("console-message-id.txt");
        if (!Files.exists(getDataFolder().toPath().resolve("messages.yml"))) saveResource("messages.yml", false);
        if (!Files.exists(getDataFolder().toPath().resolve("help.yml"))) saveResource("help.yml", false);
        loadMessages();
        loadHelp();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MRX-Logger enabled. Configure channel IDs and DISCORD_TOKEN in config.yml.");
        activeToken = readToken();
        startDiscord();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (closing) return;
            if (jda == null || jda.getStatus() == JDA.Status.SHUTDOWN) {
                if (jda != null) jda = null;
                startDiscord();
            }
        }, 20L * 60L, 20L * 60L);
        installConsoleCapture();
    }

    private void migrateLegacyConfigToken() {
        String configured = getConfig().getString("DISCORD_TOKEN", "").trim();
        String legacy = getConfig().getString("discord.token", "").trim();
        if ((configured.isEmpty() || configured.startsWith("ENTER ") || configured.startsWith("YOUR_"))
                && !legacy.isEmpty() && !legacy.startsWith("ENTER ") && !legacy.startsWith("YOUR_")) {
            getConfig().set("DISCORD_TOKEN", legacy);
            saveConfig();
        }
    }

    private void mergeDefaultConfig() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        getConfig().set("security", null);
        getConfig().set("discord", null);
        getConfig().set("activity", null);
        getConfig().set("channels", null);
        saveConfig();
    }

    private String readToken() {
        String configured = getConfig().getString("DISCORD_TOKEN", "").trim();
        if (!configured.isEmpty() && !configured.startsWith("ENTER ") && !configured.startsWith("YOUR_")) return configured;
        // Backward compatibility for existing installations that used the older token file.
        try {
            Path legacyTokenFile = getDataFolder().toPath().resolve("discord-token.txt");
            if (Files.exists(legacyTokenFile)) {
                String legacy = Files.readString(legacyTokenFile).trim();
                if (!legacy.isEmpty() && !legacy.startsWith("ENTER ") && !legacy.startsWith("YOUR_")) return legacy;
            }
        } catch (IOException ex) {
            reportOnce("token-file", "Could not read the legacy token file. Configure DISCORD_TOKEN in config.yml.");
        }
        return "";
    }

    private void reportOnce(String key, String message) {
        if (reportedConnectionIssues.add(key)) getLogger().warning(message);
    }

    private void loadMessages() { messages = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("messages.yml").toFile()); }
    private void loadHelp() { help = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("help.yml").toFile()); }

    private synchronized void startDiscord() {
        String token = readToken();
        if (token.isEmpty() || token.startsWith("ENTER ") || token.startsWith("YOUR_")) {
            reportOnce("missing-token", "Discord is not connected. Set DISCORD_TOKEN in plugins/MRX-Logger/config.yml; repeated warnings are suppressed.");
            return;
        }
        if (jda != null) {
            reportOnce("already-started", "Discord client is already initialized; keeping the current reconnecting session.");
            return;
        }
        try {
            if (Files.exists(consoleMessageFile)) consoleMessageId = Files.readString(consoleMessageFile).trim();
            jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new DiscordListener())
                .build();
            getLogger().info("Discord login started; waiting for gateway READY event...");
        } catch (Exception ex) {
            jda = null;
            reportOnce("startup-failed", "Discord startup failed (" + ex.getClass().getSimpleName() + "). Check DISCORD_TOKEN and bot permissions. Further repeated connection errors are suppressed.");
        }
    }

    private void loadConfigFiles() {
        String oldToken = activeToken;
        mergeDefaultConfig();
        loadMessages();
        loadHelp();
        String newToken = readToken();
        if (!newToken.equals(oldToken)) {
            if (jda != null) { jda.shutdownNow(); jda = null; }
            reportedConnectionIssues.clear();
            firstReadySeen.set(false);
            connectionTestSent.set(false);
            activeToken = newToken;
            startDiscord();
        }
        getLogger().info("config.yml, messages.yml and help.yml reloaded. Discord token changes are applied on reload.");
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

    private String time() { return new SimpleDateFormat(getConfig().getString("settings.timestamp-format", getConfig().getString("activity.timestamp-format", "yyyy-MM-dd HH:mm:ss"))).format(new Date()); }
    private String template(String key, Map<String, String> values) {
        String result = messages.getString("messages." + key, "[" + "{time}" + "] **" + key + "**");
        result = result.replace("{time}", time());
        for (var entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        // Any placeholder not available for this event is removed instead of being shown literally.
        return result.replaceAll("\\{[a-zA-Z0-9_.-]+\\}", "").replaceAll("[ \t]{2,}", " ").trim();
    }
    private String clean(String s) { return s == null ? "unknown" : s.replace("@", "＠").replace("\n", " ").replace("\r", " "); }
    private boolean activitiesEnabled() { return getConfig().getBoolean("activities.enabled", getConfig().getBoolean("activity.enabled", true)); }
    private ConfigurationSection categorySection(String category) {
        ConfigurationSection modern = getConfig().getConfigurationSection("multiple-activities." + category);
        if (modern == null && category.equals("item")) modern = getConfig().getConfigurationSection("multiple-activities.items");
        if (modern != null) return modern;
        ConfigurationSection legacy = getConfig().getConfigurationSection("discord.channels." + category);
        if (legacy == null && category.equals("item")) legacy = getConfig().getConfigurationSection("discord.channels.items");
        return legacy;
    }
    private String consoleChannelId() {
        return getConfig().getString("console.channel-id", getConfig().getString("discord.channels.console.id", "")).trim();
    }
    private String activityChannelId(String category) {
        ConfigurationSection section = categorySection(category);
        if (section != null) {
            if (!section.getBoolean("enabled", true)) return "";
            String id = section.getString("channel-id", section.getString("id", "")).trim();
            if (!id.isEmpty()) return id;
        }
        if (!activitiesEnabled()) return "";
        ConfigurationSection all = getConfig().getConfigurationSection("activities");
        if (all != null && !all.getBoolean("enabled", true)) return "";
        String id = getConfig().getString("activities.channel-id", "").trim();
        if (id.isEmpty()) {
            ConfigurationSection legacy = getConfig().getConfigurationSection("discord.channels.all-activity");
            if (legacy != null && legacy.getBoolean("enabled", true)) id = legacy.getString("id", "").trim();
        }
        return id;
    }
    private void activity(String category, String template, Map<String, String> values) {
        if (closing || !activitiesEnabled()) return;
        String channelId = activityChannelId(category);
        if (channelId.isEmpty()) return;
        send(channelId, template(template, values), false);
    }
    private void send(String channelId, String message, boolean console) {
        JDA bot = jda;
        if (bot == null || bot.getStatus() != JDA.Status.CONNECTED) {
            reportOnce("discord-offline", "Discord is currently disconnected; log messages are being skipped until JDA reconnects. Repeated warnings are suppressed.");
            return;
        }
        if (channelId == null || channelId.isBlank() || channelId.startsWith("ENTER ") || channelId.startsWith("YOUR_")) {
            reportOnce("empty-channel-" + console, "Discord channel ID is empty or still a placeholder; messages for this destination will be skipped.");
            return;
        }
        var channel = bot.getTextChannelById(channelId);
        if (channel == null) {
            reportOnce("missing-channel-" + channelId, "Discord channel is not found or not accessible: " + channelId + ". Check the ID and bot permissions.");
            return;
        }
        if (console) appendConsole(channel, message);
        else channel.sendMessage(truncate(message, 1900)).queue(
            ignored -> {}, error -> reportOnce("send-failed-" + channelId, "Discord send failed in channel " + channelId + " (" + error.getClass().getSimpleName() + "). Further failures for this channel are suppressed until reconnection/reload."));
    }
    private String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 20) + "… [truncated]"; }

    private void installConsoleCapture() {
        consoleHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null || !isLoggable(record) || closing) return;
                String sourceLogger = record.getLoggerName();
                if (sourceLogger != null && (sourceLogger.startsWith("MRX-Logger") || sourceLogger.startsWith("com.mrx.logger"))) return;
                String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date(record.getMillis())) + "] [" + record.getLevel().getName() + "] " + String.valueOf(record.getMessage());
                StringBuilder capture = commandOutputCapture.get();
                if (capture != null) {
                    synchronized (capture) {
                        if (capture.length() < 6000) capture.append(line).append('\n');
                    }
                }
                Bukkit.getScheduler().runTaskAsynchronously(MRXLogger.this, () -> {
                    if (getConfig().getBoolean("console.enabled", true)) {
                        String id = consoleChannelId();
                        if (!id.isEmpty()) send(id, line, true);
                    }
                    boolean filter = getConfig().getBoolean("console.activity-filter.enabled", getConfig().getBoolean("console.activity-filter", true));
                    if (filter && looksLikeEntityActivity(line)) {
                        String id = getConfig().getString("activities.channel-id", "").trim();
                        if (id.isEmpty()) id = getConfig().getString("discord.channels.all-activity.id", "").trim();
                        if (activitiesEnabled() && !id.isEmpty()) send(id, "```\n" + truncate(line, 1700) + "\n```", false);
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
        int max = Math.max(500, getConfig().getInt("console.message-editing.max-message-length", getConfig().getInt("console.max-message-length", 1800)));
        if (consoleBuffer.length() > 0) consoleBuffer.append('\n');
        consoleBuffer.append(line);
        if (consoleBuffer.length() > max) consoleBuffer.delete(0, consoleBuffer.length() - max);
        String content = "```\n" + consoleBuffer + "\n```";
        if (content.length() > 1990) content = content.substring(content.length() - 1985);
        String finalContent = content;
        if (getConfig().getBoolean("console.message-editing.enabled", getConfig().getBoolean("console.edit-existing-message", true)) && !consoleMessageId.isBlank()) {
            channel.editMessageById(consoleMessageId, finalContent).queue(m -> {}, err -> channel.sendMessage(finalContent).queue(m -> rememberConsoleMessage(m.getId()), sendErr -> reportOnce("console-fallback-send", "Could not update or send the Discord console message; further errors are suppressed until reconnection.")));
        } else channel.sendMessage(finalContent).queue(m -> rememberConsoleMessage(m.getId()), err -> reportOnce("console-send-" + channel.getId(), "Could not send the Discord console message; further errors are suppressed until reconnection."));
    }
    private synchronized void rememberConsoleMessage(String id) { consoleMessageId = id; try { Files.writeString(consoleMessageFile, id); } catch (IOException ignored) {} }

    private Map<String,String> vals(String... pairs) {
        Map<String,String> m = new java.util.HashMap<>();
        for (int i=0;i+1<pairs.length;i+=2) m.put(pairs[i], clean(pairs[i+1]));
        // Add common context automatically so placeholders work wherever they are used in messages.yml.
        String playerName = m.getOrDefault("player", m.get("victim"));
        if (playerName != null) {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) addLocationValues(m, player.getLocation());
        }
        return m;
    }
    private void addLocationValues(Map<String,String> values, Location location) {
        if (location == null || location.getWorld() == null) return;
        values.putIfAbsent("world", clean(location.getWorld().getName()));
        values.putIfAbsent("location", clean(loc(location)));
        values.putIfAbsent("x", String.valueOf(location.getBlockX()));
        values.putIfAbsent("y", String.valueOf(location.getBlockY()));
        values.putIfAbsent("z", String.valueOf(location.getBlockZ()));
        values.putIfAbsent("coordinates", location.getBlockX()+", "+location.getBlockY()+", "+location.getBlockZ());
    }
    private String loc(Location l) { return l.getWorld().getName()+" "+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ(); }
    @EventHandler public void join(PlayerJoinEvent e) { Map<String,String> v=vals("player",e.getPlayer().getName()); addLocationValues(v,e.getPlayer().getLocation()); activity("player", "player-join", v); }
    @EventHandler public void quit(PlayerQuitEvent e) { Map<String,String> v=vals("player",e.getPlayer().getName()); addLocationValues(v,e.getPlayer().getLocation()); activity("player", "player-leave", v); }
    @EventHandler public void death(PlayerDeathEvent e) { activity("death", "player-death", vals("player",e.getEntity().getName())); }
    @EventHandler public void respawn(PlayerRespawnEvent e) { activity("respawn", "player-respawn", vals("player",e.getPlayer().getName())); }
    @EventHandler public void blockPlace(BlockPlaceEvent e) { Location l=e.getBlockPlaced().getLocation(); activity("block", "block-place", vals("player",e.getPlayer().getName(),"block",e.getBlockPlaced().getType().name(),"world",l.getWorld().getName(),"location",loc(l),"x",String.valueOf(l.getBlockX()),"y",String.valueOf(l.getBlockY()),"z",String.valueOf(l.getBlockZ()))); }
    @EventHandler public void blockBreak(BlockBreakEvent e) { Location l=e.getBlock().getLocation(); activity("block", "block-break", vals("player",e.getPlayer().getName(),"block",e.getBlock().getType().name(),"world",l.getWorld().getName(),"location",loc(l),"x",String.valueOf(l.getBlockX()),"y",String.valueOf(l.getBlockY()),"z",String.valueOf(l.getBlockZ()))); }
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
        if (!getConfig().getBoolean("settings.player-movement.enabled", true)) return;
        long now=System.currentTimeMillis(); long interval=getConfig().getLong("settings.player-movement.interval-seconds",5)*1000L;
        Long old=lastMovement.put(e.getPlayer().getUniqueId().toString(),now); if(old!=null && now-old<interval)return;
        activity("movement", "interaction", vals("player",e.getPlayer().getName(),"target","moved to "+loc(e.getTo())));
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override public void onReady(ReadyEvent event) {
            if (firstReadySeen.compareAndSet(false, true)) getLogger().info("Discord bot is ONLINE as " + event.getJDA().getSelfUser().getName() + ".");
            String consoleId = consoleChannelId();
            if (getConfig().getBoolean("console.enabled", true) && !consoleId.isBlank()) {
                var channel = event.getJDA().getTextChannelById(consoleId);
                if (channel == null) {
                    reportOnce("ready-console-channel", "Discord bot is online, but console.channel-id is not a text channel accessible to the bot.");
                } else if (connectionTestSent.compareAndSet(false, true)) {
                    channel.sendMessage("[MRX-Logger] Discord connection test successful.").queue(
                        ok -> getLogger().info("Discord send test succeeded in the configured console channel."),
                        err -> reportOnce("ready-test-failed", "Discord connection test message could not be sent. Check bot channel permissions."));
                }
            } else {
                reportOnce("ready-console-disabled", "Discord bot is online, but console logging is disabled or console.channel-id is empty.");
            }
            String activityId = getConfig().getString("activities.channel-id", "").trim();
            if (activitiesEnabled() && !activityId.isBlank()) {
                if (event.getJDA().getTextChannelById(activityId) == null) {
                    reportOnce("ready-activity-channel", "activities.channel-id is not accessible to the bot. Check the channel ID and permissions.");
                }
            } else {
                reportOnce("ready-activity-empty", "No default activities.channel-id is set; category-specific channel IDs can still be used.");
            }
        }

        @Override public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromGuild()) return;
            if (!getConfig().getBoolean("console.enabled", true) || !e.getChannel().getId().equals(consoleChannelId())) return;
            if (!getConfig().getBoolean("discord.commands.enabled", true)) return;
            String content = e.getMessage().getContentRaw().trim();
            if (content.startsWith("!")) content = content.substring(1).trim();
            if (content.isBlank()) return;
            if (content.equalsIgnoreCase("help") || content.equalsIgnoreCase("mrx help")) {
                String helpText = help == null ? "MRX-Logger help.yml is missing." : help.getString("discord-console", "MRX-Logger help.yml: discord-console section is missing.");
                e.getChannel().sendMessage(truncate(helpText.replace("\\n", "\n"), 1900)).queue(null, err -> getLogger().warning("Could not send help.yml content: " + err.getMessage()));
                return;
            }
            // Full console passthrough is deliberately restricted to Discord server administrators.
            // Do not expose a remote Minecraft console to every channel member.
            if (e.getMember() == null || !e.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                e.getChannel().sendMessage("⛔ Full console commands require Discord Administrator permission.").queue();
                return;
            }
            String command = content.startsWith("/") ? content.substring(1).trim() : content;
            if (command.isBlank()) return;
            final String commandToRun = command;
            Bukkit.getScheduler().runTask(MRXLogger.this, () -> {
                StringBuilder output = new StringBuilder();
                commandOutputCapture.set(output);
                boolean dispatched;
                try {
                    dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToRun);
                } finally {
                    commandOutputCapture.remove();
                }
                getLogger().info("Discord console command by " + e.getAuthor().getName() + ": " + commandToRun + " (dispatched=" + dispatched + ")");
                String response;
                synchronized (output) { response = output.toString().trim(); }
                String result = (dispatched ? "✅ Executed: `" : "⚠️ Could not dispatch: `") + truncate(commandToRun, 150) + "`";
                if (!response.isBlank()) result += "\n```\n" + truncate(response, 1700) + "\n```";
                else result += "\nCommand output, if logged by the server/plugin, will appear in the configured console log.";
                e.getChannel().sendMessage(truncate(result, 1900)).queue(null, err -> getLogger().warning("Could not send command result to Discord: " + err.getMessage()));
            });
        }
    }
    @Override public void onDisable() {
        closing=true;
        if(consoleHandler!=null) Logger.getLogger("").removeHandler(consoleHandler);
        if(jda!=null) { jda.shutdown(); jda=null; }
    }
}
