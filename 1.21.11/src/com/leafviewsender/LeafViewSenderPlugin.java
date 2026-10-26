package com.leafviewsender;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * LeafViewSender：只把玩家真正看得到的地形发给客户端，并顺手砍掉无意义的下行流量。
 *
 * 三条路径：
 * 1. 地形区块：视野扇形 + 近身缓冲之外的区块包直接丢弃，转身时按需补发；
 * 2. 孤儿更新：客户端根本没有的区块上的方块/光照/方块实体更新，纯浪费，直接丢；
 * 3. 装饰流量：粒子（可选声音）按距离 + 视野裁剪。
 */
public final class LeafViewSenderPlugin extends JavaPlugin implements Listener {

    private volatile Settings settings;
    private volatile boolean filteringSupported = true;
    private volatile Set<String> unsupportedFeatures = Collections.emptySet();
    private SendManager sendManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = Settings.load(getConfig());

        List<String> coreProblems = CompatCheck.verifyCore();
        if (!coreProblems.isEmpty()) {
            this.filteringSupported = false;
            getLogger().severe("当前服务端构建与插件不匹配，已自动切换为“不过滤”模式（地形全量发送，不会影响显示）：");
            for (String problem : coreProblems) {
                getLogger().severe("  - " + problem);
            }
            getLogger().severe("如需恢复优化，请用当前服务端 jar 重新编译本插件。");
        }

        Set<String> broken = CompatCheck.verifyOptional();
        if (!broken.isEmpty()) {
            this.unsupportedFeatures = Collections.unmodifiableSet(broken);
            getLogger().warning("以下可选模块在当前服务端上不可用，已自动关闭：" + String.join(", ", broken));
        }

        this.sendManager = new SendManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        this.sendManager.start();

        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            this.sendManager.attach(player);
        }

        CommandHandler commandHandler = new CommandHandler(this);
        PluginCommand command = getCommand("leafviewsender");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }

        getLogger().info("已启用：视野 " + Math.round(this.settings.fovDegrees) + "°，近身 "
                + this.settings.nearRadiusChunks + " 区块，每轮补发上限 " + this.settings.maxSendsPerTick
                + "；孤儿更新=" + enabledText(this.settings.orphanUpdates, CompatCheck.FEATURE_ORPHAN_UPDATES)
                + "，粒子=" + enabledText(this.settings.particlesEnabled, CompatCheck.FEATURE_PARTICLES)
                + "，声音=" + enabledText(this.settings.soundsEnabled, CompatCheck.FEATURE_SOUNDS)
                + "。");
    }

    @Override
    public void onDisable() {
        if (this.sendManager != null) {
            this.sendManager.shutdown();
        }
        getLogger().info("已关闭。");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (this.sendManager != null) {
            this.sendManager.attach(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (this.sendManager != null) {
            this.sendManager.detach(event.getPlayer());
        }
    }

    public Settings settings() {
        return this.settings;
    }

    public SendManager sendManager() {
        return this.sendManager;
    }

    /** 启动自检是否通过；不通过时全量放行，不做任何过滤。 */
    public boolean filteringSupported() {
        return this.filteringSupported;
    }

    /** 某个可选模块在当前服务端上是否可用。 */
    public boolean supports(String feature) {
        return !this.unsupportedFeatures.contains(feature);
    }

    public Set<String> unsupportedFeatures() {
        return this.unsupportedFeatures;
    }

    public void reloadPlugin() {
        reloadConfig();
        this.settings = Settings.load(getConfig());
        if (this.sendManager != null) {
            this.sendManager.start();
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                this.sendManager.attach(player);
            }
        }
    }

    private String enabledText(boolean configured, String feature) {
        if (!configured) {
            return "关闭";
        }
        return this.supports(feature) ? "开启" : "不可用";
    }
}
