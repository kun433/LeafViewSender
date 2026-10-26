package com.leafviewsender;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件的不可变配置快照。
 *
 * 所有字段都是 final，网络线程可以直接读取而无需加锁；
 * 重载配置时整体替换实例，避免出现“读到一半配置”的情况。
 */
public final class Settings {

    public final boolean enabled;

    /** 水平视野总角度，单位度；>= 360 表示不做方向过滤（只保留近身缓冲）。 */
    public final double fovDegrees;
    public final double halfFovDegrees;

    /** 玩家周围永久完整发送的区块半径（圆形，单位：区块）。 */
    public final int nearRadiusChunks;

    /** 每个玩家每轮补发最多多少个区块。 */
    public final int maxSendsPerTick;

    /** 参与计算的最大发送半径（区块），防止玩家把发送距离开到很大时扫描过重。 */
    public final int maxViewDistance;

    /** 朝向变化超过该角度就重新扫描一次视野。 */
    public final double yawThresholdDegrees;

    /** 即使玩家静止，也每隔这么多 tick 重新扫描一次，用于补发扫描时尚未加载完成的区块。 */
    public final int idleRescanTicks;

    /** 是否保留上一轮扫描的视野作为缓冲，减少快速转身时的空洞。 */
    public final boolean keepPreviousView;

    /** 按转向速度预测多少 tick 之后的朝向，用于提前补发（0 = 关闭）。 */
    public final int predictiveTicks;

    /** 预测的最大提前角度，避免甩视角时一次性铺太多区块。 */
    public final double predictiveMaxDegrees;

    /** 是否丢弃“客户端没有的区块”上的无意义更新包（方块/光照/方块实体等）。 */
    public final boolean orphanUpdates;

    /** 粒子裁剪（纯装饰流量）。 */
    public final boolean particlesEnabled;
    public final double particleMaxDistance;
    public final boolean particleRespectCone;

    /** 声音裁剪（默认关闭：会影响听声辨位）。 */
    public final boolean soundsEnabled;
    public final double soundMaxDistance;
    public final boolean soundRespectCone;

    /** 是否统计真实下行字节数（用于 /lvw status 显示 KB/s）。 */
    public final boolean wireStats;

    /** 不处理的世界的名字（小写）。 */
    public final Set<String> worldBlacklist;

    public final boolean debug;

    private Settings(Builder builder) {
        this.enabled = builder.enabled;
        this.fovDegrees = builder.fovDegrees;
        this.halfFovDegrees = builder.fovDegrees / 2.0D;
        this.nearRadiusChunks = builder.nearRadiusChunks;
        this.maxSendsPerTick = builder.maxSendsPerTick;
        this.maxViewDistance = builder.maxViewDistance;
        this.yawThresholdDegrees = builder.yawThresholdDegrees;
        this.idleRescanTicks = builder.idleRescanTicks;
        this.keepPreviousView = builder.keepPreviousView;
        this.predictiveTicks = builder.predictiveTicks;
        this.predictiveMaxDegrees = builder.predictiveMaxDegrees;
        this.orphanUpdates = builder.orphanUpdates;
        this.particlesEnabled = builder.particlesEnabled;
        this.particleMaxDistance = builder.particleMaxDistance;
        this.particleRespectCone = builder.particleRespectCone;
        this.soundsEnabled = builder.soundsEnabled;
        this.soundMaxDistance = builder.soundMaxDistance;
        this.soundRespectCone = builder.soundRespectCone;
        this.wireStats = builder.wireStats;
        this.worldBlacklist = builder.worldBlacklist;
        this.debug = builder.debug;
    }

    public static Settings load(ConfigurationSection cfg) {
        Builder builder = new Builder();

        builder.enabled = cfg.getBoolean("enabled", true);

        double fov = cfg.getDouble("fov-degrees", 140.0D);
        if (Double.isNaN(fov) || fov <= 0.0D) {
            fov = 140.0D;
        }
        builder.fovDegrees = Math.min(fov, 360.0D);

        builder.nearRadiusChunks = clamp(cfg.getInt("near-radius-chunks", 3), 0, 8);
        builder.maxSendsPerTick = clamp(cfg.getInt("max-sends-per-tick", 4), 1, 64);
        builder.maxViewDistance = clamp(cfg.getInt("max-view-distance", 12), 2, 32);

        double yawThreshold = cfg.getDouble("yaw-threshold-degrees", 10.0D);
        builder.yawThresholdDegrees = (Double.isNaN(yawThreshold) || yawThreshold <= 0.0D) ? 10.0D : yawThreshold;

        builder.idleRescanTicks = clamp(cfg.getInt("idle-rescan-ticks", 10), 1, 200);
        builder.keepPreviousView = cfg.getBoolean("keep-previous-view", true);

        builder.predictiveTicks = clamp(cfg.getInt("predictive-lookahead-ticks", 3), 0, 10);
        double lead = cfg.getDouble("predictive-lookahead-max-degrees", 45.0D);
        builder.predictiveMaxDegrees = (Double.isNaN(lead) || lead <= 0.0D) ? 0.0D : Math.min(lead, 180.0D);

        builder.orphanUpdates = cfg.getBoolean("traffic.orphan-updates", true);

        builder.particlesEnabled = cfg.getBoolean("traffic.particles.enabled", true);
        builder.particleMaxDistance = positive(cfg.getDouble("traffic.particles.max-distance", 32.0D), 32.0D);
        builder.particleRespectCone = cfg.getBoolean("traffic.particles.respect-view-cone", true);

        builder.soundsEnabled = cfg.getBoolean("traffic.sounds.enabled", false);
        builder.soundMaxDistance = positive(cfg.getDouble("traffic.sounds.max-distance", 64.0D), 64.0D);
        builder.soundRespectCone = cfg.getBoolean("traffic.sounds.respect-view-cone", false);

        builder.wireStats = cfg.getBoolean("traffic.wire-stats", true);

        Set<String> blacklist = new HashSet<>();
        List<String> raw = cfg.getStringList("worlds.blacklist");
        for (String name : raw) {
            if (name != null && !name.isBlank()) {
                blacklist.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        builder.worldBlacklist = Collections.unmodifiableSet(blacklist);

        builder.debug = cfg.getBoolean("debug", false);

        return new Settings(builder);
    }

    /** 该世界是否参与处理（不在排除列表里就参与）。 */
    public boolean handlesWorld(String worldName) {
        return worldName != null && !worldBlacklist.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static double positive(double value, double fallback) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return fallback;
        }
        return value;
    }

    private static final class Builder {
        boolean enabled;
        double fovDegrees;
        int nearRadiusChunks;
        int maxSendsPerTick;
        int maxViewDistance;
        double yawThresholdDegrees;
        int idleRescanTicks;
        boolean keepPreviousView;
        int predictiveTicks;
        double predictiveMaxDegrees;
        boolean orphanUpdates;
        boolean particlesEnabled;
        double particleMaxDistance;
        boolean particleRespectCone;
        boolean soundsEnabled;
        double soundMaxDistance;
        boolean soundRespectCone;
        boolean wireStats;
        Set<String> worldBlacklist;
        boolean debug;
    }
}
