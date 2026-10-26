package com.leafviewsender;

import io.netty.channel.Channel;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 主线程调度：维护每个玩家的状态、安装/移除网络处理器、补发区块，并采样下行速率。
 */
public final class SendManager {

    static final String FILTER_HANDLER = "leafviewsender";
    static final String WIRE_HANDLER = "leafviewsender_wire";

    private static final String BYPASS_PERMISSION = "leafviewsender.bypass";

    private final LeafViewSenderPlugin plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final LongAdder resentTotal = new LongAdder();

    private BukkitTask task;
    private long tickCounter;

    public SendManager(LeafViewSenderPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    public void shutdown() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            detach(player);
        }
        for (PlayerState state : this.states.values()) {
            removeHandlers(state);
        }
        this.states.clear();
    }

    public Map<UUID, PlayerState> states() {
        return this.states;
    }

    public long resentTotal() {
        return this.resentTotal.sum();
    }

    // ------------------------------------------------------------------
    // 处理器安装
    // ------------------------------------------------------------------

    public void attach(Player player) {
        if (!this.plugin.filteringSupported()) {
            return;
        }
        PlayerState state = this.states.computeIfAbsent(player.getUniqueId(), PlayerState::new);
        installHandlers(state, player);
    }

    public void detach(Player player) {
        PlayerState state = this.states.remove(player.getUniqueId());
        if (state != null) {
            removeHandlers(state);
        }
    }

    private void installHandlers(PlayerState state, Player player) {
        final Channel channel;
        try {
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
            channel = serverPlayer.connection.connection.channel;
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("无法读取 " + player.getName() + " 的网络连接，本玩家不做任何过滤：" + throwable);
            return;
        }

        if (channel == null) {
            this.plugin.getLogger().warning("玩家 " + player.getName() + " 的网络通道尚未就绪，跳过本次注入。");
            return;
        }

        state.channel = channel;
        final OutboundHandler filter = new OutboundHandler(this.plugin, state);
        final WireCounterHandler wire = new WireCounterHandler(state);

        channel.eventLoop().execute(() -> {
            try {
                if (this.plugin.settings().wireStats) {
                    if (channel.pipeline().get(WIRE_HANDLER) == null) {
                        channel.pipeline().addFirst(WIRE_HANDLER, wire);
                    }
                } else if (channel.pipeline().get(WIRE_HANDLER) != null) {
                    channel.pipeline().remove(WIRE_HANDLER);
                }

                if (channel.pipeline().get(FILTER_HANDLER) != null) {
                    return;
                }
                // 首选插在 packet_handler 之前：出站时仍是 Packet 对象，入站不受影响。
                // 万一该名字不存在，退回 encoder 之后（Leaf 的 unbundler 也是这个位置，同样能拿到 Packet）。
                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", FILTER_HANDLER, filter);
                } else if (channel.pipeline().get("encoder") != null) {
                    channel.pipeline().addAfter("encoder", FILTER_HANDLER, filter);
                } else {
                    this.plugin.getLogger().warning("为 " + player.getName() + " 注入失败：找不到可用的管道位置。");
                    return;
                }
                if (this.plugin.settings().debug) {
                    this.plugin.getLogger().info("已为 " + player.getName() + " 安装下行过滤器。");
                }
            } catch (Throwable throwable) {
                this.plugin.getLogger().warning("为 " + player.getName() + " 安装下行过滤器失败：" + throwable);
            }
        });
    }

    private void removeHandlers(PlayerState state) {
        final Channel channel = state.channel;
        if (channel == null) {
            return;
        }
        state.channel = null;
        try {
            if (channel.eventLoop().inEventLoop()) {
                removeNow(channel);
            } else {
                channel.eventLoop().execute(() -> removeNow(channel));
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("移除下行过滤器失败：" + throwable);
        }
    }

    private void removeNow(Channel channel) {
        try {
            if (channel.pipeline().get(FILTER_HANDLER) != null) {
                channel.pipeline().remove(FILTER_HANDLER);
            }
            if (channel.pipeline().get(WIRE_HANDLER) != null) {
                channel.pipeline().remove(WIRE_HANDLER);
            }
        } catch (Throwable ignored) {
            // 通道已经关闭时移除会抛异常，忽略即可
        }
    }

    // ------------------------------------------------------------------
    // 判定（网络线程调用）
    // ------------------------------------------------------------------

    /** 地形区块包是否放行。 */
    public boolean shouldAllowChunk(PlayerState state, int chunkX, int chunkZ) {
        Settings settings = this.plugin.settings();
        if (!settings.enabled || !state.filterActive || !this.plugin.filteringSupported()) {
            return true;
        }

        PlayerState.Snapshot snapshot = state.current;
        if (snapshot == null) {
            return true;
        }

        if (ChunkView.allowed(settings, snapshot, chunkX, chunkZ)) {
            return true;
        }

        // 提前补发：按转向速度预测的朝向
        PlayerState.Snapshot predicted = state.predicted;
        if (predicted != null && ChunkView.allowed(settings, predicted, chunkX, chunkZ)) {
            return true;
        }

        // 上一轮扫描的视野，减轻快速转身的空洞
        if (settings.keepPreviousView) {
            PlayerState.Snapshot previous = state.scanSnapshot;
            return previous != null && ChunkView.allowed(settings, previous, chunkX, chunkZ);
        }

        return false;
    }

    /** 目标区块已经被我们丢过（客户端没有）时，与它相关的更新包都是无意义流量。 */
    public boolean shouldDropOrphan(PlayerState state, int chunkX, int chunkZ) {
        Settings settings = this.plugin.settings();
        if (!settings.orphanUpdates || !state.filterActive || !this.plugin.filteringSupported()
                || !this.plugin.supports(CompatCheck.FEATURE_ORPHAN_UPDATES)) {
            return false;
        }
        if (state.droppedChunks.isEmpty()) {
            return false;
        }
        return state.droppedChunks.contains(ChunkView.key(chunkX, chunkZ));
    }

    public boolean shouldDropParticle(PlayerState state, ClientboundLevelParticlesPacket packet) {
        Settings settings = this.plugin.settings();
        if (!settings.particlesEnabled || !state.filterActive || !this.plugin.filteringSupported()
                || !this.plugin.supports(CompatCheck.FEATURE_PARTICLES)) {
            return false;
        }
        // /particle ... force 强制显示的粒子不要裁掉
        if (packet.alwaysShow()) {
            return false;
        }
        PlayerState.Snapshot snapshot = state.current;
        if (snapshot == null) {
            return false;
        }
        final double x = packet.getX();
        final double z = packet.getZ();

        if (insideNearBuffer(settings, snapshot, x, z)) {
            return false;
        }
        if (!ChunkView.withinDistance(snapshot, x, z, settings.particleMaxDistance)) {
            return true;
        }
        return settings.particleRespectCone && !ChunkView.withinCone(settings, snapshot, x, z);
    }

    public boolean shouldDropSound(PlayerState state, ClientboundSoundPacket packet) {
        Settings settings = this.plugin.settings();
        if (!settings.soundsEnabled || !state.filterActive || !this.plugin.filteringSupported()
                || !this.plugin.supports(CompatCheck.FEATURE_SOUNDS)) {
            return false;
        }
        PlayerState.Snapshot snapshot = state.current;
        if (snapshot == null) {
            return false;
        }
        final double x = packet.getX();
        final double z = packet.getZ();

        if (insideNearBuffer(settings, snapshot, x, z)) {
            return false;
        }
        if (!ChunkView.withinDistance(snapshot, x, z, settings.soundMaxDistance)) {
            return true;
        }
        return settings.soundRespectCone && !ChunkView.withinCone(settings, snapshot, x, z);
    }

    /** 近身缓冲范围内的东西一律保留，避免“身边的东西凭空消失”。 */
    private static boolean insideNearBuffer(Settings settings, PlayerState.Snapshot snapshot, double x, double z) {
        if (settings.nearRadiusChunks <= 0) {
            return false;
        }
        return ChunkView.withinDistance(snapshot, x, z, settings.nearRadiusChunks * 16.0D);
    }

    // ------------------------------------------------------------------
    // 每 tick 调度
    // ------------------------------------------------------------------

    private void tick() {
        this.tickCounter++;
        Settings settings = this.plugin.settings();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState state = this.states.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            try {
                update(state, player, settings);
            } catch (Throwable throwable) {
                if (settings.debug) {
                    this.plugin.getLogger().warning("处理玩家 " + player.getName() + " 时出错：" + throwable);
                }
            }
        }

        if (this.tickCounter % 20L == 0L) {
            sampleWireRates();
        }
        if ((this.tickCounter & 0xFFL) == 0L) {
            cleanupStaleStates();
        }
    }

    private void cleanupStaleStates() {
        this.states.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void sampleWireRates() {
        long now = System.nanoTime();
        for (PlayerState state : this.states.values()) {
            long total = state.wireBytes.sum();
            if (state.lastSampleNanos != 0L) {
                double seconds = (now - state.lastSampleNanos) / 1.0E9D;
                if (seconds > 0.05D) {
                    state.wireBytesPerSecond = (total - state.lastSampleBytes) / seconds;
                }
            }
            state.lastSampleBytes = total;
            state.lastSampleNanos = now;
        }
    }

    private void update(PlayerState state, Player player, Settings settings) {
        if (!player.isOnline()) {
            return;
        }

        UUID worldId = player.getWorld().getUID();
        if (!worldId.equals(state.worldId)) {
            state.worldId = worldId;
            state.clearChunkTracking();
            state.lastChunkX = Integer.MIN_VALUE;
            state.lastChunkZ = Integer.MIN_VALUE;
        }

        boolean active = settings.enabled
                && settings.handlesWorld(player.getWorld().getName())
                && !player.hasPermission(BYPASS_PERMISSION);
        state.filterActive = active;

        Location location = player.getLocation();
        PlayerState.Snapshot snapshot = new PlayerState.Snapshot(
                location.getX(),
                location.getZ(),
                location.getYaw(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );

        PlayerState.Snapshot previousTick = state.current;
        state.current = snapshot;

        if (previousTick != null) {
            double delta = ChunkView.wrapDegrees(snapshot.yaw() - previousTick.yaw());
            state.yawRate = state.yawRate * 0.5D + delta * 0.5D;
        } else {
            state.yawRate = 0.0D;
        }

        state.predicted = buildPrediction(state, snapshot, settings);

        if (!active) {
            state.backlog = false;
            return;
        }

        boolean moved = snapshot.chunkX() != state.lastChunkX || snapshot.chunkZ() != state.lastChunkZ;
        boolean turned = Math.abs(ChunkView.wrapDegrees(snapshot.yaw() - state.lastYaw)) >= settings.yawThresholdDegrees;
        boolean idle = (this.tickCounter - state.lastScanTick) >= settings.idleRescanTicks;
        // 上一轮没补完（配额用尽）就立即继续，否则转身/传送后会白等一会儿才把地形铺满
        boolean backlog = state.backlog;

        if (!moved && !turned && !idle && !backlog) {
            return;
        }

        state.lastScanTick = this.tickCounter;
        state.lastYaw = snapshot.yaw();
        state.lastChunkX = snapshot.chunkX();
        state.lastChunkZ = snapshot.chunkZ();
        state.scanSnapshot = snapshot;

        rescan(state, player, settings, snapshot);
    }

    private PlayerState.Snapshot buildPrediction(PlayerState state, PlayerState.Snapshot snapshot, Settings settings) {
        if (settings.predictiveTicks <= 0 || settings.predictiveMaxDegrees <= 0.0D) {
            return null;
        }
        double lead = state.yawRate * settings.predictiveTicks;
        if (Math.abs(lead) < 1.0D) {
            return null;
        }
        lead = Math.max(-settings.predictiveMaxDegrees, Math.min(settings.predictiveMaxDegrees, lead));
        return new PlayerState.Snapshot(snapshot.x(), snapshot.z(),
                (float) (snapshot.yaw() + lead), snapshot.chunkX(), snapshot.chunkZ());
    }

    /**
     * 扫描玩家周围，把“视野内、已加载、但客户端还没有”的区块补发回去。
     * 从近到远逐环发送，保证近处优先生效，并受 max-sends-per-tick 限制。
     */
    private void rescan(PlayerState state, Player player, Settings settings, PlayerState.Snapshot snapshot) {
        int radius = computeRadius(player, settings);
        int centerX = snapshot.chunkX();
        int centerZ = snapshot.chunkZ();

        prune(state, centerX, centerZ, radius);

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        ServerLevel level = serverPlayer.level();
        if (level == null || serverPlayer.connection == null) {
            return;
        }

        int quota = settings.maxSendsPerTick;
        int sent = 0;

        for (int ring = 0; ring <= radius && sent < quota; ring++) {
            if (ring == 0) {
                if (sendIfNeeded(state, serverPlayer, level, centerX, centerZ)) {
                    sent++;
                }
                continue;
            }

            for (int offset = -ring; offset <= ring && sent < quota; offset++) {
                // 上下两条边
                if (sendIfNeeded(state, serverPlayer, level, centerX + offset, centerZ - ring)) {
                    sent++;
                }
                if (sent >= quota) {
                    break;
                }
                if (sendIfNeeded(state, serverPlayer, level, centerX + offset, centerZ + ring)) {
                    sent++;
                }
                if (sent >= quota) {
                    break;
                }
                // 左右两条边（四角已在上面处理）
                if (offset != -ring && offset != ring) {
                    if (sendIfNeeded(state, serverPlayer, level, centerX - ring, centerZ + offset)) {
                        sent++;
                    }
                    if (sent >= quota) {
                        break;
                    }
                    if (sendIfNeeded(state, serverPlayer, level, centerX + ring, centerZ + offset)) {
                        sent++;
                    }
                }
            }
        }

        if (sent > 0) {
            this.resentTotal.add(sent);
            state.resentChunks.add(sent);
        }
        // 配额用尽说明还有没补完的，下一 tick 继续；否则等下次触发
        state.backlog = sent >= quota;
    }

    /**
     * 计算需要覆盖的半径（区块，方形口径，与服务端一致）。
     *
     * 服务端实际发送范围是 Chebyshev 距离 <= 有效发送距离 + 1；
     * 有效发送距离又会被客户端自己设置的渲染距离限制。
     * 这里同时考虑两者，既避免最外圈漏补（会留空洞），也避免给低渲染距离的客户端多发它根本渲染不到的区块。
     */
    private static int computeRadius(Player player, Settings settings) {
        int configuredSend = player.getSendViewDistance(); // 未单独设置时可能是负数
        int clientView = player.getClientViewDistance();     // 未设置时回退为服务器 view-distance
        int worldView = player.getWorld().getViewDistance();

        int base = configuredSend >= 0 ? configuredSend : worldView;

        int radius = Math.min(base + 1, clientView + 2);
        radius = Math.min(radius, settings.maxViewDistance);
        // 至少要能覆盖近身缓冲，否则玩家身边会出现空洞
        radius = Math.max(radius, settings.nearRadiusChunks + 1);
        return Math.max(radius, 1);
    }

    private boolean sendIfNeeded(PlayerState state, ServerPlayer serverPlayer, ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkView.key(chunkX, chunkZ);
        if (state.clientChunks.contains(key)) {
            return false;
        }
        if (!shouldAllowChunk(state, chunkX, chunkZ)) {
            return false;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            // 区块还没加载完成，等下一轮扫描再试
            return false;
        }

        try {
            // 走服务端原生发送路径：会带抗透视判定、高度图、方块实体，并触发 PlayerChunkLoadEvent。
            PlayerChunkSender.sendChunk(serverPlayer.connection, level, chunk);
        } catch (Throwable throwable) {
            if (this.plugin.settings().debug) {
                this.plugin.getLogger().warning("补发区块 " + chunkX + "," + chunkZ + " 失败：" + throwable);
            }
            return false;
        }
        state.markClientHas(key);
        return true;
    }

    /**
     * 清掉距离玩家过远的记账，避免集合无限增长。
     * 清得比服务端卸载稍早是安全的：服务端在区块离开发送范围时会自行重新发送。
     */
    private void prune(PlayerState state, int centerX, int centerZ, int radius) {
        int limit = radius + 1;
        state.clientChunks.removeIf(key -> outsideLimit(key, centerX, centerZ, limit));
        state.droppedChunks.removeIf(key -> outsideLimit(key, centerX, centerZ, limit));
    }

    private static boolean outsideLimit(long key, int centerX, int centerZ, int limit) {
        int chunkX = ChunkView.keyX(key);
        int chunkZ = ChunkView.keyZ(key);
        return Math.abs(chunkX - centerX) > limit || Math.abs(chunkZ - centerZ) > limit;
    }

    public String describe(PlayerState state) {
        return String.format(Locale.ROOT,
                "区块 已知=%d 拦截=%d 补发=%d｜丢弃 孤儿=%d 粒子=%d 声音=%d｜下行=%.1f KB/s",
                state.clientChunkCount(),
                state.droppedChunkPackets.sum(),
                state.resentChunks.sum(),
                state.droppedOrphanUpdates.sum(),
                state.droppedParticles.sum(),
                state.droppedSounds.sum(),
                state.wireBytesPerSecond / 1024.0D);
    }
}
