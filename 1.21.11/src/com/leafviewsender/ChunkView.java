package com.leafviewsender;

/**
 * 视野判定工具：把玩家朝向换算成“允许发送的区块集合”，
 * 以及粒子/声音这类点坐标的可见性判断。
 *
 * 约定（与原版一致）：yaw = 0° 朝 +Z（南），90° 朝 -X（西），180° 朝 -Z（北），270° 朝 +X（东）。
 */
public final class ChunkView {

    private ChunkView() {
    }

    /** 把区块坐标打包成 long，作为玩家区块集合的键。 */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >>> 32);
    }

    /**
     * 该区块是否允许发送给这个玩家。
     *
     * @param snap 玩家位置与朝向快照
     */
    public static boolean allowed(Settings settings, PlayerState.Snapshot snap, int chunkX, int chunkZ) {
        // 1) 近身缓冲：玩家所在区块周围 nearRadiusChunks 范围内的区块永远发送，
        //    保证脚下的地面、以及转身瞬间身边的方块一定存在。
        int near = settings.nearRadiusChunks;
        if (near > 0) {
            int dx = chunkX - snap.chunkX();
            int dz = chunkZ - snap.chunkZ();
            if (dx * dx + dz * dz <= near * near) {
                return true;
            }
        }

        // 2) 用区块中心方向与玩家朝向比较
        double centerX = (chunkX << 4) + 8.0D;
        double centerZ = (chunkZ << 4) + 8.0D;
        return withinCone(settings, snap, centerX, centerZ);
    }

    /** 水平方向是否落在视野扇形内（fov >= 360 视为不限制）。 */
    public static boolean withinCone(Settings settings, PlayerState.Snapshot snap, double x, double z) {
        if (settings.fovDegrees >= 360.0D) {
            return true;
        }

        double dx = x - snap.x();
        double dz = z - snap.z();
        if (dx == 0.0D && dz == 0.0D) {
            return true;
        }

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = wrapDegrees(targetYaw - snap.yaw());
        return Math.abs(delta) <= settings.halfFovDegrees;
    }

    /** 水平距离是否在限制以内（maxDistance <= 0 视为不限制）。 */
    public static boolean withinDistance(PlayerState.Snapshot snap, double x, double z, double maxDistance) {
        if (maxDistance <= 0.0D) {
            return true;
        }
        double dx = x - snap.x();
        double dz = z - snap.z();
        return dx * dx + dz * dz <= maxDistance * maxDistance;
    }

    /** 把角度归一化到 [-180, 180)。 */
    public static double wrapDegrees(double degrees) {
        double value = degrees % 360.0D;
        if (value >= 180.0D) {
            value -= 360.0D;
        } else if (value < -180.0D) {
            value += 360.0D;
        }
        return value;
    }
}
