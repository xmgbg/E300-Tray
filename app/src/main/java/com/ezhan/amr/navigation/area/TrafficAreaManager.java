package com.ezhan.amr.navigation.area;

import android.util.Log;

import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.communication.lora.LoraRequestScheduler;
import com.ezhan.amr.communication.lora.TrafficLoraPacket;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.MapArea;
import com.ezhan.amr.data.datatype.OtherRobot;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交管区域状态机: 基于先进入者权限高 + LoRa 心跳广播实现多机器人互斥。
 *
 * 状态流转:
 *   IDLE -> APPROACHING  (机器人位置进入交管区域 polygon)
 *     APPROACHING: 监听 >= LISTEN_MS, 广播 APPROACHING 心跳 (每 APPROACHING_INTERVAL_MS)
 *       - 听到有人 INSIDE 或 听到更小 robotId 的 APPROACHING -> WAITING (暂停导航)
 *       - 无人 INSIDE 且无更小 robotId 竞争 -> INSIDE (获得路权)
 *     WAITING: 在边界外暂停, 每 TICK_MS 检查区域是否空闲
 *       - 区域空闲 (3s 无 INSIDE 心跳) -> resumeNavigation -> INSIDE
 *       - 等待超 WAIT_TIMEOUT_MS_MS -> 告警日志 (防永久阻塞, 继续等待)
 *     INSIDE: 已在区域内, 每 INSIDE_INTERVAL_MS 广播 INSIDE 心跳
 *   退出 polygon:
 *     INSIDE  -> 发 LEAVING burst x3, 释放
 *     WAITING -> resumeNavigation
 *
 * 死锁预防四要素:
 *   1. 互斥: 同一 (mapHash, areaNum) 同时只有一个 INSIDE
 *   2. 让行在临界区外: WAITING 状态在 polygon 边界外暂停 (polygon 配置需覆盖入口前空间)
 *   3. 确定性 tie-breaker: 同向竞争时 robotId 小者优先 (小者永不听不到更小 id, 必先 claim)
 *   4. 超时兜底: 3s 无 INSIDE 心跳视为占有者已离开/失联, 自己进入 (防僵尸持有)
 *
 * 单机器人串行化: 同一时刻只持有一个交管区域 (持有期间其他交管区域进入会直接 WAITING),
 *                 天然避免"持有并等待"循环死锁。
 */
public class TrafficAreaManager {
    private static final String TAG = "TrafficAreaManager";

    // 心跳与监听参数
    private static final long TICK_MS = 500;                  // 状态机检查周期
    private static final long LISTEN_MS = 1500;               // APPROACHING 监听时长 (3个周期)
    private static final long APPROACHING_INTERVAL_MS = 500;  // APPROACHING 心跳间隔
    private static final long INSIDE_INTERVAL_MS = 1000;      // INSIDE 心跳间隔 (1秒, 按需求)
    private static final long INSIDE_TIMEOUT_MS = 3000;       // 3s 无 INSIDE 心跳视为对方已离开
    private static final long WAIT_TIMEOUT_MS = 30000;        // 等待 30s 告警防永久阻塞
    private static final int LEAVING_BURST_COUNT = 3;         // LEAVING 连发次数
    private static final long LEAVING_BURST_INTERVAL_MS = 150;

    // 区域名解析: {mapName}_traffic{N}
    private static final Pattern AREA_NAME_PATTERN =
            Pattern.compile("^(.+?)_traffic(\\d+)$", Pattern.CASE_INSENSITIVE);

    private enum State { IDLE, APPROACHING, WAITING, INSIDE }

    /** 每个交管区域的运行时状态, key = mapHash + "_" + areaNum */
    private static class AreaState {
        final int mapHash;
        final int building;
        final int floor;
        final int areaNum;
        final String areaName;
        State state = State.IDLE;
        long stateEnterTime;
        long lastBroadcastTime;
        long lastOtherInsideTime;       // 最后一次听到他人 INSIDE 的时间
        int lastOtherInsideRobotId;     // 最后一次听到他人 INSIDE 的 robotId
        long lastOtherApproachingTime;  // 最后一次听到他人 APPROACHING 的时间
        int lastOtherApproachingRobotId;
        boolean pausedByTraffic;        // 是否因交管暂停了导航
        long waitAlertTime;             // 上次告警时间, 避免日志刷屏

        AreaState(int mapHash, int building, int floor, int areaNum, String areaName) {
            this.mapHash = mapHash;
            this.building = building;
            this.floor = floor;
            this.areaNum = areaNum;
            this.areaName = areaName;
        }

        String key() {
            return mapHash + "_" + areaNum;
        }
    }

    private final LoraRequestScheduler scheduler;
    private final BasicViewModel basicViewModel;
    private final SharedViewModel sharedViewModel;
    private final LoraCommunicator loraCommunicator;
    private final DataStoreManager dataStoreManager;

    private final Map<String, AreaState> areas = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> tickFuture;
    private volatile boolean started;

    /** 独立的心跳发送线程, 与状态机 executor 隔离, 防止 executor 停顿导致 INSIDE 心跳中断 */
    private final ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile long lastTickTime = 0;

    /** 当前持有的交管区域 key 集合 (允许重叠区域同时持有), 空集表示未持有 */
    private volatile java.util.Set<String> heldAreaKeys = new java.util.concurrent.CopyOnWriteArraySet<>();

    /** 全局交管机器人列表 (从 DataStore 加载), 用于逐个发送和白名单过滤; 空表表示不过滤 */
    private volatile List<OtherRobot> trustedRobots = Collections.emptyList();

    public TrafficAreaManager(LoraRequestScheduler scheduler,
                              BasicViewModel basicViewModel,
                              SharedViewModel sharedViewModel,
                              LoraCommunicator loraCommunicator,
                              DataStoreManager dataStoreManager) {
        this.scheduler = scheduler;
        this.basicViewModel = basicViewModel;
        this.sharedViewModel = sharedViewModel;
        this.loraCommunicator = loraCommunicator;
        this.dataStoreManager = dataStoreManager;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TrafficAreaManager");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TrafficHeartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        loadTrustedRobots();
        tickFuture = executor.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        // 独立心跳线程: 每 INSIDE_INTERVAL_MS 发送 INSIDE 心跳, 与状态机 executor 隔离
        // 防止 executor 停顿 (GC/IO/调度) 导致 INSIDE 心跳中断, 进而引发双车同时进入
        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(
                this::heartbeatTick, INSIDE_INTERVAL_MS, INSIDE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.d(TAG, "TrafficAreaManager started (tick + heartbeat)");
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (tickFuture != null) tickFuture.cancel(false);
        if (heartbeatFuture != null) heartbeatFuture.cancel(false);
        // 释放所有持有区域
        for (AreaState s : areas.values()) {
            if (s.state == State.INSIDE) {
                sendLeavingBurst(s);
            }
            if (s.pausedByTraffic) {
                resumeNavigation("stop");
            }
        }
        areas.clear();
        heldAreaKeys.clear();
        Log.d(TAG, "TrafficAreaManager stopped");
    }

    /** 从 DataStore 加载全局交管机器人列表 (用于逐个发送 + 白名单过滤) */
    private void loadTrustedRobots() {
        if (dataStoreManager == null) return;
        dataStoreManager.getTrafficRobots()
                .first(Collections.emptyList())
                .subscribe(robots -> {
                    List<OtherRobot> list = new java.util.ArrayList<>();
                    if (robots != null) {
                        for (OtherRobot r : robots) {
                            if (r != null && r.getRobotId() > 0) {
                                list.add(r);
                            }
                        }
                    }
                    trustedRobots = list;
                    StringBuilder sb = new StringBuilder();
                    for (OtherRobot r : list) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append("[id=").append(r.getRobotId())
                          .append(" ch=").append(r.getChannel())
                          .append(" addr=").append(r.getAddress()).append("]");
                    }
                    Log.d(TAG, "加载交管机器人列表: " + list.size() + " 个");
                    TrafficLog.log("LOAD trustedRobots count=" + list.size() + " -> " + sb);
                }, e -> {
                    Log.e(TAG, "加载交管机器人列表失败", e);
                    TrafficLog.log("LOAD trustedRobots ERROR: " + e.getMessage());
                });
    }

    /** 外部配置变更后重新加载白名单 (UI 保存后调用) */
    public void reloadTrustedRobots() {
        executor.execute(this::loadTrustedRobots);
    }

    /**
     * 机器人进入交管区域 (由 MapAreaTriggerManager 边沿触发调用)。
     * 在后台线程执行, 不阻塞调用方。
     */
    public void onEnterTrafficArea(MapArea area) {
        TrafficLog.log("onEnterTrafficArea CALLED: " + area.getName() + " map=" + area.getMapName());
        executor.execute(() -> handleEnter(area));
    }

    /**
     * 机器人离开交管区域 (由 MapAreaTriggerManager 边沿触发调用)。
     */
    public void onExitTrafficArea(MapArea area) {
        TrafficLog.log("onExitTrafficArea CALLED: " + area.getName() + " map=" + area.getMapName());
        executor.execute(() -> handleExit(area));
    }

    /**
     * 收到其他机器人的交管帧 (由 LoraCommunicator 分发)。
     * 在 Lora 接收线程调用, 仅更新时间戳, 不做重逻辑。
     */
    public void onTrafficPacket(TrafficLoraPacket packet) {
        if (packet == null) return;
        // 忽略自己的回环
        int myRobotId = getRobotId();
        if (packet.robotId == myRobotId) return;
        // 白名单过滤: 若配置了交管机器人列表, 仅信任列表内的 robotId
        List<OtherRobot> trusted = trustedRobots;
        if (!trusted.isEmpty()) {
            boolean found = false;
            for (OtherRobot r : trusted) {
                if (r.getRobotId() == packet.robotId) { found = true; break; }
            }
            if (!found) {
                Log.d(TAG, "忽略未配置的交管帧: robotId=" + packet.robotId);
                TrafficLog.log("RECV IGNORE robotId=" + packet.robotId + " (not in trusted list) areaNum=" + packet.areaNum);
                return;
            }
        }

        executor.execute(() -> {
            String key = packet.mapHash + "_" + packet.areaNum;
            AreaState s = areas.get(key);
            long now = System.currentTimeMillis();
            if (s == null) {
                // 不是本机关注的区域, 忽略 (本机未进入该交管区域)
                TrafficLog.log("RECV robot=" + packet.robotId + " state=" + packet.stateString()
                        + " key=" + key + " -> 本机未关注此区域, 忽略");
                return;
            }
            if (packet.isInside()) {
                s.lastOtherInsideTime = now;
                s.lastOtherInsideRobotId = packet.robotId;
                Log.d(TAG, "Heard INSIDE from robot " + packet.robotId + " in " + key);
                TrafficLog.log(s.areaName, "RECV INSIDE from robot " + packet.robotId);
                // 本机也处于 INSIDE -> 双方冲突, 立即回送 INSIDE 声明路权
                // (心跳线程会持续发送, 这里额外补发一次加快对方感知)
                if (s.state == State.INSIDE) {
                    TrafficLog.log(s.areaName, "INSIDE 冲突! 立即补发 INSIDE 心跳给 robot " + packet.robotId);
                    sendPacket(s, TrafficLoraPacket.STATE_INSIDE);
                }
            } else if (packet.isApproaching()) {
                s.lastOtherApproachingTime = now;
                s.lastOtherApproachingRobotId = packet.robotId;
                Log.d(TAG, "Heard APPROACHING from robot " + packet.robotId + " in " + key);
                TrafficLog.log(s.areaName, "RECV APPROACHING from robot " + packet.robotId);
                // 本机处于 INSIDE 时, 收到他人 APPROACHING 立即回送 INSIDE
                // 告知对方区域被占用, 避免对方 3s 超时后误判区域空闲而进入
                if (s.state == State.INSIDE) {
                    TrafficLog.log(s.areaName, "本机 INSIDE, 立即回送 INSIDE 心跳给 APPROACHING robot " + packet.robotId);
                    sendPacket(s, TrafficLoraPacket.STATE_INSIDE);
                }
            } else if (packet.isLeaving()) {
                // 对方离开, 清除其 INSIDE 占有 (若它正是占有者)
                if (s.lastOtherInsideRobotId == packet.robotId) {
                    s.lastOtherInsideTime = 0;
                    Log.d(TAG, "Heard LEAVING from holder robot " + packet.robotId + " in " + key);
                    TrafficLog.log(s.areaName, "RECV LEAVING from holder robot " + packet.robotId);
                } else {
                    TrafficLog.log(s.areaName, "RECV LEAVING from robot " + packet.robotId + " (非当前占有者, 忽略清除)");
                }
            }
        });
    }

    // ==================== 内部状态机 ====================

    private void handleEnter(MapArea area) {
        ParsedArea pr = parseArea(area);
        if (pr == null) {
            Log.w(TAG, "handleEnter: 无法解析区域名 " + area.getName());
            TrafficLog.log("handleEnter FAIL parse: " + area.getName());
            return;
        }
        int mapHash = area.getMapName() == null ? 0 : area.getMapName().hashCode();
        int building = getBuildingNo();
        int floor = getFloorNo();
        String key = mapHash + "_" + pr.number;

        AreaState existing = areas.get(key);
        if (existing != null && existing.state != State.IDLE) {
            Log.d(TAG, "handleEnter: 区域 " + key + " 已在 " + existing.state + " 状态, 忽略重复进入");
            TrafficLog.log(area.getName(), "handleEnter IGNORE (already " + existing.state + ")");
            return;
        }

        AreaState s = new AreaState(mapHash, building, floor, pr.number, area.getName());
        s.state = State.APPROACHING;
        s.stateEnterTime = System.currentTimeMillis();
        s.lastBroadcastTime = 0;
        s.lastOtherInsideTime = 0;
        s.lastOtherApproachingTime = 0;
        areas.put(key, s);
        Log.d(TAG, "handleEnter: " + area.getName() + " -> APPROACHING (key=" + key + ")");
        TrafficLog.log(area.getName(), "handleEnter -> APPROACHING key=" + key
                + " mapHash=" + Integer.toHexString(mapHash)
                + " building=" + building + " floor=" + floor
                + " num=" + pr.number + " myRobotId=" + getRobotId());
    }

    private void handleExit(MapArea area) {
        ParsedArea pr = parseArea(area);
        if (pr == null) return;
        int mapHash = area.getMapName() == null ? 0 : area.getMapName().hashCode();
        String key = mapHash + "_" + pr.number;

        AreaState s = areas.get(key);
        if (s == null) {
            return;
        }
        Log.d(TAG, "handleExit: " + area.getName() + " from state=" + s.state);
        TrafficLog.log(area.getName(), "handleExit from state=" + s.state);

        if (s.state == State.INSIDE) {
            // 离开区域: 连发 LEAVING 通知他人释放
            sendLeavingBurst(s);
            heldAreaKeys.remove(key);
        } else if (s.state == State.WAITING && s.pausedByTraffic) {
            // 等待中离开: 恢复导航
            resumeNavigation("exit_waiting");
            heldAreaKeys.remove(key);
        }
        areas.remove(key);
    }

    /** 状态机主循环, 每 TICK_MS 执行一次 */
    private void tick() {
        long now = System.currentTimeMillis();
        // 看门狗: 检测 executor 停顿 (GC/IO/调度等导致 tick 延迟)
        if (lastTickTime > 0) {
            long gap = now - lastTickTime;
            if (gap > TICK_MS * 3) {
                Log.w(TAG, "tick 停顿 " + gap + "ms (预期 " + TICK_MS + "ms), 可能导致心跳延迟");
                TrafficLog.log("WATCHDOG tick 停顿 " + gap + "ms (预期 " + TICK_MS
                        + "ms) — INSIDE 心跳由独立线程保障");
            }
        }
        lastTickTime = now;
        for (AreaState s : areas.values()) {
            try {
                switch (s.state) {
                    case APPROACHING:
                        tickApproaching(s, now);
                        break;
                    case WAITING:
                        tickWaiting(s, now);
                        break;
                    case INSIDE:
                        tickInside(s, now);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "tick error for " + s.key(), e);
            }
        }
    }

    /**
     * 独立心跳线程: 每 INSIDE_INTERVAL_MS 发送 INSIDE 心跳。
     * 与状态机 executor 隔离, 即使 tick 停顿, INSIDE 心跳也不中断。
     * 这是防止双车同时进入的关键保障: 只要 INSIDE 心跳持续, APPROACHING 侧就会暂停。
     */
    private void heartbeatTick() {
        try {
            for (AreaState s : areas.values()) {
                if (s.state == State.INSIDE) {
                    sendPacket(s, TrafficLoraPacket.STATE_INSIDE);
                    s.lastBroadcastTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "heartbeat tick error", e);
            TrafficLog.log("heartbeatTick ERROR: " + e.getMessage());
        }
    }

    /** APPROACHING: 监听 + 广播心跳, 判断能否进入 */
    private void tickApproaching(AreaState s, long now) {
        // 广播 APPROACHING 心跳
        if (now - s.lastBroadcastTime >= APPROACHING_INTERVAL_MS) {
            sendPacket(s, TrafficLoraPacket.STATE_APPROACHING);
            s.lastBroadcastTime = now;
        }

        // 监听未满 LISTEN_MS, 继续等
        if (now - s.stateEnterTime < LISTEN_MS) {
            return;
        }

        boolean someoneInside = now - s.lastOtherInsideTime < INSIDE_TIMEOUT_MS;
        if (someoneInside) {
            // 有人占有 -> 让行, 在边界外暂停
            transitionToWaiting(s, now, "有人 INSIDE (robot " + s.lastOtherInsideRobotId + ")");
            return;
        }

        // 无人 INSIDE, 检查是否有更小 robotId 的 APPROACHING 竞争
        int myRobotId = getRobotId();
        boolean smallerCompetitor = s.lastOtherApproachingRobotId != 0
                && s.lastOtherApproachingRobotId < myRobotId
                && (now - s.lastOtherApproachingTime < LISTEN_MS);
        if (smallerCompetitor) {
            transitionToWaiting(s, now, "更小 robotId 竞争者 (robot " + s.lastOtherApproachingRobotId + ")");
            return;
        }

        // 无人占有, 无更小竞争者 -> 获得路权, 进入
        // 允许同时持有多个重叠区域 (heldAreaKeys 为集合), 死锁预防靠 robotId 全局有序 + 超时释放
        TrafficLog.log(s.areaName, "APPROACHING 判定通过, 准备转 INSIDE");
        transitionToInside(s, now);
    }

    /** WAITING: 在边界外暂停, 等待区域空闲后进入 */
    private void tickWaiting(AreaState s, long now) {
        // 区域空闲 (3s 无 INSIDE 心跳) -> 进入
        boolean someoneInside = now - s.lastOtherInsideTime < INSIDE_TIMEOUT_MS;
        if (!someoneInside) {
            // 检查更小 robotId 竞争者是否已退场
            int myRobotId = getRobotId();
            boolean smallerCompetitor = s.lastOtherApproachingRobotId != 0
                    && s.lastOtherApproachingRobotId < myRobotId
                    && (now - s.lastOtherApproachingTime < LISTEN_MS);
            if (smallerCompetitor) {
                // 仍有更小竞争者, 继续等
            } else {
                // 区域空闲, 恢复导航进入 (允许同时持有重叠区域)
                TrafficLog.log(s.areaName, "WAITING 区域空闲, 准备进入 (waited="
                        + (now - s.stateEnterTime) + "ms)");
                if (s.pausedByTraffic) {
                    resumeNavigation("waiting_clear");
                }
                transitionToInside(s, now);
                return;
            }
        }

        // 等待超时告警 (防永久阻塞, 但不放弃, 继续等)
        long waited = now - s.stateEnterTime;
        if (waited > WAIT_TIMEOUT_MS && now - s.waitAlertTime > 10000) {
            s.waitAlertTime = now;
            Log.w(TAG, "交管区域 " + s.areaName + " 等待已超 " + WAIT_TIMEOUT_MS
                    + "ms, 可能存在异常, lastInsideRobot=" + s.lastOtherInsideRobotId);
            TrafficLog.log(s.areaName, "WARN 等待超 " + WAIT_TIMEOUT_MS
                    + "ms, lastInsideRobot=" + s.lastOtherInsideRobotId
                    + " lastInsideTime=" + s.lastOtherInsideTime
                    + " now=" + now + " diff=" + (now - s.lastOtherInsideTime));
        }
    }

    /** INSIDE: 心跳由独立 heartbeatTick 线程发送, 这里仅做冲突检测 */
    private void tickInside(AreaState s, long now) {
        // 冲突检测: 收到他人 INSIDE 心跳 (双方都认为自己是 INSIDE)
        if (s.lastOtherInsideRobotId != 0 && now - s.lastOtherInsideTime < INSIDE_TIMEOUT_MS) {
            if (now - s.waitAlertTime > 5000) {
                s.waitAlertTime = now;
                Log.w(TAG, s.areaName + ": 冲突! robot " + s.lastOtherInsideRobotId
                        + " 也处于 INSIDE (lastHeard=" + (now - s.lastOtherInsideTime) + "ms ago)");
                TrafficLog.log(s.areaName, "WARN 冲突! robot " + s.lastOtherInsideRobotId
                        + " 也处于 INSIDE (lastHeard=" + (now - s.lastOtherInsideTime)
                        + "ms ago) — 继续发送 INSIDE 心跳声明路权");
            }
        }
    }

    private void transitionToWaiting(AreaState s, long now, String reason) {
        Log.d(TAG, s.areaName + ": APPROACHING -> WAITING (" + reason + ")");
        TrafficLog.log(s.areaName, "APPROACHING -> WAITING (" + reason + ") myRobotId=" + getRobotId());
        s.state = State.WAITING;
        s.stateEnterTime = now;
        s.waitAlertTime = 0;
        if (!s.pausedByTraffic) {
            pauseNavigation("traffic_waiting_" + s.areaName);
            s.pausedByTraffic = true;
        }
    }

    private void transitionToInside(AreaState s, long now) {
        Log.d(TAG, s.areaName + ": -> INSIDE (获得路权, 当前持有: " + heldAreaKeys + ")");
        TrafficLog.log(s.areaName, "-> INSIDE 获得路权 heldAreaKeys=" + heldAreaKeys);
        s.state = State.INSIDE;
        s.stateEnterTime = now;
        s.lastBroadcastTime = now;
        heldAreaKeys.add(s.key());
        // 立即发送首帧 INSIDE (不等待心跳线程的 1s 间隔, 尽快告知其他机器人)
        sendPacket(s, TrafficLoraPacket.STATE_INSIDE);
        // 进入区域前确保导航已恢复 (若之前被暂停)
        if (s.pausedByTraffic) {
            resumeNavigation("enter_inside");
            s.pausedByTraffic = false;
        }
    }

    private void sendLeavingBurst(AreaState s) {
        Log.d(TAG, s.areaName + ": 发送 LEAVING burst x" + LEAVING_BURST_COUNT);
        TrafficLog.log(s.areaName, "发送 LEAVING burst x" + LEAVING_BURST_COUNT);
        for (int i = 0; i < LEAVING_BURST_COUNT; i++) {
            // 串行连发, 间隔 LEAVING_BURST_INTERVAL_MS
            final int idx = i;
            executor.schedule(() -> sendPacket(s, TrafficLoraPacket.STATE_LEAVING),
                    (long) i * LEAVING_BURST_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void sendPacket(AreaState s, byte state) {
        if (scheduler == null) return;
        int robotId = getRobotId();
        List<OtherRobot> targets = trustedRobots;
        if (targets.isEmpty()) {
            Log.w(TAG, "未配置交管机器人列表, 不发送交管帧");
            TrafficLog.log(s.areaName, "WARN 未配置交管机器人列表, 不发送帧");
            return;
        }
        // 按配置的每个机器人逐个发送 (使用各自的 channel + address)
        for (OtherRobot r : targets) {
            int channel = parseChannel(r.getChannel());
            int addr = parseAddress(r.getAddress());
            if (channel < 0 || addr < 0) {
                Log.w(TAG, "跳过配置无效的机器人: " + r.getRobotId()
                        + " channel=" + r.getChannel() + " address=" + r.getAddress());
                TrafficLog.log(s.areaName, "WARN 跳过无效配置 robotId=" + r.getRobotId()
                        + " channel=" + r.getChannel() + " address=" + r.getAddress());
                continue;
            }
            String hex = TrafficLoraPacket.buildHex(robotId, s.mapHash, s.building, s.floor,
                    s.areaNum, state, channel, addr);
            scheduler.enqueue("TrafficArea", hex, false, 0, LoraRequestScheduler.PRIORITY_INFO_AREA + 10);
            Log.d(TAG, "发送交管帧 -> robot " + r.getRobotId()
                    + " (" + s.areaName + " state=" + stateByteToString(state)
                    + " channel=" + channel + " addr=" + addr + ")");
            TrafficLog.logHex("SEND",
                    "robot=" + r.getRobotId() + " state=" + stateByteToString(state)
                    + " area=" + s.areaName + " hex=" + hex);
        }
    }

    private int parseChannel(String ch) {
        try { return Integer.parseInt(ch != null ? ch.trim() : ""); }
        catch (Exception e) { return -1; }
    }

    private int parseAddress(String addr) {
        try {
            String a = addr != null ? addr.trim() : "";
            if (a.startsWith("0x") || a.startsWith("0X")) a = a.substring(2);
            return Integer.parseInt(a, 16);
        } catch (Exception e) { return -1; }
    }

    // ==================== 依赖获取 ====================

    private int getRobotId() {
        Integer id = basicViewModel.getRobotId().getValue();
        return id != null ? id : 1;
    }

    private int getLoraChannel() {
        Integer ch = basicViewModel.getLoraChannel().getValue();
        return ch != null ? ch : -1;
    }

    private int getBuildingNo() {
        String mapName = getCurrentMapName();
        String buildingId = com.ezhan.amr.data.datatype.MultiBuildingMapPoints.extractBuildingId(mapName);
        try {
            return Integer.parseInt(buildingId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int getFloorNo() {
        String mapName = getCurrentMapName();
        Integer f = com.ezhan.amr.data.datatype.MultiBuildingMapPoints.extractMapFloor(mapName);
        return f != null ? f : 0;
    }

    /** 从底盘状态响应获取当前 mapName (自动获取, 非手动配置) */
    private String getCurrentMapName() {
        com.ezhan.amr.data.datatype.AgvStatusResponse resp = sharedViewModel.getLastStatusResponse();
        if (resp == null || resp.data == null || resp.data.pos == null) {
            return null;
        }
        return resp.data.pos.mapName;
    }

    private void pauseNavigation(String reason) {
        GeneralNavigationHandler handler = sharedViewModel.getNavigationHandler();
        if (handler != null) {
            handler.pauseNavigation();
            Log.d(TAG, "pauseNavigation: " + reason);
        }
    }

    private void resumeNavigation(String reason) {
        GeneralNavigationHandler handler = sharedViewModel.getNavigationHandler();
        if (handler != null) {
            handler.resumeNavigation();
            Log.d(TAG, "resumeNavigation: " + reason);
        }
    }

    // ==================== 工具 ====================

    private static String stateByteToString(byte state) {
        switch (state) {
            case TrafficLoraPacket.STATE_APPROACHING: return "APPROACHING";
            case TrafficLoraPacket.STATE_INSIDE:      return "INSIDE";
            case TrafficLoraPacket.STATE_LEAVING:     return "LEAVING";
            default: return "UNKNOWN";
        }
    }

    /** 解析区域名 {mapName}_traffic{N}, 返回 number; 无法解析返回 null */
    private static ParsedArea parseArea(MapArea area) {
        if (area == null || area.getName() == null) return null;
        Matcher m = AREA_NAME_PATTERN.matcher(area.getName());
        if (!m.matches()) return null;
        try {
            int number = Integer.parseInt(m.group(2));
            return new ParsedArea(number);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class ParsedArea {
        final int number;
        ParsedArea(int number) { this.number = number; }
    }
}
