package com.ezhan.amr.navigation.task;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Navigation Elevator Manager - Independent lifecycle manager for elevator occupancy queries
 * NOT tied to route/position/command lifecycle
 *
 * This manager periodically queries elevator occupied status via LoRa communication
 * and provides real-time availability information for elevator selection.
 */
public class NavigationElevatorManager implements AbstractElevatorManager {
    private static final Logger LOG = LoggerFactory.getLogger(NavigationElevatorManager.class);
    private static NavigationElevatorManager instance;

    // Configuration constants
    private static final long ELEVATOR_QUERY_INTERVAL_MS = 3000; // 3 seconds
    private static final long QUERY_TIMEOUT_MS = 3000; // 3 seconds timeout for LoRa

    // State tracking
    private final Map<String, ElevatorStatus> elevatorStatusMap = new ConcurrentHashMap<>();
    private boolean isInitialized = false;

    // Independent scheduler for elevator operations
    private ScheduledExecutorService elevatorScheduler;
    private ScheduledFuture<?> elevatorQueryFuture;
    private boolean isSchedulerRunning = false;
    private long schedulerStartTime = 0;
    private long schedulerStopTime = 0;
    private int schedulerStopCount = 0;
    private String lastStopReason = "";

    // References
    private SharedViewModel sharedViewModel;
    private MapViewModel mapViewModel;
    private ElevatorViewModel elevatorViewModel;
    private BasicViewModel basicViewModel;
    private NavigationStateDebugger debugger;
    private final Object statusLock = new Object();

    // Query state
    private boolean isQueryInProgress = false;
    private long lastQueryTime = 0;

    private NavigationElevatorManager() {}

    public static synchronized NavigationElevatorManager getInstance() {
        if (instance == null) {
            instance = new NavigationElevatorManager();
        }
        return instance;
    }

    @Override
    public void initElevatorManager() {
        LOG.info("NavigationElevatorManager initializing with independent scheduler");
        debugger = NavigationStateDebugger.getInstance();

        debugger.logInfo("========== ELEVATOR MANAGER INITIALIZATION ==========");
        debugger.logInfo("Initializing NavigationElevatorManager with independent lifecycle...");

        // Get references
        if (MyApplication.getInstance() != null) {
            sharedViewModel = MyApplication.getInstance().getSharedViewModel();
            mapViewModel = MyApplication.getInstance().getMapViewModel();
            elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
            basicViewModel = MyApplication.getInstance().getBasicViewModel();
            debugger.logInfo("NavigationElevatorManager references obtained: " +
                    "sharedVM=" + (sharedViewModel != null) +
                    ", mapVM=" + (mapViewModel != null) +
                    ", elevatorVM=" + (elevatorViewModel != null) +
                    ", basicVM=" + (basicViewModel != null));
        } else {
            debugger.logError("MyApplication instance is null during NavigationElevatorManager initialization", "");
        }

        // Initialize elevator statuses from configs
        initializeElevatorStatuses();

        startElevatorScheduler();
        isInitialized = true;
        debugger.logInfo("========== ELEVATOR MANAGER INITIALIZATION COMPLETE ==========");
    }

    /**
     * Initialize elevator status map from ElevatorViewModel configs
     */
    private void initializeElevatorStatuses() {
        if (elevatorViewModel == null) {
            debugger.logWarning("ElevatorViewModel is null, cannot initialize elevator statuses", "");
            return;
        }

        List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
        if (configs != null) {
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                String configId = config.getId();
                if (!elevatorStatusMap.containsKey(configId)) {
                    elevatorStatusMap.put(configId, new ElevatorStatus(
                            configId,
                            config.getChannel(),
                            config.getAddress(),
                            config.getBuildingId(),
                            false, // Initially available
                            null   // No robot using it
                    ));
                    debugger.logInfo("Initialized elevator status for config: " + configId +
                            " (building=" + config.getBuildingId() + ")");
                }
            }
            LOG.info("Initialized {} elevator statuses", elevatorStatusMap.size());
        } else {
            debugger.logWarning("No elevator configs found during initialization", "");
        }
    }

    /**
     * Start the independent elevator scheduler
     */
    private synchronized void startElevatorScheduler() {
        debugger.logInfo("========== STARTING ELEVATOR SCHEDULER ==========");
        debugger.logInfo("Current state: elevatorScheduler=" + (elevatorScheduler != null ? "exists" : "null") +
                ", isSchedulerRunning=" + isSchedulerRunning);

        if (elevatorScheduler == null || elevatorScheduler.isShutdown()) {
            elevatorScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ElevatorManagerScheduler");
                t.setDaemon(true);
                return t;
            });
            isSchedulerRunning = true;
            schedulerStartTime = System.currentTimeMillis();
            schedulerStopCount = 0;
            debugger.logInfo("Elevator manager independent scheduler created and started at " + schedulerStartTime);
            LOG.info("Elevator manager scheduler started");
        } else {
            debugger.logInfo("Elevator scheduler already exists and is running - reusing existing scheduler");
        }

//        // Schedule elevator query (runs every 3 seconds)
//        if (elevatorQueryFuture == null || elevatorQueryFuture.isDone()) {
//            elevatorQueryFuture = elevatorScheduler.scheduleWithFixedDelay(
//                    this::queryAllElevatorsOccupiedStatus,
//                    1000, ELEVATOR_QUERY_INTERVAL_MS, TimeUnit.MILLISECONDS
//            );
//            debugger.logInfo("Elevator query scheduled - initialDelay=1000ms, interval=" +
//                    ELEVATOR_QUERY_INTERVAL_MS + "ms");
//            LOG.info("Elevator query scheduled every {}ms", ELEVATOR_QUERY_INTERVAL_MS);
//        } else {
//            debugger.logInfo("Elevator query future already exists and is not done - state: cancelled=" +
//                    elevatorQueryFuture.isCancelled() + ", done=" + elevatorQueryFuture.isDone());
//        }

        debugger.logInfo("========== ELEVATOR SCHEDULER STARTED ==========");
    }

    /**
     * Stop the independent elevator scheduler
     */
    @Override
    public synchronized void stopElevatorScheduler(String reason) {
        schedulerStopCount++;
        schedulerStopTime = System.currentTimeMillis();
        lastStopReason = reason;

        debugger.logWarning("========== STOPPING ELEVATOR SCHEDULER ==========",
                "Reason: " + reason + ", Stop count: " + schedulerStopCount);
        debugger.logInfo("Scheduler uptime before stop: " + (schedulerStopTime - schedulerStartTime) + "ms");

        if (elevatorQueryFuture != null) {
            boolean cancelled = elevatorQueryFuture.cancel(false);
            debugger.logInfo("Elevator query future cancelled: " + cancelled +
                    ", wasDone=" + elevatorQueryFuture.isDone() +
                    ", wasCancelled=" + elevatorQueryFuture.isCancelled());
            elevatorQueryFuture = null;
        }

        if (elevatorScheduler != null && !elevatorScheduler.isShutdown()) {
            elevatorScheduler.shutdown();
            debugger.logInfo("Elevator scheduler shutdown initiated");
            try {
                if (!elevatorScheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    debugger.logWarning("Elevator scheduler did not terminate gracefully", "Forcing shutdown now");
                    elevatorScheduler.shutdownNow();
                } else {
                    debugger.logInfo("Elevator scheduler terminated gracefully");
                }
            } catch (InterruptedException e) {
                debugger.logWarning("Interrupted while waiting for scheduler termination", e.getMessage());
                elevatorScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            elevatorScheduler = null;
        }
        isSchedulerRunning = false;
        isInitialized = false;
        debugger.logInfo("========== ELEVATOR SCHEDULER STOPPED ==========");
        LOG.info("Elevator manager scheduler stopped: {}", reason);
    }

    @Override
    public boolean isSchedulerRunning() {
        boolean running = isSchedulerRunning && elevatorScheduler != null && !elevatorScheduler.isShutdown();
        debugger.log("isSchedulerRunning=" + running +
                " (flag=" + isSchedulerRunning +
                ", scheduler=" + (elevatorScheduler != null) +
                ", shutdown=" + (elevatorScheduler != null && elevatorScheduler.isShutdown()) + ")");
        return running;
    }

    @Override
    public void queryAllElevatorsOccupiedStatus() {
        // Check if scheduler is still running
        if (!isSchedulerRunning || elevatorScheduler == null || elevatorScheduler.isShutdown()) {
            debugger.logWarning("queryAllElevatorsOccupiedStatus skipped - scheduler not running",
                    "isSchedulerRunning=" + isSchedulerRunning);
            return;
        }

        // Prevent overlapping queries
        if (isQueryInProgress) {
            debugger.log("queryAllElevatorsOccupiedStatus: Query already in progress, skipping");
            return;
        }

        // Refresh elevator configs in case new elevators were added
        refreshElevatorConfigs();

        synchronized (statusLock) {
            isQueryInProgress = true;
        }

        try {
            // Get all elevator configs from ElevatorViewModel
            List<ElevatorViewModel.ElevatorConfig> configs =
                    elevatorViewModel != null ? elevatorViewModel.getElevatorConfigs().getValue() : null;

            if (configs == null || configs.isEmpty()) {
                debugger.log("queryAllElevatorsOccupiedStatus: No elevator configs available");
                synchronized (statusLock) {
                    isQueryInProgress = false;
                }
                return;
            }

            debugger.log("queryAllElevatorsOccupiedStatus: Querying " + configs.size() + " elevators");

            // Query each elevator
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                String configId = config.getId();
                ElevatorStatus status = elevatorStatusMap.get(configId);
                if (status == null) {
                    // Create new status entry if it doesn't exist
                    status = new ElevatorStatus(
                            configId,
                            config.getChannel(),
                            config.getAddress(),
                            config.getBuildingId(),
                            false,
                            null
                    );
                    elevatorStatusMap.put(configId, status);
                }

                // Query this elevator's occupied status
                QueryResult result = queryElevatorOccupiedStatus(config);

                // Update status
                status.setOccupied(!result.isNotOccupied);
                status.setOccupiedByRobotId(result.robotId);
                status.setLastUpdateTime(System.currentTimeMillis());
                status.setLastQuerySuccess(result.success);

                if (debugger != null) {
                    debugger.log("Elevator " + configId + " (building=" + config.getBuildingId() +
                            ") available: " + result.isNotOccupied +
                            ", robotId: " + (result.robotId != null ? result.robotId : "none"));
                }
            }

            lastQueryTime = System.currentTimeMillis();

        } catch (Exception e) {
            debugger.logError("Error querying elevators: " + e.getMessage(), "");
            LOG.error("Error querying elevators: {}", e.getMessage(), e);
        } finally {
            synchronized (statusLock) {
                isQueryInProgress = false;
            }
        }
    }

    /**
     * Query a specific elevator's occupied status using LoRa communication
     * This uses the same mechanism as ElevatorViewModel.queryElevatorAccess()
     *
     * @param config The elevator configuration
     * @return QueryResult containing isNotOccupied and robotId if occupied
     */
    private QueryResult queryElevatorOccupiedStatus(ElevatorViewModel.ElevatorConfig config) {
        if (config == null) {
            debugger.logError("queryElevatorOccupiedStatus", "Config is null");
            return new QueryResult(false, null, false);
        }

        debugger.log(String.format("🔍 [QUERY_OCCUPIED] Starting query for elevator config ID: %s, Channel: %d, Address: %d",
                config.getId(), config.getChannel(), config.getAddress()));

        try {
            if (elevatorViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
                }
                if (elevatorViewModel == null) {
                    debugger.logError("queryElevatorOccupiedStatus", "ElevatorViewModel is null");
                    return new QueryResult(false, null, false);
                }
            }

            if (basicViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    basicViewModel = MyApplication.getInstance().getBasicViewModel();
                }
                if (basicViewModel == null) {
                    debugger.logError("queryElevatorOccupiedStatus", "BasicViewModel is null");
                    return new QueryResult(false, null, false);
                }
            }

            // Get robot parameters
            Integer robotId = basicViewModel.getRobotId().getValue();
            Integer loraChannel = basicViewModel.getLoraChannel().getValue();
            Integer loraAddress = basicViewModel.getLoraAddress().getValue();

            debugger.log(String.format("🔍 [QUERY_OCCUPIED] Robot params - ID: %d, Channel: %d, Address: %d",
                    robotId != null ? robotId : 1,
                    loraChannel != null ? loraChannel : 0,
                    loraAddress != null ? loraAddress : 0));

            if (robotId == null) robotId = 1;
            if (loraChannel == null) loraChannel = 0;
            if (loraAddress == null) loraAddress = 0;

            // Build parameters for the query access command
            Map<String, Object> hexParams = new HashMap<>();
            hexParams.put("channel", String.format("%02X", config.getChannel()));
            hexParams.put("address", String.format("%02X", config.getAddress()));
            hexParams.put("robotId", String.format("%02X", robotId));
            hexParams.put("robotChannel", String.format("%02X", loraChannel));
            hexParams.put("robotAddress", String.format("%02X", loraAddress));

            debugger.log(String.format("🔍 [QUERY_OCCUPIED] Hex params built - channel: %s, address: %s, robotId: %s",
                    hexParams.get("channel"), hexParams.get("address"), hexParams.get("robotId")));

            // Build the command using CommandBuilder
            String command = CommandBuilder.buildHexMessage(
                    NavigationOrderType.OP_ELEVATOR_QUERY_ACCESS,
                    hexParams
            );

            debugger.log(String.format("🔍 [QUERY_OCCUPIED] Sending command: %s", command));

            // Send the command synchronously with timeout
            long startTime = System.currentTimeMillis();
            String response = MyApplication.getInstance()
                    .getSharedViewModel()
                    .loraCommunicator
                    .sendMessage(command, true);

            long elapsedMs = System.currentTimeMillis() - startTime;
            debugger.log(String.format("🔍 [QUERY_OCCUPIED] Response received in %d ms: %s",
                    elapsedMs, response != null ? response : "null"));

            if (response == null || response.isEmpty()) {
                debugger.logWarning("queryElevatorOccupiedStatus", "Empty response received");
                return new QueryResult(false, null, false);
            }

            // Parse the response
            return parseQueryAccessResponse(response, robotId);

        } catch (Exception e) {
            debugger.logError("queryElevatorOccupiedStatus",
                    String.format("Error querying elevator: %s", e.getMessage()));
            LOG.error("Error querying elevator occupancy for config {}: {}", config.getId(), e.getMessage(), e);
            return new QueryResult(false, null, false);
        }
    }

    /**
     * Parse the query access response to determine if elevator is not occupied
     * Returns both isNotOccupied and the robotId if occupied
     */
    private QueryResult parseQueryAccessResponse(String response, int robotId) {
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Parsing response for robotId: %d", robotId));

        if (response == null || response.isEmpty()) {
            debugger.logWarning("parseQueryAccessResponse", "Empty response received");
            return new QueryResult(false, null, false);
        }

        // Clean response by removing spaces
        String cleanResponse = response.replace(" ", "");
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Cleaned response: %s (length: %d)",
                cleanResponse, cleanResponse.length()));

        if (cleanResponse.length() < 16) {
            debugger.logWarning("parseQueryAccessResponse",
                    String.format("Response too short: %d chars, expected at least 16", cleanResponse.length()));
            return new QueryResult(false, null, false);
        }

        // Check function code at positions 8-10 (should be "05" for query access)
        String functionCode = cleanResponse.substring(8, 10);
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Function code at positions 8-10: %s", functionCode));

        if (!"05".equals(functionCode)) {
            debugger.logWarning("parseQueryAccessResponse",
                    String.format("Invalid function code: expected '05', got '%s'", functionCode));
            return new QueryResult(false, null, false);
        }
        debugger.log("🔍 [PARSE_QUERY_RESPONSE] Function code verified: 05");

        // Get the robot ID from the response at positions 12-14
        String responseRobotId = cleanResponse.substring(12, 14);
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Robot ID from response at positions 12-14: %s", responseRobotId));

        String expectedRobotId = String.format("%02X", robotId);
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Expected robot ID: %s", expectedRobotId));

        boolean robotIdMatches = responseRobotId.equals(expectedRobotId);
        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] Robot ID match: %b (response=%s, expected=%s)",
                robotIdMatches, responseRobotId, expectedRobotId));

        // Treat both "00" AND "FF" as no robot (available)
        boolean isAvailable = "00".equals(responseRobotId) || "FF".equals(responseRobotId);

        boolean isOccupied = robotIdMatches || (!isAvailable);

        boolean isNotOccupied = !isOccupied;
        String occupiedByRobotId = isOccupied ? responseRobotId : null;

        debugger.log(String.format("🔍 [PARSE_QUERY_RESPONSE] ✅ Final result - isNotOccupied: %b, occupiedByRobotId: %s",
                isNotOccupied, occupiedByRobotId));

        return new QueryResult(isNotOccupied, occupiedByRobotId, true);
    }

    /**
     * Refresh elevator configs from ElevatorViewModel
     */
    private void refreshElevatorConfigs() {
        if (elevatorViewModel == null) {
            if (MyApplication.getInstance() != null) {
                elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
            }
            if (elevatorViewModel == null) return;
        }

        List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
        if (configs != null) {
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                String configId = config.getId();
                if (!elevatorStatusMap.containsKey(configId)) {
                    ElevatorStatus status = new ElevatorStatus(
                            configId,
                            config.getChannel(),
                            config.getAddress(),
                            config.getBuildingId(),
                            false,
                            null
                    );
                    elevatorStatusMap.put(configId, status);
                    debugger.logInfo("Added new elevator config to status map: " + configId);
                } else {
                    ElevatorStatus status = elevatorStatusMap.get(configId);
                    if (status.getChannel() != config.getChannel() ||
                            status.getAddress() != config.getAddress()) {
                        status.setChannel(config.getChannel());
                        status.setAddress(config.getAddress());
                        status.setBuildingId(config.getBuildingId());
                        debugger.logInfo("Updated elevator config: " + configId);
                    }
                }
            }
        }
    }

    @Override
    public boolean isElevatorOccupied(String elevatorConfigId) {
        ElevatorStatus status = elevatorStatusMap.get(elevatorConfigId);
        if (status == null) {
            debugger.logWarning("isElevatorOccupied: No status for config " + elevatorConfigId, "");
            return false;
        }
        return status.isOccupied();
    }

    @Override
    public Map<String, Boolean> getAllElevatorOccupiedStatus() {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, ElevatorStatus> entry : elevatorStatusMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().isOccupied());
        }
        return result;
    }

    @Override
    public String getElevatorIdForPosition(Position position) {
        if (position == null) {
            return null;
        }

        Position.ElevatorInfo elevatorInfo = position.getElevatorInfo();
        if (elevatorInfo != null && elevatorInfo.getElevatorId() != null) {
            return elevatorInfo.getElevatorId();
        }

        String posName = position.getName();
        if (posName != null) {
            for (String configId : elevatorStatusMap.keySet()) {
                if (posName.contains(configId) || posName.contains("elevator_" + configId)) {
                    return configId;
                }
            }
        }

        return null;
    }

    @Override
    public boolean isElevatorAvailable(String elevatorConfigId) {
        ElevatorStatus status = elevatorStatusMap.get(elevatorConfigId);
        if (status == null) {
            return true;
        }
        return !status.isOccupied();
    }

    /**
     * Trigger an immediate query of elevator statuses
     */
    @Override
    public void triggerImmediateQuery() {
        if (!isQueryInProgress) {
            debugger.logInfo("triggerImmediateQuery: Starting immediate elevator query");
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                queryAllElevatorsOccupiedStatus();
            }).start();
        } else {
            debugger.log("triggerImmediateQuery: Query already in progress, skipping");
        }
    }

    /**
     * Get the elevator the robot is currently using from SharedViewModel's inUseElevator.
     * This is the authoritative source since we explicitly track elevator usage during navigation.
     *
     * @return The elevator identifier string in format: "mapPrefix_buildingId_elevatorAddress_elevatorChannel",
     *         or null if the robot is not using any elevator
     */
    @Override
    public String getRobotCurrentElevator() {
        try {
            if (sharedViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    sharedViewModel = MyApplication.getInstance().getSharedViewModel();
                }
                if (sharedViewModel == null) {
                    debugger.logError("getRobotCurrentElevator", "SharedViewModel is null");
                    return null;
                }
            }

            if (mapViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    mapViewModel = MyApplication.getInstance().getMapViewModel();
                }
                if (mapViewModel == null) {
                    debugger.logError("getRobotCurrentElevator", "MapViewModel is null");
                    return null;
                }
            }

            // Get the current in-use elevator from SharedViewModel
            String inUseElevatorId = sharedViewModel.getInUseElevatorValue();

            if (inUseElevatorId == null || inUseElevatorId.isEmpty()) {
                debugger.log("🔍 [GET_ROBOT_ELEVATOR] Robot is not using any elevator (inUseElevator is null)");
                return null;
            }

            debugger.log(String.format("🔍 [GET_ROBOT_ELEVATOR] Robot is using elevator ID: %s (from SharedViewModel)",
                    inUseElevatorId));

            // Get map prefix and building ID from current map
            String currentMap = mapViewModel.getCurrentMap();
            String buildingId = mapViewModel.getCurrentBuilding();

            if (currentMap == null || currentMap.isEmpty()) {
                debugger.logWarning("getRobotCurrentElevator", "Current map is null or empty");
                return null;
            }
            if (buildingId == null || buildingId.isEmpty()) {
                debugger.logWarning("getRobotCurrentElevator", "Current building ID is null or empty");
                return null;
            }

            // Get the elevator config details from ElevatorViewModel
            if (elevatorViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
                }
                if (elevatorViewModel == null) {
                    debugger.logError("getRobotCurrentElevator", "ElevatorViewModel is null");
                    return null;
                }
            }

            // Find the elevator config with matching ID
            List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
            if (configs == null || configs.isEmpty()) {
                debugger.logWarning("getRobotCurrentElevator", "No elevator configs available");
                return null;
            }

            ElevatorViewModel.ElevatorConfig matchingConfig = null;
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                if (config.getId().equals(inUseElevatorId)) {
                    matchingConfig = config;
                    break;
                }
            }

            if (matchingConfig == null) {
                debugger.logWarning("getRobotCurrentElevator",
                        "No elevator config found for ID: " + inUseElevatorId);
                return null;
            }

            return inUseElevatorId;

        } catch (Exception e) {
            debugger.logError("getRobotCurrentElevator", "Error: " + e.getMessage());
            LOG.error("Error getting robot's current elevator from SharedViewModel: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String getDetailedStateInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NavigationElevatorManager State ===\n");
        sb.append("  isInitialized: ").append(isInitialized).append("\n");
        sb.append("  isSchedulerRunning: ").append(isSchedulerRunning).append("\n");
        sb.append("  schedulerStartTime: ").append(schedulerStartTime).append("\n");
        sb.append("  schedulerStopCount: ").append(schedulerStopCount).append("\n");
        sb.append("  lastStopReason: ").append(lastStopReason).append("\n");
        sb.append("  lastQueryTime: ").append(lastQueryTime);
        if (lastQueryTime > 0) {
            sb.append(" (").append(System.currentTimeMillis() - lastQueryTime).append("ms ago)\n");
        } else {
            sb.append("\n");
        }
        sb.append("  isQueryInProgress: ").append(isQueryInProgress).append("\n");
        sb.append("  elevators in map: ").append(elevatorStatusMap.size()).append("\n");

        for (Map.Entry<String, ElevatorStatus> entry : elevatorStatusMap.entrySet()) {
            ElevatorStatus status = entry.getValue();
            sb.append("    Elevator ").append(entry.getKey()).append(": ")
                    .append(status.isOccupied() ? "OCCUPIED" : "AVAILABLE");
            if (status.isOccupied()) {
                sb.append(" (robotId: ").append(status.getOccupiedByRobotId()).append(")");
            }
            sb.append(" (building=").append(status.getBuildingId())
                    .append(", channel=").append(status.getChannel())
                    .append(", address=").append(status.getAddress())
                    .append(", lastUpdate=");
            if (status.getLastUpdateTime() > 0) {
                sb.append(System.currentTimeMillis() - status.getLastUpdateTime()).append("ms ago");
            } else {
                sb.append("never");
            }
            sb.append(")\n");
        }
        sb.append("=================================================");
        return sb.toString();
    }

    @Override
    public Map<String, Boolean> getElevatorAvailabilityForFloor(String buildingId, int floor) {
        Map<String, Boolean> availability = new HashMap<>();

        // Step 1: Check if cloud IP and port are configured
        String cloudIp = getCloudIp();
        Integer cloudPort = getCloudPort();

        if (cloudIp != null && !cloudIp.isEmpty() && cloudPort != null && cloudPort > 0) {
            debugger.log(String.format("🔍 [GET_ELEVATOR_AVAILABILITY] Cloud configured - IP: %s, Port: %d", cloudIp, cloudPort));

            // Step 2: Try to get elevator from cloud
            String elevatorConfigId = getElevatorFromCloud(buildingId, floor);

            if (elevatorConfigId != null && !elevatorConfigId.isEmpty()) {
                // Cloud returned an elevator - check its availability
                boolean isAvailable = isElevatorAvailable(elevatorConfigId);
                availability.put(elevatorConfigId, isAvailable);
                debugger.logInfo("✅ [GET_ELEVATOR_AVAILABILITY] Using cloud elevator: " + elevatorConfigId +
                        ", available: " + isAvailable);
                return availability;
            } else {
                debugger.logWarning("getElevatorAvailabilityForFloor",
                        "Cloud returned null elevator - falling back to local logic");
            }
        } else {
            debugger.log("🔍 [GET_ELEVATOR_AVAILABILITY] No cloud configuration (IP: " +
                    (cloudIp != null && !cloudIp.isEmpty() ? "set" : "null/empty") +
                    ", Port: " + (cloudPort != null && cloudPort > 0 ? cloudPort : "null/0") +
                    ") - using local logic");
        }

        // Step 3: Fallback to current local logic
        return getElevatorAvailabilityFromLocal(buildingId, floor);
    }

    /**
     * Get elevator configuration from cloud server
     * This method runs on a background thread to avoid NetworkOnMainThreadException
     *
     * @param buildingId The building ID
     * @param floor The floor number
     * @return Elevator configuration ID if found, null otherwise
     */
    private String getElevatorFromCloud(String buildingId, int floor) {
        debugger.log(String.format("🔍 [GET_ELEVATOR_FROM_CLOUD] Requesting elevator for building: %s, floor: %d",
                buildingId, floor));

        try {
            String cloudIp = getCloudIp();
            Integer cloudPort = getCloudPort();

            String currentMap = MultiBuildingMapPoints.generateMapName(mapViewModel.getCurrentMap(),
                    mapViewModel.getCurrentBuilding(), mapViewModel.getCurrentFloor());
            String targetMap = MultiBuildingMapPoints.generateMapName(mapViewModel.getCurrentMap(),
                    buildingId, floor);

            if (cloudIp == null || cloudIp.isEmpty() || cloudPort == null || cloudPort <= 0) {
                debugger.logWarning("getElevatorFromCloud", "Cloud configuration not available");
                return null;
            }

            // Build the cloud API URL
            String url = "http://" + cloudIp + ":" + cloudPort + "/device/elevator/available";
            debugger.log(String.format("🔍 [GET_ELEVATOR_FROM_CLOUD] URL: %s, buildingId: %s, floor: %d",
                    url, buildingId, floor));

            // Build request body
            org.json.JSONObject requestBody = new org.json.JSONObject();
            requestBody.put("deviceId", getRobotId());
            requestBody.put("currentMap", currentMap);
            requestBody.put("targetMap", targetMap);


            debugger.log(String.format("🔍 [GET_ELEVATOR_FROM_CLOUD] Request body: %s", requestBody.toString()));

            // Use a CountDownLatch to wait for the background thread
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] result = new String[1];
            final Exception[] error = new Exception[1];

            // Run network request on background thread
            new Thread(() -> {
                try {
                    result[0] = doCloudRequest(url, requestBody);
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }, "ElevatorCloudRequest").start();

            // Wait for completion with timeout
            boolean completed = latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!completed) {
                debugger.logWarning("getElevatorFromCloud", "Cloud request timed out after 5 seconds");
                return null;
            }

            if (error[0] != null) {
                debugger.logError("getElevatorFromCloud", "Exception: " + error[0].getMessage());
                LOG.error("Error getting elevator from cloud: {}", error[0].getMessage(), error[0]);
                return null;
            }

            return result[0];

        } catch (Exception e) {
            debugger.logError("getElevatorFromCloud", "Exception: " + e.getMessage());
            LOG.error("Error getting elevator from cloud: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Perform the actual cloud HTTP request (runs on background thread)
     */
    private String doCloudRequest(String url, org.json.JSONObject requestBody) throws Exception {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL urlObj = new java.net.URL(url);
            connection = (java.net.HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);

            // Write request body
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = connection.getResponseCode();
            debugger.log(String.format("🔍 [GET_ELEVATOR_FROM_CLOUD] Response code: %d", responseCode));

            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                try (java.io.InputStream is = connection.getInputStream()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    String responseStr = response.toString();
                    debugger.log(String.format("🔍 [GET_ELEVATOR_FROM_CLOUD] Response: %s", responseStr));

                    // Parse response
                    org.json.JSONObject jsonResponse = new org.json.JSONObject(responseStr);

                    // Check response code
                    int code = jsonResponse.optInt("code", -1);
                    if (code != 200) {
                        String msg = jsonResponse.optString("msg", "Unknown error");
                        debugger.logWarning("getElevatorFromCloud", "Cloud returned error code: " + code + ", msg: " + msg);
                        return null;
                    }

                    // Extract data object
                    org.json.JSONObject data = jsonResponse.optJSONObject("data");
                    if (data == null) {
                        debugger.logWarning("getElevatorFromCloud", "No data object in cloud response");
                        return null;
                    }

                    // Get elevatorId from data
                    String elevatorId = data.optString("elevatorId", null);
                    boolean available = data.optBoolean("available", false);

                    debugger.logInfo("✅ [GET_ELEVATOR_FROM_CLOUD] Received elevator: " + elevatorId +
                            ", available: " + available);

                    if (elevatorId != null && !elevatorId.isEmpty() && !"null".equals(elevatorId)) {
                        return elevatorId;
                    } else {
                        debugger.logWarning("getElevatorFromCloud", "No elevator found in cloud response data");
                        return null;
                    }
                }
            } else {
                // Read error response
                try (java.io.InputStream es = connection.getErrorStream()) {
                    if (es != null) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(es, java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder error = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            error.append(line);
                        }
                        debugger.logWarning("getElevatorFromCloud", "Error response: " + error.toString());
                    }
                }
                debugger.logWarning("getElevatorFromCloud", "Cloud request failed with code: " + responseCode);
                return null;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Get elevator availability using local logic (original implementation)
     */
    private Map<String, Boolean> getElevatorAvailabilityFromLocal(String buildingId, int floor) {
        debugger.log("🔍 [GET_ELEVATOR_AVAILABILITY_LOCAL] Using local logic for building: " +
                buildingId + ", floor: " + floor);

        Map<String, Boolean> availability = new HashMap<>();

        if (elevatorViewModel == null) {
            if (MyApplication.getInstance() != null) {
                elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
            }
            if (elevatorViewModel == null) {
                debugger.logWarning("getElevatorAvailabilityFromLocal", "ElevatorViewModel is null");
                return availability;
            }
        }

        List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
        if (configs == null) {
            debugger.logWarning("getElevatorAvailabilityFromLocal", "No elevator configs available");
            return availability;
        }

        debugger.log(String.format("🔍 [GET_ELEVATOR_AVAILABILITY_LOCAL] Checking %d elevator configs", configs.size()));

        for (ElevatorViewModel.ElevatorConfig config : configs) {
            String configBuildingId = config.getBuildingId();
            if (configBuildingId == null) configBuildingId = "default";

            boolean servesFloor = false;
            for (ElevatorViewModel.ElevatorFloor ef : config.getFloors()) {
                if (ef.getFloor() == floor) {
                    servesFloor = true;
                    break;
                }
            }

            if (servesFloor && configBuildingId.equals(buildingId)) {
                String configId = config.getId();
                boolean isAvailable = isElevatorAvailable(configId);
                availability.put(configId, isAvailable);

                debugger.log("getElevatorAvailabilityFromLocal: elevator " + configId +
                        " serves floor " + floor + ", available=" + isAvailable);
            }
        }

        debugger.log("🔍 [GET_ELEVATOR_AVAILABILITY_LOCAL] Returning " + availability.size() + " elevators");
        return availability;
    }

    /**
     * Helper method to get cloud IP from BasicViewModel
     */
    private String getCloudIp() {
        try {
            if (basicViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    basicViewModel = MyApplication.getInstance().getBasicViewModel();
                }
                if (basicViewModel == null) {
                    return null;
                }
            }
            return basicViewModel.getCloudIp().getValue();
        } catch (Exception e) {
            debugger.logWarning("getCloudIp", "Error getting cloud IP: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper method to get cloud port from BasicViewModel
     */
    private Integer getCloudPort() {
        try {
            if (basicViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    basicViewModel = MyApplication.getInstance().getBasicViewModel();
                }
                if (basicViewModel == null) {
                    return null;
                }
            }
            return basicViewModel.getCloudPort().getValue();
        } catch (Exception e) {
            debugger.logWarning("getCloudPort", "Error getting cloud port: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper method to get robot ID
     */
    private int getRobotId() {
        try {
            if (basicViewModel == null) {
                if (MyApplication.getInstance() != null) {
                    basicViewModel = MyApplication.getInstance().getBasicViewModel();
                }
                if (basicViewModel == null) {
                    return 1;
                }
            }
            Integer robotId = basicViewModel.getRobotId().getValue();
            return robotId != null ? robotId : 1;
        } catch (Exception e) {
            debugger.logWarning("getRobotId", "Error getting robot ID: " + e.getMessage());
            return 1;
        }
    }

    @Override
    public String findAvailableElevator(String buildingId, int floor) {
        Map<String, Boolean> availability = getElevatorAvailabilityForFloor(buildingId, floor);

        for (Map.Entry<String, Boolean> entry : availability.entrySet()) {
            if (entry.getValue()) {
                debugger.logInfo("findAvailableElevator: Found available elevator " + entry.getKey() +
                        " for building " + buildingId + ", floor " + floor);
                return entry.getKey();
            }
        }

        debugger.logWarning("findAvailableElevator: No available elevator for building " +
                buildingId + ", floor " + floor, "all elevators occupied");
        return null;
    }

    // ========== Inner Classes ==========

    /**
     * Represents the status of a single elevator
     */
    public static class ElevatorStatus {
        private final String configId;
        private int channel;
        private int address;
        private String buildingId;
        private volatile boolean occupied;
        private volatile String occupiedByRobotId;
        private volatile long lastUpdateTime;
        private volatile boolean lastQuerySuccess;

        public ElevatorStatus(String configId, int channel, int address, String buildingId,
                              boolean occupied, String occupiedByRobotId) {
            this.configId = configId;
            this.channel = channel;
            this.address = address;
            this.buildingId = buildingId != null ? buildingId : "default";
            this.occupied = occupied;
            this.occupiedByRobotId = occupiedByRobotId;
            this.lastUpdateTime = System.currentTimeMillis();
            this.lastQuerySuccess = true;
        }

        public String getConfigId() { return configId; }
        public int getChannel() { return channel; }
        public void setChannel(int channel) { this.channel = channel; }
        public int getAddress() { return address; }
        public void setAddress(int address) { this.address = address; }
        public String getBuildingId() { return buildingId; }
        public void setBuildingId(String buildingId) { this.buildingId = buildingId; }
        public boolean isOccupied() { return occupied; }
        public void setOccupied(boolean occupied) { this.occupied = occupied; }
        public String getOccupiedByRobotId() { return occupiedByRobotId; }
        public void setOccupiedByRobotId(String occupiedByRobotId) { this.occupiedByRobotId = occupiedByRobotId; }
        public long getLastUpdateTime() { return lastUpdateTime; }
        public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
        public boolean isLastQuerySuccess() { return lastQuerySuccess; }
        public void setLastQuerySuccess(boolean lastQuerySuccess) { this.lastQuerySuccess = lastQuerySuccess; }
    }

    /**
     * Result of a single elevator query
     */
    private static class QueryResult {
        final boolean isNotOccupied;
        final String robotId;
        final boolean success;

        QueryResult(boolean isNotOccupied, String robotId, boolean success) {
            this.isNotOccupied = isNotOccupied;
            this.robotId = robotId;
            this.success = success;
        }
    }
}
