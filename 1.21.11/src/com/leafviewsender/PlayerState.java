package com.leafviewsender;

import io.netty.channel.Channel;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个玩家的运行状态。
 *
 * 主线程负责更新快照、补发区块与采样统计，网络线程只读写并发安全的集合和 volatile 字段，
 * 因此这里不使用任何需要跨线程持有的锁。
 */
public final class PlayerState {

    public final UUID playerId;

    /** 客户端当前确实持有的区块（我们自己记账，因为服务端的账本里包含被我们丢掉的区块）。 */
    public final Set<Long> clientChunks = ConcurrentHashMap.newKeySet();

    /** 被我们明确丢掉的区块：只有这些区块上的更新包才允许被裁剪，避免误伤客户端已有的区块。 */
    public final Set<Long> droppedChunks = ConcurrentHashMap.newKeySet();

    public final LongAdder droppedChunkPackets = new LongAdder();
    public final LongAdder droppedOrphanUpdates = new LongAdder();
    public final LongAdder droppedParticles = new LongAdder();
    public final LongAdder droppedSounds = new LongAdder();
    public final LongAdder resentChunks = new LongAdder();

    /** 线路字节数（压缩、加密后的真实下行字节），由管道最末端的计数器累加。 */
    public final LongAdder wireBytes = new LongAdder();

    /** 每 tick 刷新的位置与朝向快照，供网络线程判断。 */
    volatile Snapshot current;

    /** 最近一次扫描时的快照，作为转身缓冲。 */
    volatile Snapshot scanSnapshot;

    /** 按转向速度预测出的朝向快照（提前补发用）。 */
    volatile Snapshot predicted;

    /** 是否对当前玩家启用过滤（关闭插件、权限绕过、世界被排除时为 false）。 */
    volatile boolean filterActive;

    /** 当前世界，用于换维度时清空记账。 */
    volatile UUID worldId;

    /** 网络连接，用于退出时移除处理器。 */
    volatile Channel channel;

    long lastScanTick;
    double lastYaw;
    int lastChunkX = Integer.MIN_VALUE;
    int lastChunkZ = Integer.MIN_VALUE;

    /** 转向速度（度/tick，做了一点平滑）。 */
    double yawRate;

    /** 上一轮补发被配额打断，需要下一 tick 接着补。 */
    volatile boolean backlog;

    /** 下行速率采样。 */
    long lastSampleBytes;
    long lastSampleNanos;
    volatile double wireBytesPerSecond;

    public PlayerState(UUID playerId) {
        this.playerId = playerId;
    }

    public void clearChunkTracking() {
        this.clientChunks.clear();
        this.droppedChunks.clear();
        this.backlog = false;
    }

    public void markClientHas(long key) {
        this.clientChunks.add(key);
        this.droppedChunks.remove(key);
    }

    public void markDropped(long key) {
        this.clientChunks.remove(key);
        this.droppedChunks.add(key);
    }

    public int clientChunkCount() {
        return this.clientChunks.size();
    }

    /** 位置与朝向的不可变快照。 */
    public record Snapshot(double x, double z, float yaw, int chunkX, int chunkZ) {
    }
}
