package com.leafviewsender;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动自检。
 *
 * 本插件直接编译对接服务端内部类，换 Leaf/Paper 构建后理论上可能出现签名变化。
 * 与其在发送路径上抛异常，不如在启用时一次性检查清楚：
 * - 核心项（区块过滤）对不上 → 整体退化为“不过滤”，保证地形显示不受影响；
 * - 可选模块（孤儿更新/粒子/声音）对不上 → 只关掉对应模块，其它功能照常。
 */
public final class CompatCheck {

    public static final String FEATURE_ORPHAN_UPDATES = "orphan-updates";
    public static final String FEATURE_PARTICLES = "particles";
    public static final String FEATURE_SOUNDS = "sounds";

    private CompatCheck() {
    }

    /** 区块过滤所依赖的核心成员。 */
    public static List<String> verifyCore() {
        List<String> problems = new ArrayList<>();

        try {
            PlayerChunkSender.class.getMethod("sendChunk",
                    ServerGamePacketListenerImpl.class, ServerLevel.class, LevelChunk.class);
        } catch (NoSuchMethodException missing) {
            problems.add("PlayerChunkSender.sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk) 不存在");
        }

        try {
            ServerPlayer.class.getField("connection");
        } catch (NoSuchFieldException missing) {
            problems.add("ServerPlayer.connection 字段不可访问");
        }

        try {
            ServerCommonPacketListenerImpl.class.getField("connection");
        } catch (NoSuchFieldException missing) {
            problems.add("ServerCommonPacketListenerImpl.connection 字段不可访问");
        }

        try {
            Connection.class.getField("channel");
        } catch (NoSuchFieldException missing) {
            problems.add("Connection.channel 字段不可访问");
        }

        try {
            ServerChunkCache.class.getMethod("getChunkNow", int.class, int.class);
        } catch (NoSuchMethodException missing) {
            problems.add("ServerChunkCache.getChunkNow(int,int) 不存在");
        }

        try {
            ClientboundLevelChunkWithLightPacket.class.getMethod("getX");
            ClientboundLevelChunkWithLightPacket.class.getMethod("getZ");
        } catch (NoSuchMethodException missing) {
            problems.add("ClientboundLevelChunkWithLightPacket.getX()/getZ() 不存在");
        }

        return problems;
    }

    /** 可选模块依赖的成员；缺失只会关掉对应模块。 */
    public static Set<String> verifyOptional() {
        Set<String> broken = new HashSet<>();

        try {
            ClientboundLightUpdatePacket.class.getMethod("getX");
            ClientboundLightUpdatePacket.class.getMethod("getZ");
            ClientboundBlockUpdatePacket.class.getMethod("getPos");
            ClientboundBlockEntityDataPacket.class.getMethod("getPos");
            ClientboundBlockDestructionPacket.class.getMethod("getPos");
            ClientboundBlockEventPacket.class.getMethod("getPos");
            ClientboundLevelEventPacket.class.getMethod("getPos");
            ClientboundLevelEventPacket.class.getMethod("isGlobalEvent");
            if (!OutboundHandler.sectionBlocksSupported()) {
                broken.add(FEATURE_ORPHAN_UPDATES);
            }
        } catch (Throwable missing) {
            broken.add(FEATURE_ORPHAN_UPDATES);
        }

        try {
            ClientboundLevelParticlesPacket.class.getMethod("getX");
            ClientboundLevelParticlesPacket.class.getMethod("getZ");
            ClientboundLevelParticlesPacket.class.getMethod("alwaysShow");
        } catch (Throwable missing) {
            broken.add(FEATURE_PARTICLES);
        }

        try {
            ClientboundSoundPacket.class.getMethod("getX");
            ClientboundSoundPacket.class.getMethod("getZ");
        } catch (Throwable missing) {
            broken.add(FEATURE_SOUNDS);
        }

        return broken;
    }
}
