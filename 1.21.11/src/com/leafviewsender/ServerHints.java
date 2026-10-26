package com.leafviewsender;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 读取 server.properties，给出真正能“少发不少字节”的建议。
 *
 * 说明：这些不是插件能直接改的东西，但它们的杠杆往往比插件本身更大，
 * 尤其是压缩阈值——没开压缩时下行流量会成倍偏大。
 */
public final class ServerHints {

    private static final long CACHE_MILLIS = 60_000L;

    private static volatile long cachedAt;
    private static volatile List<String> cached = List.of();

    private ServerHints() {
    }

    public static List<String> hints() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        cached = compute();
        cachedAt = now;
        return cached;
    }

    private static List<String> compute() {
        List<String> hints = new ArrayList<>();
        Properties properties = loadProperties();
        if (properties == null) {
            hints.add("读不到 server.properties，跳过配置建议。");
            return hints;
        }

        int compression = parseInt(properties.getProperty("network-compression-threshold"), 256);
        if (compression < 0) {
            hints.add("network-compression-threshold = -1：压缩是关闭的，下行流量成倍浪费，建议改成 256。");
        } else if (compression == 0) {
            hints.add("network-compression-threshold = 0：所有包都压缩，CPU 白烧，建议改成 256。");
        } else if (compression > 1024) {
            hints.add("network-compression-threshold = " + compression + "：阈值偏高，区块这类大包之外的小包会不压缩，建议 256。");
        }

        int viewDistance = parseInt(properties.getProperty("view-distance"), -1);
        int simulationDistance = parseInt(properties.getProperty("simulation-distance"), -1);
        int bukkitView = Bukkit.getViewDistance();

        int effectiveView = viewDistance > 0 ? viewDistance : bukkitView;
        if (effectiveView >= 12) {
            hints.add("view-distance = " + effectiveView + "：偏大，每降 1 每个玩家的发送量都会明显下降，建议 8~10。");
        }
        if (simulationDistance > effectiveView && simulationDistance > 0) {
            hints.add("simulation-distance(" + simulationDistance + ") 大于 view-distance(" + effectiveView
                    + ")：多出来的模拟范围只在浪费 CPU，建议设为与 view-distance 相同或更小。");
        }
        if (hints.isEmpty()) {
            hints.add("服务端网络与视距配置看起来正常（压缩阈值 " + compression + "，view-distance " + effectiveView + "）。");
        }
        return hints;
    }

    private static Properties loadProperties() {
        for (Path path : candidatePaths()) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
                return properties;
            } catch (IOException ignored) {
                // 换下一个候选路径
            }
        }
        return null;
    }

    private static List<Path> candidatePaths() {
        List<Path> paths = new ArrayList<>(2);
        paths.add(Path.of("server.properties"));
        try {
            Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath();
            paths.add(container.resolve("server.properties"));
            if (container.getParent() != null) {
                paths.add(container.getParent().resolve("server.properties"));
            }
        } catch (Throwable ignored) {
            // 拿不到世界目录就只试当前目录
        }
        return paths;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
