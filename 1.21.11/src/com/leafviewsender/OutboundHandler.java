package com.leafviewsender;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import java.lang.reflect.Field;

/**
 * 出站过滤器。
 *
 * 位置：位于 "packet_handler" 之前（仍然拿得到 Packet 对象、还没有被 encoder 序列化）。
 * 负责四类判断：
 * 1. 地形区块包：不在视野内直接丢弃，之后由 {@link SendManager} 补发；
 * 2. 孤儿更新：目标区块我们丢过、客户端根本没有，那么方块/光照/方块实体等更新都是纯浪费；
 * 3. 粒子：装饰性流量，按距离与视野裁剪；
 * 4. 声音：可选，按距离与视野裁剪（默认关闭，避免影响听声辨位）。
 */
public final class OutboundHandler extends ChannelDuplexHandler {

    /** ClientboundSectionBlocksUpdatePacket 的 sectionPos 没有公开 getter，只能读私有字段。 */
    private static final Field SECTION_POS_FIELD = resolveSectionPosField();

    private final LeafViewSenderPlugin plugin;
    private final PlayerState state;

    public OutboundHandler(LeafViewSenderPlugin plugin, PlayerState state) {
        this.plugin = plugin;
        this.state = state;
    }

    static boolean sectionBlocksSupported() {
        return SECTION_POS_FIELD != null;
    }

    private static Field resolveSectionPosField() {
        try {
            Field field = ClientboundSectionBlocksUpdatePacket.class.getDeclaredField("sectionPos");
            field.setAccessible(true);
            return field;
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ClientboundLevelChunkWithLightPacket packet) {
            handleChunk(ctx, packet, promise);
            return;
        }

        if (msg instanceof ClientboundSectionBlocksUpdatePacket packet) {
            if (this.dropSectionBlocks(packet)) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundLightUpdatePacket packet) {
            if (this.plugin.sendManager().shouldDropOrphan(this.state, packet.getX(), packet.getZ())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundBlockUpdatePacket packet) {
            if (this.dropOrphanAt(packet.getPos())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundBlockEntityDataPacket packet) {
            if (this.dropOrphanAt(packet.getPos())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundBlockDestructionPacket packet) {
            if (this.dropOrphanAt(packet.getPos())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundBlockEventPacket packet) {
            if (this.dropOrphanAt(packet.getPos())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundLevelEventPacket packet) {
            // 全局事件（如末影龙、天气）不能按位置裁剪
            if (!packet.isGlobalEvent() && this.dropOrphanAt(packet.getPos())) {
                this.state.droppedOrphanUpdates.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundLevelParticlesPacket packet) {
            if (this.plugin.sendManager().shouldDropParticle(this.state, packet)) {
                this.state.droppedParticles.increment();
                promise.setSuccess();
                return;
            }
        } else if (msg instanceof ClientboundSoundPacket packet) {
            if (this.plugin.sendManager().shouldDropSound(this.state, packet)) {
                this.state.droppedSounds.increment();
                promise.setSuccess();
                return;
            }
        }

        ctx.write(msg, promise);
    }

    private void handleChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise) {
        final int chunkX = packet.getX();
        final int chunkZ = packet.getZ();
        final long key = ChunkView.key(chunkX, chunkZ);

        if (this.plugin.sendManager().shouldAllowChunk(this.state, chunkX, chunkZ)) {
            this.state.markClientHas(key);
            ctx.write(packet, promise);
        } else {
            this.state.markDropped(key);
            this.state.droppedChunkPackets.increment();
            // 包被我们吞掉，但仍然要让 Promise 成功，否则上层会认为发送失败。
            promise.setSuccess();
        }
    }

    private boolean dropOrphanAt(BlockPos pos) {
        return this.plugin.sendManager().shouldDropOrphan(this.state, pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean dropSectionBlocks(ClientboundSectionBlocksUpdatePacket packet) {
        final Field field = SECTION_POS_FIELD;
        if (field == null) {
            return false;
        }
        try {
            if (field.get(packet) instanceof SectionPos pos) {
                // section 坐标与区块坐标在这两个轴上是同一个值（一个 section 就是 16×16）
                return this.plugin.sendManager().shouldDropOrphan(this.state, pos.x(), pos.z());
            }
        } catch (Throwable ignored) {
            // 读不到就放行，宁可多发也不要把方块更新弄丢
        }
        return false;
    }
}
