package com.ezhan.amr.navigation.area;

import java.util.ArrayList;
import java.util.List;

public final class PolygonUtils {
    private PolygonUtils() {
    }

    public static boolean pointInPolygon(double x, double y, List<Float> polygonPoints) {
        return pointInPolygon(x, y, polygonPoints, 0f);
    }

    /**
     * 带膨胀参数的点-多边形包含判断。
     * 先将多边形按 expandMeters 膨胀(正数放大, 负数缩小), 再判断点是否在膨胀后的多边形内。
     *
     * @param x             点的 x 坐标 (世界坐标, 米)
     * @param y             点的 y 坐标 (世界坐标, 米)
     * @param polygonPoints 原始多边形点列表 [x0,y0,x1,y1,...]
     * @param expandMeters  膨胀量 (米), 正数向外膨胀(提前触发), 负数向内收缩(延后触发), 0 不膨胀
     * @return 点是否在膨胀后的多边形内
     */
    public static boolean pointInPolygon(double x, double y, List<Float> polygonPoints, float expandMeters) {
        if (polygonPoints == null || polygonPoints.size() < 6) {
            return false;
        }
        if (expandMeters == 0f) {
            return pointInPolygonRaw(x, y, polygonPoints);
        }
        List<Float> expanded = expandPolygon(polygonPoints, expandMeters);
        if (expanded == null || expanded.size() < 6) {
            return false;
        }
        return pointInPolygonRaw(x, y, expanded);
    }

    /** 原始点-多边形包含判断 (射线法) */
    private static boolean pointInPolygonRaw(double x, double y, List<Float> polygonPoints) {
        boolean inside = false;
        int n = polygonPoints.size() / 2;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double x1 = polygonPoints.get(i * 2);
            double y1 = polygonPoints.get(i * 2 + 1);
            double x2 = polygonPoints.get(j * 2);
            double y2 = polygonPoints.get(j * 2 + 1);

            if (((y1 > y) != (y2 > y))
                    && (x < (x2 - x1) * (y - y1) / (y2 - y1) + x1)) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * 多边形膨胀: 沿"质心 -> 顶点"方向将每个顶点平移 expandMeters 米。
     * 正数: 顶点向外移动, 多边形放大 (用于提前触发进入);
     * 负数: 顶点向内移动, 多边形缩小 (用于延后触发进入)。
     *
     * 该算法对凸多边形近似等价于标准 polygon offset, 对凹多边形在凹角处可能略有偏差,
     * 但用于区域触发检测精度足够, 且实现简单、稳定、无自交退化(负膨胀过大时顶点会越过质心,
     * 调用方需自行限制负膨胀幅度)。
     *
     * @param polygonPoints 原始多边形点列表 [x0,y0,x1,y1,...]
     * @param expandMeters  膨胀量 (米)
     * @return 膨胀后的点列表, 输入非法返回 null
     */
    public static List<Float> expandPolygon(List<Float> polygonPoints, float expandMeters) {
        if (polygonPoints == null || polygonPoints.size() < 6) {
            return null;
        }
        int n = polygonPoints.size() / 2;
        // 计算质心
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += polygonPoints.get(i * 2);
            cy += polygonPoints.get(i * 2 + 1);
        }
        cx /= n;
        cy /= n;

        List<Float> result = new ArrayList<>(polygonPoints.size());
        for (int i = 0; i < n; i++) {
            double px = polygonPoints.get(i * 2);
            double py = polygonPoints.get(i * 2 + 1);
            double dx = px - cx;
            double dy = py - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            double nx, ny;
            if (len < 1e-6) {
                // 顶点与质心重合, 不偏移该顶点 (避免除零)
                nx = px;
                ny = py;
            } else {
                // 沿质心->顶点方向移动 expandMeters 米
                nx = px + expandMeters * (dx / len);
                ny = py + expandMeters * (dy / len);
            }
            result.add((float) nx);
            result.add((float) ny);
        }
        return result;
    }
}
