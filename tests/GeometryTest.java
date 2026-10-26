import com.leafviewsender.ChunkView;
import com.leafviewsender.PlayerState;
import com.leafviewsender.Settings;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * 离线几何断言：验证「朝向 -> 允许区块」的换算方向没有搞反。
 *
 * MC 约定：yaw = 0 朝 +Z（南），90 朝 -X（西），180 朝 -Z（北），270 朝 +X（东）。
 * 这个方向一旦搞反，插件会送身后、丢身前，属于最严重的静默错误，所以单独验证。
 *
 * 运行：见 tests/run-geometry-test.ps1
 */
public final class GeometryTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("enabled", true);
        cfg.set("fov-degrees", 120);          // 左右各 60°
        cfg.set("near-radius-chunks", 0);     // 先关掉近身缓冲，纯测扇形
        cfg.set("max-sends-per-tick", 4);
        cfg.set("max-view-distance", 12);
        cfg.set("yaw-threshold-degrees", 10);
        cfg.set("idle-rescan-ticks", 10);
        cfg.set("keep-previous-view", true);
        Settings fan = Settings.load(cfg);

        double x = 0.5D;
        double z = 0.5D;

        // 玩家在区块 (0,0)，yaw=0 => 前方是 +Z
        check(fan, x, z, 0.0F, 0, 0, true, "自己脚下");
        check(fan, x, z, 0.0F, 0, 3, true, "yaw=0 正前方(+Z)");
        check(fan, x, z, 0.0F, 0, -3, false, "yaw=0 正后方(-Z)");
        check(fan, x, z, 0.0F, 3, -3, false, "yaw=0 右后方(+X,-Z)");
        check(fan, x, z, 0.0F, 3, 3, true, "yaw=0 前右 45 度(+X,+Z)");

        // yaw=90 => 前方是 -X
        check(fan, x, z, 90.0F, -3, 0, true, "yaw=90 正前方(-X)");
        check(fan, x, z, 90.0F, 3, 0, false, "yaw=90 正后方(+X)");

        // yaw=180 => 前方是 -Z
        check(fan, x, z, 180.0F, 0, -3, true, "yaw=180 正前方(-Z)");
        check(fan, x, z, 180.0F, 0, 3, false, "yaw=180 正后方(+Z)");

        // yaw=270 => 前方是 +X
        check(fan, x, z, 270.0F, 3, 0, true, "yaw=270 正前方(+X)");
        check(fan, x, z, 270.0F, -3, 0, false, "yaw=270 正后方(-X)");

        // 近身缓冲：即便在身后，半径内也要放行
        MemoryConfiguration nearCfg = new MemoryConfiguration();
        nearCfg.set("fov-degrees", 60);
        nearCfg.set("near-radius-chunks", 3);
        Settings near = Settings.load(nearCfg);
        check(near, x, z, 0.0F, 0, -2, true, "身后 2 区块：近身缓冲放行");
        check(near, x, z, 0.0F, 2, 0, true, "侧后 2 区块：近身缓冲放行");
        check(near, x, z, 0.0F, 0, -5, false, "身后 5 区块：超出近身缓冲且不在扇形内");

        // 360° 关闭方向过滤
        MemoryConfiguration allCfg = new MemoryConfiguration();
        allCfg.set("fov-degrees", 360);
        allCfg.set("near-radius-chunks", 0);
        Settings all = Settings.load(allCfg);
        check(all, x, z, 0.0F, 0, -5, true, "fov=360 时全方向放行");
        check(all, x, z, 0.0F, 5, -5, true, "fov=360 时斜后方放行");

        // 角度归一化
        expect(Math.abs(ChunkView.wrapDegrees(350.0D) + 10.0D) < 1.0E-9D, "wrapDegrees(350) = -10");
        expect(Math.abs(ChunkView.wrapDegrees(-350.0D) - 10.0D) < 1.0E-9D, "wrapDegrees(-350) = 10");
        expect(Math.abs(ChunkView.wrapDegrees(190.0D) + 170.0D) < 1.0E-9D, "wrapDegrees(190) = -170");

        // 打包/解包
        long key = ChunkView.key(-1234, 5678);
        expect(ChunkView.keyX(key) == -1234 && ChunkView.keyZ(key) == 5678, "区块键打包解包一致");

        // 点坐标的扇形判定（粒子/声音用），口径与区块一致
        PlayerState.Snapshot face = new PlayerState.Snapshot(x, z, 0.0F, 0, 0);
        expect(ChunkView.withinCone(fan, face, 0.5D, 10.5D), "yaw=0 正前方 10 格的点落在扇形内");
        expect(!ChunkView.withinCone(fan, face, 0.5D, -10.5D), "yaw=0 正后方 10 格的点不在扇形内");
        expect(ChunkView.withinCone(fan, face, 0.5D, 0.5D), "玩家自身位置始终在扇形内");

        // fov=360 时点坐标不做方向限制
        expect(ChunkView.withinCone(all, face, 0.5D, -30.5D), "fov=360 时身后的点也放行");

        // 距离判定
        expect(ChunkView.withinDistance(face, 0.5D, 20.5D, 32.0D), "20 格在 32 格距离内");
        expect(!ChunkView.withinDistance(face, 0.5D, 40.5D, 32.0D), "40 格超出 32 格距离");
        expect(ChunkView.withinDistance(face, 0.5D, 400.5D, 0.0D), "maxDistance=0 视为不限制");
        expect(ChunkView.withinDistance(face, 0.5D, 0.5D + 48.0D, 48.0D), "48 格刚好在 48 格边界上");
        expect(!ChunkView.withinDistance(face, 0.5D, 0.5D + 48.1D, 48.0D), "48.1 格已超出 48 格");

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(Settings settings, double x, double z, float yaw,
                              int chunkX, int chunkZ, boolean expected, String label) {
        PlayerState.Snapshot snapshot = new PlayerState.Snapshot(x, z, yaw, 0, 0);
        boolean actual = ChunkView.allowed(settings, snapshot, chunkX, chunkZ);
        boolean ok = actual == expected;
        if (ok) {
            passed++;
            System.out.println("[PASS] " + label);
        } else {
            failed++;
            System.out.println("[FAIL] " + label + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void expect(boolean condition, String label) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + label);
        } else {
            failed++;
            System.out.println("[FAIL] " + label);
        }
    }
}
