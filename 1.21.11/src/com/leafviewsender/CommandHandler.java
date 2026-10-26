package com.leafviewsender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /leafviewsender reload|status|hints
 */
public final class CommandHandler implements TabExecutor {

    private static final String[] SUBCOMMANDS = {"status", "reload", "hints"};

    private final LeafViewSenderPlugin plugin;

    public CommandHandler(LeafViewSenderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                this.plugin.reloadPlugin();
                send(sender, Component.text("配置已重载。", NamedTextColor.GREEN));
                sendStatus(sender);
            }
            case "hints" -> sendHints(sender);
            case "status" -> sendStatus(sender);
            default -> {
                send(sender, Component.text("/" + label + " status  查看当前状态与各玩家下行速率", NamedTextColor.YELLOW));
                send(sender, Component.text("/" + label + " hints   查看服务端配置层面的省流量建议", NamedTextColor.YELLOW));
                send(sender, Component.text("/" + label + " reload  重载配置", NamedTextColor.YELLOW));
            }
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        Settings settings = this.plugin.settings();
        SendManager manager = this.plugin.sendManager();
        if (manager == null) {
            send(sender, Component.text("插件尚未初始化完成。", NamedTextColor.RED));
            return;
        }

        send(sender, Component.text("LeafViewSender 状态", NamedTextColor.AQUA));
        send(sender, line("启用", settings.enabled ? "是" : "否"));
        send(sender, line("启动自检", this.plugin.filteringSupported() ? "通过" : "未通过（已自动改为不过滤）"));
        send(sender, line("视野角度", Math.round(settings.fovDegrees) + "°" + (settings.fovDegrees >= 360.0D ? "（方向过滤关闭）" : "")));
        send(sender, line("近身缓冲", settings.nearRadiusChunks + " 区块"));
        send(sender, line("每轮补发上限", String.valueOf(settings.maxSendsPerTick)));
        send(sender, line("转向提前量", settings.predictiveTicks <= 0
                ? "关闭"
                : settings.predictiveTicks + " tick（最多 " + Math.round(settings.predictiveMaxDegrees) + "°）"));
        send(sender, line("孤儿更新裁剪", moduleText(settings.orphanUpdates, CompatCheck.FEATURE_ORPHAN_UPDATES)));
        send(sender, line("粒子裁剪", moduleText(settings.particlesEnabled, CompatCheck.FEATURE_PARTICLES)
                + (settings.particlesEnabled ? "（" + Math.round(settings.particleMaxDistance) + " 格"
                + (settings.particleRespectCone ? " + 视野" : "") + "）" : "")));
        send(sender, line("声音裁剪", moduleText(settings.soundsEnabled, CompatCheck.FEATURE_SOUNDS)
                + (settings.soundsEnabled ? "（" + Math.round(settings.soundMaxDistance) + " 格"
                + (settings.soundRespectCone ? " + 视野" : "") + "）" : "")));
        send(sender, line("排除的世界", settings.worldBlacklist.isEmpty() ? "无" : String.join(", ", settings.worldBlacklist)));
        send(sender, line("累计补发", String.valueOf(manager.resentTotal())));

        int tracked = 0;
        long totalBytesPerSecond = 0L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState state = manager.states().get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            tracked++;
            totalBytesPerSecond += (long) state.wireBytesPerSecond;
            send(sender, Component.text("  - " + player.getName() + "：" + manager.describe(state)
                    + (state.filterActive ? "" : "（已绕过）"), NamedTextColor.GRAY));
        }
        if (tracked == 0) {
            send(sender, Component.text("  当前没有受管理的在线玩家。", NamedTextColor.GRAY));
        } else {
            send(sender, line("在线玩家合计下行", String.format(Locale.ROOT, "%.1f KB/s", totalBytesPerSecond / 1024.0D)));
        }
    }

    private void sendHints(CommandSender sender) {
        send(sender, Component.text("服务端配置层面的省流量建议", NamedTextColor.AQUA));
        for (String hint : ServerHints.hints()) {
            send(sender, Component.text("  - " + hint, NamedTextColor.GRAY));
        }
    }

    private String moduleText(boolean configured, String feature) {
        if (!configured) {
            return "关闭";
        }
        return this.plugin.supports(feature) ? "开启" : "开启（当前服务端不可用，已跳过）";
    }

    private static Component line(String key, String value) {
        return Component.text(key + "：", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static void send(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            List<String> result = new ArrayList<>();
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(prefix)) {
                    result.add(option);
                }
            }
            return result;
        }
        return List.of();
    }
}
