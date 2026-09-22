package com.ezhan.amr.navigation.task;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.RobotCommunicationParams;
import com.ezhan.amr.navigation.LoraCommand;
import com.ezhan.amr.viewmodels.ElevatorViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandUtils {
    private static final String TAG = "CommandUtils";
    private static final long ELEVATOR_CALL_RETRY_DELAY_MS = 15000L;

    // Get debugger instance
    private static NavigationStateDebugger getDebugger() {
        return NavigationStateDebugger.getInstance();
    }

    private static final Pattern MULTI_BUILDING_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)_(\\d+)$");
    private static final Pattern LEGACY_TWO_PART_PATTERN = Pattern.compile("^([A-Za-z]+)_(\\d+)$");
    private static final Pattern LEGACY_ONE_PART_PATTERN = Pattern.compile("^([A-Za-z]+)");

    // Pattern to extract elevatorConfigId from point name
    // Format: elevator_wait_floorId_elevatorConfigId or elevator_ride_floorId_elevatorConfigId or elevator_transition_floorId_elevatorConfigId
    private static final Pattern ELEVATOR_POINT_PATTERN = Pattern.compile("^elevator_(?:wait|ride|transition|preride)_(\\d+)_(\\d+)$");


    public static String extractBuildingId(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "0";
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix + numericBuildingId + _ + floor
        // Example: "test13_11" -> prefix="test", buildingId="13", floor="11"
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            String buildingId = multiMatcher.group(2);
            getDebugger().log("extractBuildingId: Multi-building format, buildingId='" + buildingId +
                    "' from mapName='" + fullMapName + "'");
            return buildingId;
        }

        // Check legacy two-part format: prefix_floor
        // Example: "Test_12" -> returns "0"
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            getDebugger().log("extractBuildingId: Legacy two-part format, returning '0' from mapName='" + fullMapName + "'");
            return "0";
        }

        // Legacy one-part format or any other format
        getDebugger().log("extractBuildingId: Legacy format, returning '0' from mapName='" + fullMapName + "'");
        return "0";
    }

    /**
     * Check if two positions belong to the same building
     */
    private static boolean isSameBuilding(Position pos1, Position pos2) {
        if (pos1 == null || pos2 == null) return false;

        String building1 = extractBuildingId(pos1.getMapName());
        String building2 = extractBuildingId(pos2.getMapName());

        boolean same = building1.equals(building2);
        getDebugger().log("isSameBuilding: pos1 building=" + building1 + ", pos2 building=" + building2 + ", same=" + same);
        return same;
    }

    /**
     * Remove file extension from map name
     */
    private static String removeFileExtension(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return "";
        }
        int dotIndex = mapName.lastIndexOf('.');
        if (dotIndex > 0) {
            return mapName.substring(0, dotIndex);
        }
        return mapName;
    }

    /**
     * Find transition point for a specific building (any floor)
     * Used for building-to-building transitions where transition points are on the same connecting floor
     */
    private static Position findTransitionPointForBuildingAndFloor(
            List<Position> transitionPoints, String buildingId, String floor) {
        if (transitionPoints == null) return null;

        for (Position point : transitionPoints) {
            String pointBuilding = extractBuildingId(point.getMapName());
            if (pointBuilding != null && pointBuilding.equals(buildingId)) {
                if (floor == null) {
                    // Return any transition point in this building
                    getDebugger().log("findTransitionPointForBuildingAndFloor: Found transition point for building=" + buildingId +
                            ", floor=" + point.getFloor() + ", name=" + point.getName());
                    return point;
                } else if (point.getFloor() != null && point.getFloor().equals(floor)) {
                    getDebugger().log("findTransitionPointForBuildingAndFloor: Found transition point for building=" + buildingId +
                            ", floor=" + floor + ", name=" + point.getName());
                    return point;
                }
            }
        }

        getDebugger().log("findTransitionPointForBuildingAndFloor: No transition point found for building=" + buildingId +
                (floor != null ? ", floor=" + floor : ""));
        return null;
    }

    /**
     * Extract elevator config ID from a position point
     * For elevator points: parses from point name (e.g., "elevator_wait_2_1")
     * For regular points: extracts buildingId from mapName, then finds the elevator config for that building
     *
     * @param point The position point
     * @param elevatorConfigs List of all elevator configurations
     * @return The elevator config ID, or null if not found
     */
    private static String extractElevatorConfigIdFromPoint(Position point, List<ElevatorViewModel.ElevatorConfig> elevatorConfigs) {
        if (point == null || elevatorConfigs == null) return null;

        String pointName = point.getName();

        // First, try to extract from point name if it's an elevator point
        if (pointName != null && pointName.startsWith("elevator_")) {
            Matcher matcher = ELEVATOR_POINT_PATTERN.matcher(pointName);
            if (matcher.matches()) {
                String configId = matcher.group(2);
                getDebugger().log("extractElevatorConfigIdFromPoint: Extracted configId=" + configId +
                        " from elevator point name: " + pointName);
                return configId;
            }
        }

        // For regular points, extract buildingId from mapName and find matching elevator config
        String mapName = point.getMapName();
        if (mapName != null && !mapName.isEmpty()) {
            String buildingId = extractBuildingId(mapName);

            getDebugger().log("extractElevatorConfigIdFromPoint: Regular point - buildingId=" + buildingId +
                    ", mapName=" + mapName);

            if (buildingId != null) {
                // Find the first elevator config that serves this building
                for (ElevatorViewModel.ElevatorConfig config : elevatorConfigs) {
                    String configBuildingId = config.getBuildingId();
                    if (configBuildingId == null) configBuildingId = "default";

                    if (configBuildingId.equals(buildingId)) {
                        getDebugger().log("extractElevatorConfigIdFromPoint: Found config ID=" + config.getId() +
                                " for building=" + buildingId);
                        return config.getId();
                    }
                }
            }
        }

        getDebugger().log("extractElevatorConfigIdFromPoint: Could not extract configId for point: name=" + pointName +
                ", mapName=" + mapName);
        return null;
    }

    /**
     * Get elevator config from a position point
     * Works for both elevator points and regular points
     */
    private static ElevatorViewModel.ElevatorConfig getElevatorConfigFromPoint(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs, Position point) {
        if (point == null || elevatorConfigs == null) return null;

        // First, try to get from point's elevator info if available
        if (point.getElevatorInfo() != null && point.getElevatorInfo().getElevatorId() != null) {
            String configId = point.getElevatorInfo().getElevatorId();
            ElevatorViewModel.ElevatorConfig config = findElevatorConfigById(elevatorConfigs, configId);
            if (config != null) {
                getDebugger().log("getElevatorConfigFromPoint: Found via ElevatorInfo: ID=" + configId);
                return config;
            }
        }

        // Extract config ID using the building-based method
        String configId = extractElevatorConfigIdFromPoint(point, elevatorConfigs);
        if (configId != null) {
            ElevatorViewModel.ElevatorConfig config = findElevatorConfigById(elevatorConfigs, configId);
            if (config != null) {
                getDebugger().log("getElevatorConfigFromPoint: Found via building lookup: ID=" + configId +
                        ", channel=" + config.getChannel() + ", address=" + config.getAddress());
                return config;
            }
        }

        getDebugger().log("getElevatorConfigFromPoint: No config found for point: " + point.getName());
        return null;
    }

    /**
     * Find elevator config by ID from the list of configs
     */
    private static ElevatorViewModel.ElevatorConfig findElevatorConfigById(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs, String configId) {
        if (elevatorConfigs == null || configId == null) return null;
        for (ElevatorViewModel.ElevatorConfig config : elevatorConfigs) {
            if (configId.equals(config.getId())) {
                getDebugger().log("findElevatorConfigById: Found config with ID=" + configId +
                        ", channel=" + config.getChannel() + ", address=" + config.getAddress());
                return config;
            }
        }
        getDebugger().log("findElevatorConfigById: No config found with ID=" + configId);
        return null;
    }

    // 检查插点是否正确
    public static List<Position> getInterpolatedPath(
            Position currentPosition,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            List<Position> selectedPositions
    ) {
        getDebugger().log("========== getInterpolatedPath START ==========");
        getDebugger().log("currentPosition: name=" + (currentPosition != null ? currentPosition.getName() : "null") +
                ", floor=" + (currentPosition != null ? currentPosition.getFloor() : "null") +
                ", mapName=" + (currentPosition != null ? currentPosition.getMapName() : "null"));
        getDebugger().log("selectedPositions count: " + (selectedPositions != null ? selectedPositions.size() : 0));
        getDebugger().log("waitPoints count: " + (waitPoints != null ? waitPoints.size() : 0));
        getDebugger().log("ridePoints count: " + (ridePoints != null ? ridePoints.size() : 0));
        getDebugger().log("transitionPoints count: " + (transitionPoints != null ? transitionPoints.size() : 0));

        if (currentPosition == null || waitPoints == null || ridePoints == null
                || selectedPositions == null || selectedPositions.isEmpty()) {
            getDebugger().logWarning("getInterpolatedPath", "Required parameters are null or empty");
            return new ArrayList<>();
        }

        List<Position> interpolatedPath = new ArrayList<>();

        // Get building info for current position
        String currentBuilding = extractBuildingId(currentPosition.getMapName());
        String firstTargetBuilding = extractBuildingId(selectedPositions.get(0).getMapName());

        getDebugger().log("Current building: " + currentBuilding);
        getDebugger().log("First target building: " + firstTargetBuilding);

        // Handle transition from current position to first selected position
        if (!isSameBuilding(currentPosition, selectedPositions.get(0))) {
            // Different buildings - need to use transition points
            getDebugger().log("Different buildings detected: " + currentBuilding + " -> " + firstTargetBuilding);

            // First find target transition point to know the connecting floor
            Position targetTransitionPoint = findTransitionPointForBuildingAndFloor(
                    transitionPoints, firstTargetBuilding, null);

            String connectingFloor = null;
            if (targetTransitionPoint != null) {
                connectingFloor = targetTransitionPoint.getFloor();
                getDebugger().log("Target transition point found on floor: " + connectingFloor +
                        ", name: " + targetTransitionPoint.getName());
            } else {
                getDebugger().logWarning("getInterpolatedPath", "No transition point found for target building: " + firstTargetBuilding);
            }

            // Find transition point for current building on the connecting floor
            Position currentTransitionPoint = null;
            if (connectingFloor != null) {
                currentTransitionPoint = findTransitionPointForBuildingAndFloor(
                        transitionPoints, currentBuilding, connectingFloor);
            }

            // Fallback to any transition point if not found on connecting floor
            if (currentTransitionPoint == null) {
                currentTransitionPoint = findTransitionPointForBuildingAndFloor(
                        transitionPoints, currentBuilding, null);
                if (currentTransitionPoint != null) {
                    getDebugger().log("Using fallback transition point for current building: " +
                            currentTransitionPoint.getName() + " on floor " + currentTransitionPoint.getFloor());
                    connectingFloor = currentTransitionPoint.getFloor();
                }
            }

            if (currentTransitionPoint != null) {
                String currentFloorStr = currentPosition.getFloor();
                String transitionPointFloor = currentTransitionPoint.getFloor();
                String transitionBuilding = extractBuildingId(currentTransitionPoint.getMapName());

                // Add elevator sequence if current floor is different from transition floor
                if (!currentFloorStr.equals(transitionPointFloor)) {
                    getDebugger().log("Need to move from current floor " + currentFloorStr +
                            " to transition floor " + transitionPointFloor + " in building " + currentBuilding);
                    interpolatedPath.addAll(addElevatorSequence(
                            currentFloorStr, transitionPointFloor, transitionBuilding, waitPoints, ridePoints,
                            getElevatorId(currentPosition)));
                } else {
                    getDebugger().log("Already on transition floor " + transitionPointFloor +
                            ", no elevator sequence needed in current building");
                }

                getDebugger().log("Adding current transition point: " + currentTransitionPoint.getName() +
                        " at floor " + currentTransitionPoint.getFloor());
                interpolatedPath.add(currentTransitionPoint);
            } else {
                getDebugger().logWarning("getInterpolatedPath", "No transition point found for current building: " + currentBuilding);
            }

            // Add target transition point AFTER current building's transition point
            if (targetTransitionPoint != null) {
                // ✅ FIX: Check if target transition point is at the same location as current transition point
                boolean isSameLocation = false;
                if (currentTransitionPoint != null) {
                    double distance = Math.sqrt(
                            Math.pow(currentTransitionPoint.getPosX() - targetTransitionPoint.getPosX(), 2) +
                                    Math.pow(currentTransitionPoint.getPosY() - targetTransitionPoint.getPosY(), 2)
                    );
                    isSameLocation = distance < 0.1; // 10cm threshold
                }

                if (isSameLocation) {
                    getDebugger().log("Target transition point is at same location as current transition point - skipping duplicate");
                    // Don't add target transition point separately, but mark that map change is needed
                    // The command generation will handle the map change
                } else {
                    getDebugger().log("Adding target transition point: " + targetTransitionPoint.getName() +
                            " at floor " + targetTransitionPoint.getFloor());
                    interpolatedPath.add(targetTransitionPoint);
                }

                // ⭐⭐⭐ CRITICAL FIX: Check if target transition point is on same floor as first selected position
                String targetTransitionFloor = targetTransitionPoint.getFloor();
                String firstTargetFloor = selectedPositions.get(0).getFloor();

                if (!targetTransitionFloor.equals(firstTargetFloor)) {
                    getDebugger().log("⚠️ Target transition point floor (" + targetTransitionFloor +
                            ") is different from first selected position floor (" + firstTargetFloor + ")");
                    getDebugger().log("Adding elevator sequence between transition point and first selected position");
                    interpolatedPath.addAll(addElevatorSequence(
                            targetTransitionFloor, firstTargetFloor, firstTargetBuilding, waitPoints, ridePoints));
                } else {
                    getDebugger().log("Target transition point is on same floor as first selected position");
                }
            }
        }
        // Same building, different floors
        else if (!Objects.equals(currentPosition.getFloor(), selectedPositions.get(0).getFloor())) {
            String currentFloorStr = currentPosition.getFloor();
            String targetFloorStr = selectedPositions.get(0).getFloor();

            getDebugger().log("========== SAME BUILDING, DIFFERENT FLOORS ==========");
            getDebugger().log("Current floor: " + currentFloorStr);
            getDebugger().log("Target floor: " + targetFloorStr);

            interpolatedPath.addAll(addElevatorSequence(
                    currentFloorStr, targetFloorStr, firstTargetBuilding, waitPoints, ridePoints,
                    getElevatorId(currentPosition)));
            getDebugger().log("========== END SAME BUILDING DIFFERENT FLOORS ==========");
        }
        else {
            getDebugger().log("Same building and same floor, no interpolation needed");
        }

        // Always add the first selected position
        interpolatedPath.add(selectedPositions.get(0));
        getDebugger().log("Added first selected position: " + selectedPositions.get(0).getName());

        // Process remaining positions
        for (int i = 1; i < selectedPositions.size(); i++) {
            Position prev = selectedPositions.get(i-1);
            Position current = selectedPositions.get(i);

            String prevBuilding = extractBuildingId(prev.getMapName());
            String currentBuildingId = extractBuildingId(current.getMapName());

            getDebugger().log("Processing pair " + i + ": " + prev.getName() + " (building=" + prevBuilding +
                    ", floor=" + prev.getFloor() + ") -> " + current.getName() +
                    " (building=" + currentBuildingId + ", floor=" + current.getFloor() + ")");

            if (!isSameBuilding(prev, current)) {
                // Different buildings - use transition points
                getDebugger().log("Different buildings detected between positions");

                // First find target transition point to know the connecting floor
                Position targetTransitionPoint = findTransitionPointForBuildingAndFloor(
                        transitionPoints, currentBuildingId, null);

                String connectingFloor = null;
                if (targetTransitionPoint != null) {
                    connectingFloor = targetTransitionPoint.getFloor();
                    getDebugger().log("Target transition point found on floor: " + connectingFloor +
                            ", name: " + targetTransitionPoint.getName());
                }

                // Find transition point for previous building on the connecting floor
                Position prevTransitionPoint = null;
                if (connectingFloor != null) {
                    prevTransitionPoint = findTransitionPointForBuildingAndFloor(
                            transitionPoints, prevBuilding, connectingFloor);
                }

                if (prevTransitionPoint == null) {
                    prevTransitionPoint = findTransitionPointForBuildingAndFloor(
                            transitionPoints, prevBuilding, null);
                    if (prevTransitionPoint != null) {
                        getDebugger().log("Using fallback transition point for previous building: " +
                                prevTransitionPoint.getName() + " on floor " + prevTransitionPoint.getFloor());
                        connectingFloor = prevTransitionPoint.getFloor();
                    }
                }

                if (prevTransitionPoint != null) {
                    String prevFloor = prev.getFloor();
                    String prevTransitionFloor = prevTransitionPoint.getFloor();
                    String prevTransitionBuilding = extractBuildingId(prevTransitionPoint.getMapName());

                    // Add elevator sequence for previous building if needed
                    if (!prevFloor.equals(prevTransitionFloor)) {
                        getDebugger().log("Need to move from floor " + prevFloor +
                                " to transition floor " + prevTransitionFloor + " in building " + prevBuilding);
                        interpolatedPath.addAll(addElevatorSequence(
                                prevFloor, prevTransitionFloor, prevTransitionBuilding, waitPoints, ridePoints));
                    } else {
                        getDebugger().log("Already on transition floor " + prevTransitionFloor +
                                ", no elevator sequence needed");
                    }

                    getDebugger().log("Adding previous transition point: " + prevTransitionPoint.getName());
                    interpolatedPath.add(prevTransitionPoint);
                }

                // Add target transition point AFTER previous building's transition point
                if (targetTransitionPoint != null) {
                    // ✅ FIX: Check if target transition point is at the same location as previous transition point
                    boolean isSameLocation = false;
                    if (prevTransitionPoint != null) {
                        double distance = Math.sqrt(
                                Math.pow(prevTransitionPoint.getPosX() - targetTransitionPoint.getPosX(), 2) +
                                        Math.pow(prevTransitionPoint.getPosY() - targetTransitionPoint.getPosY(), 2)
                        );
                        isSameLocation = distance < 0.1;
                    }

                    if (isSameLocation) {
                        getDebugger().log("Target transition point is at same location as previous transition point - skipping duplicate");
                    } else {
                        getDebugger().log("Adding target transition point: " + targetTransitionPoint.getName());
                        interpolatedPath.add(targetTransitionPoint);
                    }

                    // ⭐⭐⭐ CRITICAL FIX: Check if target transition point is on same floor as current selected position
                    String targetTransitionFloor = targetTransitionPoint.getFloor();
                    String currentTargetFloor = current.getFloor();
                    String currentTargetBuilding = extractBuildingId(current.getMapName());

                    if (!targetTransitionFloor.equals(currentTargetFloor)) {
                        getDebugger().log("⚠️ Target transition point floor (" + targetTransitionFloor +
                                ") is different from current selected position floor (" + currentTargetFloor + ")");
                        getDebugger().log("Adding elevator sequence between transition point and selected position");
                        interpolatedPath.addAll(addElevatorSequence(
                                targetTransitionFloor, currentTargetFloor, currentTargetBuilding, waitPoints, ridePoints));
                    } else {
                        getDebugger().log("Target transition point is on same floor as selected position");
                    }
                }
            }
            // Same building, different floors between selected positions
            else if (!Objects.equals(prev.getFloor(), current.getFloor())) {
                String prevFloorStr = prev.getFloor();
                String targetFloorStr = current.getFloor();
                String targetBuilding = extractBuildingId(current.getMapName());

                getDebugger().log("========== SAME BUILDING, DIFFERENT FLOORS (between positions) ==========");
                getDebugger().log("Previous floor: " + prevFloorStr);
                getDebugger().log("Target floor: " + targetFloorStr);

                interpolatedPath.addAll(addElevatorSequence(prevFloorStr, targetFloorStr, targetBuilding, waitPoints, ridePoints));
                getDebugger().log("========== END SAME BUILDING DIFFERENT FLOORS (between positions) ==========");
            }

            // Always add the current selected position
            interpolatedPath.add(current);
            getDebugger().log("Added selected position: " + current.getName());
        }

        getDebugger().log("getInterpolatedPath END - Total path size: " + interpolatedPath.size());
        getDebugger().log("===============================================");

        return interpolatedPath;
    }

    private static Position findPositionWithFloor(List<Position> positions, String floor, String buildingId) {
        return findPositionWithFloor(positions, floor, buildingId, null);
    }

    private static Position findPositionWithFloor(List<Position> positions, String floor, String buildingId,
                                                  String elevatorId) {
        if (positions == null || buildingId == null) return null;
        for (Position pos : positions) {
            if (pos.getFloor() != null && pos.getFloor().equals(floor)) {
                String posBuildingId = extractBuildingId(pos.getMapName());
                if (buildingId.equals(posBuildingId)) {
                    if (elevatorId == null || elevatorId.equals(getElevatorId(pos))) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static ElevatorViewModel getElevatorViewModel() {
        try {
            return MyApplication.getInstance().getElevatorViewModel();
        } catch (Exception e) {
            getDebugger().logError("getElevatorViewModel", "Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Occupancy-aware elevator position selection.
     * Uses ElevatorViewModel to pick an available elevator for cross-floor tasks.
     *
     * @param positions  The list of candidate positions
     * @param fromFloor  The current floor
     * @param toFloor    The target floor
     * @param buildingId The building ID
     * @param elevatorId Optional specific elevator ID to use (if null, will find any available)
     * @param isTo       If true, use toFloor for elevator selection; if false, use fromFloor
     * @return The best matching position, or null if none found
     */
    private static Position findPositionWithFloor(
            List<Position> positions,
            String fromFloor,
            String toFloor,
            String buildingId,
            String elevatorId,
            boolean isTo) {

        NavigationStateDebugger debugger = getDebugger();
        debugger.log(String.format("findPositionWithFloor: fromFloor=%s, toFloor=%s, buildingId=%s, elevatorId=%s, isTo=%b",
                fromFloor, toFloor, buildingId, elevatorId, isTo));

        if (positions == null || buildingId == null) {
            debugger.logWarning("findPositionWithFloor", "positions or buildingId is null");
            return null;
        }

        // Try to use ElevatorViewModel for occupancy-aware selection
        ElevatorViewModel elevatorViewModel = getElevatorViewModel();
        if (elevatorViewModel != null) {
            try {
                // Pass both fromFloor and toFloor with isTo flag
                Position result = elevatorViewModel.findPositionWithFloorAndElevatorSelection(
                        positions, fromFloor, toFloor, buildingId, elevatorId, isTo);
                if (result != null) {
                    debugger.log("findPositionWithFloor: Found position via ElevatorViewModel: " + result.getName());
                    return result;
                }
            } catch (Exception e) {
                debugger.logError("findPositionWithFloor",
                        "Error using ElevatorViewModel: " + e.getMessage());
                // Fall through to legacy
            }
        }

        // Fallback to legacy behavior (use toFloor for matching)
        debugger.logWarning("findPositionWithFloor", "Using legacy behavior");
        return findPositionWithFloorLegacy(positions, toFloor, buildingId, elevatorId);
    }

    /**
     * Legacy findPositionWithFloor for backward compatibility
     */
    private static Position findPositionWithFloorLegacy(
            List<Position> positions, String floor, String buildingId, String elevatorId) {
        if (positions == null || buildingId == null) return null;
        for (Position pos : positions) {
            if (pos.getFloor() != null && pos.getFloor().equals(floor)) {
                String posBuildingId = extractBuildingId(pos.getMapName());
                if (buildingId.equals(posBuildingId)) {
                    if (elevatorId == null || elevatorId.equals(getElevatorId(pos))) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 5-arg overload: selects the elevator for the target floor (isTo=true)
     */
    private static Position findPositionWithFloor(List<Position> positions, String fromFloor, String toFloor, String buildingId, boolean isTo) {
        return findPositionWithFloor(positions, fromFloor, toFloor, buildingId, null, isTo);
    }

    private static String getElevatorId(Position position) {
        if (position == null) return null;
        if (position.getElevatorInfo() != null &&
                position.getElevatorInfo().getElevatorId() != null) {
            return position.getElevatorInfo().getElevatorId();
        }

        String name = position.getName();
        if (name != null) {
            Matcher matcher = ELEVATOR_POINT_PATTERN.matcher(name);
            if (matcher.matches()) {
                return matcher.group(2);
            }
        }
        return null;
    }

    // 检查判断类型是否正确
    public static int getTransitionType(Position currentPosition, Position nextPosition) {
        if (currentPosition == null || nextPosition == null) {
            throw new IllegalArgumentException("站点不能为空");
        }

        int currentType = currentPosition.getType();
        int nextType = nextPosition.getType();

        boolean currentIsNonElevator = notElevatorPositionType(currentType);
        boolean nextIsNonElevator = notElevatorPositionType(nextType);

        // Check if positions are in different buildings
        boolean sameBuilding = isSameBuilding(currentPosition, nextPosition);

        getDebugger().log("getTransitionType: currentType=" + currentType +
                ", nextType=" + nextType +
                ", sameBuilding=" + sameBuilding +
                ", currentNonElevator=" + currentIsNonElevator +
                ", nextNonElevator=" + nextIsNonElevator);

        // Type 6: Different buildings
        if (!sameBuilding) {
            getDebugger().log("Transition Type: 6 (Different buildings)");
            return 6;
        }

        // Type 1: Current is non-elevator point, next is wait point (same floor)
        if ((currentIsNonElevator || currentType == 3) && nextType == 3) {
            getDebugger().log("Transition Type: 1 (Non-elevator -> Wait point)");
            return 1;
        }

        // A preride point is an intermediate fixed-track point, so returning to
        // the wait point uses the existing move-to-wait command sequence.
        if (currentType == 2 && nextType == 3 &&
                Objects.equals(currentPosition.getFloor(), nextPosition.getFloor())) {
            String currentElevatorId = getElevatorId(currentPosition);
            String nextElevatorId = getElevatorId(nextPosition);
            if (currentElevatorId != null && currentElevatorId.equals(nextElevatorId)) {
                getDebugger().log("Transition Type: 1 (Preride point -> Wait point)");
                return 1;
            }
        }

        // A preride point can also be the robot's physical start position for
        // same-floor tasks. In that case it should leave the elevator area with
        // a normal navigation command instead of being treated as an elevator
        // transition.
        if (currentType == 2 && nextIsNonElevator &&
                Objects.equals(currentPosition.getFloor(), nextPosition.getFloor())) {
            getDebugger().log("Transition Type: 0 (Preride point -> Non-elevator point, same floor)");
            return 0;
        }

        // Type 2: Current is wait point, next is ride point (same floor)
        if (currentType == 3 && nextType == 4) {
            getDebugger().log("Transition Type: 2 (Wait point -> Ride point)");
            return 2;
        }

        // Type 3: Current is ride point, next is ride point (different floors)
        if (currentType == 4 && nextType == 4) {
            getDebugger().log("Transition Type: 3 (Ride point -> Ride point, different floors)");
            return 3;
        }

        // Type 4: Current is ride point, next is wait point (same floor, exiting elevator)
        if (currentType == 4 && nextType == 3) {
            getDebugger().log("Transition Type: 4 (Ride point -> Wait point)");
            return 4;
        }

        // Type 5: Current is wait point, next is non-elevator point (same floor)
        if (currentType == 3 && nextIsNonElevator) {
            getDebugger().log("Transition Type: 5 (Wait point -> Non-elevator point)");
            return 5;
        }

        // Type 0: Both are non-elevator points on same floor
        if (currentIsNonElevator && nextIsNonElevator) {
            getDebugger().log("Transition Type: 0 (Non-elevator -> Non-elevator, same floor)");
            return 0;
        }

        // Invalid transition
        String errorMsg = "Invalid transition: currentType=" + currentType + ", nextType=" + nextType;
        getDebugger().logError("getTransitionType", errorMsg);
        throw new IllegalStateException("无效当前站点类型: "
                + currentType + ", 下个站点类型: " + nextType);
    }

    public static List<LoraCommand> getCommandSequence(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        int transitionType = getTransitionType(currentPosition, nextPosition);

        getDebugger().log("========== getCommandSequence ==========");
        getDebugger().log("Transition Type: " + transitionType);
        getDebugger().log("Current Position: " + currentPosition.getName() +
                " (type=" + currentPosition.getType() +
                ", floor=" + currentPosition.getFloor() +
                ", building=" + extractBuildingId(currentPosition.getMapName()) + ")");
        getDebugger().log("Next Position: " + nextPosition.getName() +
                " (type=" + nextPosition.getType() +
                ", floor=" + nextPosition.getFloor() +
                ", building=" + extractBuildingId(nextPosition.getMapName()) + ")");

        List<LoraCommand> commands = new ArrayList<>();

        // If no elevator configs or transition type doesn't require elevator commands
        if (elevatorConfigs == null || transitionType == 0 || transitionType == -1) {
            getDebugger().log("No elevator commands needed for transition type " + transitionType);
            commands.add(new LoraCommand.Builder(getNavigationType(nextPosition), nextPosition.getName())
                    .withPlayMusic(false)
                    .withTarget(nextPosition)
                    .build());
            return commands;
        }

        // Type 6: Different buildings - special handling
        if (transitionType == 6) {
            getDebugger().log("Building transition detected, using Type 6 handler");
            return buildCommandSequenceType6(elevatorConfigs, currentPosition, nextPosition,
                    preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
        }

        switch (transitionType) {
            case 1:
                getDebugger().log("Using Type 1 handler");
                return buildCommandSequenceType1(elevatorConfigs, currentPosition, nextPosition,
                        preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
            case 2:
                getDebugger().log("Using Type 2 handler");
                return buildCommandSequenceType2(elevatorConfigs, currentPosition, nextPosition,
                        preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
            case 3:
                getDebugger().log("Using Type 3 handler");
                return buildCommandSequenceType3(elevatorConfigs, currentPosition, nextPosition,
                        preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
            case 4:
                getDebugger().log("Using Type 4 handler");
                return buildCommandSequenceType4(elevatorConfigs, currentPosition, nextPosition,
                        preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
            case 5:
                getDebugger().log("Using Type 5 handler");
                return buildCommandSequenceType5(elevatorConfigs, currentPosition, nextPosition,
                        preridePoints, waitPoints, ridePoints, transitionPoints, robotCommParams);
            default:
                throw new IllegalArgumentException("Invalid transitionType: " + transitionType);
        }
    }

    // Type 6: Different buildings - navigate from current building to target building with full elevator handling
    private static List<LoraCommand> buildCommandSequenceType6(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

        getDebugger().log("========== buildCommandSequenceType6 START ==========");
        getDebugger().log("Current Position: " + currentPosition.getName() +
                " (type=" + currentPosition.getType() +
                ", floor=" + currentPosition.getFloor() +
                ", building=" + extractBuildingId(currentPosition.getMapName()) + ")");
        getDebugger().log("Next Position: " + nextPosition.getName() +
                " (type=" + nextPosition.getType() +
                ", floor=" + nextPosition.getFloor() +
                ", building=" + extractBuildingId(nextPosition.getMapName()) + ")");

        // First, try to get elevator config from the current position
        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, currentPosition);
        getDebugger().log("Elevator config from current position: " + (elevatorConfig != null ?
                "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                        ", address=" + elevatorConfig.getAddress() : "null"));

        // If not found, try from the next position
        if (elevatorConfig == null) {
            elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
            getDebugger().log("Elevator config from next position: " + (elevatorConfig != null ?
                    "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                            ", address=" + elevatorConfig.getAddress() : "null"));
        }

        // If still not found, use fallback to first available config
        if (elevatorConfig == null && elevatorConfigs != null && !elevatorConfigs.isEmpty()) {
            elevatorConfig = elevatorConfigs.get(0);
            getDebugger().logWarning("buildCommandSequenceType6", "Using fallback config with ID=" + elevatorConfig.getId() +
                    ", channel=" + elevatorConfig.getChannel() + ", address=" + elevatorConfig.getAddress());
        }

        if (elevatorConfig == null) {
            getDebugger().logError("buildCommandSequenceType6", "No elevator config found!");
            return commands;
        }

        int channel = elevatorConfig.getChannel();
        int address = elevatorConfig.getAddress();
        getDebugger().log("Using elevator config: channel=" + channel + ", address=" + address);

        String currentBuilding = extractBuildingId(currentPosition.getMapName());
        String targetBuilding = extractBuildingId(nextPosition.getMapName());

        getDebugger().log("Current Building: " + currentBuilding + ", Floor: " + currentPosition.getFloor());
        getDebugger().log("Target Building: " + targetBuilding + ", Floor: " + nextPosition.getFloor());

        // Find target transition point first to know the connecting floor
        Position targetTransitionPoint = findTransitionPointForBuildingAndFloor(
                transitionPoints, targetBuilding, null);

        if (targetTransitionPoint == null) {
            getDebugger().logError("buildCommandSequenceType6", "No transition point found for target building: " + targetBuilding);
            return commands;
        }

        String connectingFloor = targetTransitionPoint.getFloor();
        getDebugger().log("Connecting floor (from target building): " + connectingFloor);

        // Find current building's transition point on the same connecting floor
        Position currentTransitionPoint = findTransitionPointForBuildingAndFloor(
                transitionPoints, currentBuilding, connectingFloor);

        // Fallback to any transition point if not found on connecting floor
        if (currentTransitionPoint == null) {
            currentTransitionPoint = findTransitionPointForBuildingAndFloor(
                    transitionPoints, currentBuilding, null);
            if (currentTransitionPoint != null) {
                getDebugger().log("Using fallback transition point for current building: " +
                        currentTransitionPoint.getName() + " on floor " + currentTransitionPoint.getFloor());
            }
        }

        if (currentTransitionPoint == null) {
            getDebugger().logError("buildCommandSequenceType6", "No transition point found for current building: " + currentBuilding);
            return commands;
        }

        int transitionFloor = Integer.parseInt(currentTransitionPoint.getFloor());
        String transitionBuilding = extractBuildingId(currentPosition.getMapName());
        getDebugger().log("Current transition floor: " + transitionFloor);

        // ========== Phase 1: Navigate from current position to current building's transition point ==========
        int currentFloorNum = Integer.parseInt(currentPosition.getFloor());
        getDebugger().log("Phase 1: currentFloorNum=" + currentFloorNum + ", transitionFloor=" + transitionFloor);

        if (currentFloorNum != transitionFloor) {
            getDebugger().log("Phase 1: Need to move from floor " + currentFloorNum + " to transition floor " + transitionFloor);

            Position currentFloorPreridePoint = findPositionWithFloor(preridePoints, currentPosition.getFloor(), String.valueOf(transitionFloor), currentBuilding, false);
            Position currentFloorWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), String.valueOf(transitionFloor), currentBuilding, false);
            Position currentFloorRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), String.valueOf(transitionFloor), currentBuilding, false);
            Position transitionFloorPreridePoint = findPositionWithFloor(preridePoints, currentPosition.getFloor(), String.valueOf(transitionFloor), transitionBuilding, true);
            Position transitionFloorWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), String.valueOf(transitionFloor), transitionBuilding, true);
            Position transitionFloorRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), String.valueOf(transitionFloor),transitionBuilding, true);

            getDebugger().log("Phase 1 - currentFloorPreridePoint: " + (currentFloorPreridePoint != null ? currentFloorPreridePoint.getName() : "null"));
            getDebugger().log("Phase 1 - currentFloorWaitPoint: " + (currentFloorWaitPoint != null ? currentFloorWaitPoint.getName() : "null"));
            getDebugger().log("Phase 1 - currentFloorRidePoint: " + (currentFloorRidePoint != null ? currentFloorRidePoint.getName() : "null"));
            getDebugger().log("Phase 1 - transitionFloorPreridePoint: " + (transitionFloorPreridePoint != null ? transitionFloorPreridePoint.getName() : "null"));
            getDebugger().log("Phase 1 - transitionFloorWaitPoint: " + (transitionFloorWaitPoint != null ? transitionFloorWaitPoint.getName() : "null"));
            getDebugger().log("Phase 1 - transitionFloorRidePoint: " + (transitionFloorRidePoint != null ? transitionFloorRidePoint.getName() : "null"));

            // Move to wait point on current floor
            if (currentFloorWaitPoint != null) {
                LoraCommand cmd = new LoraCommand.Builder(getNavigationType(currentFloorWaitPoint),
                        currentFloorWaitPoint.getName())
                        .withDescription("Move to wait point on current floor " + currentFloorNum)
                        .withPlayMusic(false)
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd.getDescription());
            }

            // Enter elevator on current floor
            if (currentFloorRidePoint != null) {
                ElevatorViewModel.ElevatorConfig ridePointConfig = getElevatorConfigFromPoint(elevatorConfigs, currentFloorRidePoint);
                int rideChannel = ridePointConfig != null ? ridePointConfig.getChannel() : channel;
                int rideAddress = ridePointConfig != null ? ridePointConfig.getAddress() : address;
                getDebugger().log("Phase 1 - ride point config: channel=" + rideChannel + ", address=" + rideAddress);

                // Add QUERY_ACCESS command - use rideChannel/rideAddress for consistency
                LoraCommand cmd1 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_QUERY_ACCESS, currentFloorWaitPoint.getName())
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Query access for floor " + nextPosition.getFloor())
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd1);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd1.getDescription());

                // Add CLAIM_ACCESS command - use rideChannel/rideAddress for consistency
                LoraCommand cmd2 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CLAIM_ACCESS, currentFloorWaitPoint.getName())
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withDescription("Claim access for floor " + nextPosition.getFloor())
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd2);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd2.getDescription());

                LoraCommand cmd3 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, currentFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(currentFloorNum))
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                        .withDescription("Call elevator to floor " + currentFloorNum)
                        .withPlayMusic(false)
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd3);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd3.getDescription());

                LoraCommand cmd4a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, currentFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(currentFloorNum))
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("01")
                        .withRetries(60, 2000)
                        .withDescription("Check elevator arrival and door at floor " + currentFloorNum)
                        .withPlayMusic(false)
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd4a);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd4a.getDescription());

//                LoraCommand cmd4b = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, currentFloorRidePoint.getName())
//                        .withHexParam("floor", formatFloorHex(currentFloorNum))
//                        .withHexParam("channel", String.format("%02X", rideChannel))
//                        .withHexParam("address", String.format("%02X", rideAddress))
//                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                        .withExpectedResponse("01")
//                        .withRetries(60, 2000)
//                        .withDescription("Check elevator door at floor " + currentFloorNum)
//                        .withPlayMusic(false)
//                        .withTarget(currentFloorWaitPoint)
//                        .build();
//                commands.add(cmd4b);
//                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd4b.getDescription());

                LoraCommand cmd5 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, currentFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(currentFloorNum))
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Open elevator door")
                        .withPlayMusic(false)
                        .withTarget(currentFloorWaitPoint)
                        .build();
                commands.add(cmd5);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd5.getDescription());

                LoraCommand cmd6 = new LoraCommand.Builder(NavigationOrderType.OP_IN_ELEVATOR_MOVE, currentFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(currentFloorNum))
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withDescription("Move inside elevator")
                        .withPlayMusic(false)
                        .withTarget(currentFloorRidePoint)
                        .withPreridePoint(currentFloorPreridePoint)
                        .withRidePoint(currentFloorRidePoint)
                        .build();
                commands.add(cmd6);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd6.getDescription());

                LoraCommand cmd7 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, currentFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(currentFloorNum))
                        .withHexParam("channel", String.format("%02X", rideChannel))
                        .withHexParam("address", String.format("%02X", rideAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Cancel door open before moving to transition floor")
                        .withPlayMusic(false)
                        .withTarget(currentFloorRidePoint)
                        .build();
                commands.add(cmd7);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd7.getDescription());
            }

            // Change map to transition floor
            if (transitionFloorRidePoint != null) {
                ElevatorViewModel.ElevatorConfig transitionConfig = getElevatorConfigFromPoint(elevatorConfigs, transitionFloorRidePoint);
                int transitionChannel = transitionConfig != null ? transitionConfig.getChannel() : channel;
                int transitionAddress = transitionConfig != null ? transitionConfig.getAddress() : address;
                getDebugger().log("Phase 1 - transition config: channel=" + transitionChannel + ", address=" + transitionAddress);

                // 先呼叫电梯，让电梯提前过来（优化：呼叫和切图可并行进行）
                LoraCommand cmdCall = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, transitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(transitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                        .withDescription("Call elevator to transition floor " + transitionFloor)
                        .withPlayMusic(false)
                        .withTarget(transitionFloorRidePoint)
                        .build();
                commands.add(cmdCall);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmdCall.getDescription());

                // 然后切图
                LoraCommand cmdMap = new LoraCommand.Builder(NavigationOrderType.OP_CHANGE_MAP, transitionFloorRidePoint.getName())
                        .withHexParam("x", transitionFloorRidePoint.getPosX())
                        .withHexParam("y", transitionFloorRidePoint.getPosY())
                        .withHexParam("yaw", transitionFloorRidePoint.getYaw())
                        .withHexParam("mapName", transitionFloorRidePoint.getMapName())
                        .withExpectedResponse("0")
                        .withRetries(100, 2000)
                        .withDescription("Change map to transition floor " + transitionFloor)
                        .withPlayMusic(false)
                        .withTarget(transitionFloorRidePoint)
                        .build();
                commands.add(cmdMap);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmdMap.getDescription());

                // 检查电梯是否到达
                LoraCommand cmdCheck = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, transitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(transitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("01")
                        .withRetries(50, 2000)
                        .withDescription("Check elevator arrival and door at transition floor " + transitionFloor)
                        .withPlayMusic(false)
                        .withTarget(transitionFloorRidePoint)
                        .build();
                commands.add(cmdCheck);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmdCheck.getDescription());

//                LoraCommand cmdDoorCheck = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, transitionFloorRidePoint.getName())
//                        .withHexParam("floor", formatFloorHex(transitionFloor))
//                        .withHexParam("channel", String.format("%02X", transitionChannel))
//                        .withHexParam("address", String.format("%02X", transitionAddress))
//                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                        .withExpectedResponse("01")
//                        .withRetries(50, 2000)
//                        .withDescription("Check elevator door at transition floor " + transitionFloor)
//                        .withPlayMusic(false)
//                        .withTarget(transitionFloorRidePoint)
//                        .build();
//                commands.add(cmdDoorCheck);
//                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmdDoorCheck.getDescription());

                // 开门
                LoraCommand cmdOpen = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, transitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(transitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withExpectedResponse("00")
                        .withDescription("Open elevator door at transition floor " + transitionFloor)
                        .withPlayMusic(false)
                        .withTarget(transitionFloorRidePoint)
                        .build();
                commands.add(cmdOpen);
                getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmdOpen.getDescription());

                if (transitionFloorWaitPoint != null) {
                    LoraCommand cmd5 = new LoraCommand.Builder(NavigationOrderType.OP_OUT_ELEVATOR_MOVE, transitionFloorWaitPoint.getName())
                            .withHexParam("floor", formatFloorHex(transitionFloor))
                            .withHexParam("channel", String.format("%02X", transitionChannel))
                            .withHexParam("address", String.format("%02X", transitionAddress))
                            .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                            .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                            .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                            .withExpectedResponse("00")
                            .withDescription("Move out of elevator to wait point on transition floor")
                            .withTarget(transitionFloorWaitPoint)
                            .withPreridePoint(transitionFloorPreridePoint)
                            .withRidePoint(transitionFloorRidePoint)
                            .withPlayMusic(false)
                            .build();
                    commands.add(cmd5);
                    getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd5.getDescription());

                    LoraCommand cmd6 = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, transitionFloorWaitPoint.getName())
                            .withHexParam("floor", formatFloorHex(transitionFloor))
                            .withHexParam("channel", String.format("%02X", transitionChannel))
                            .withHexParam("address", String.format("%02X", transitionAddress))
                            .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                            .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                            .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                            .withExpectedResponse("00")
                            .withExpectedResponse("00")
                            .withDescription("Cancel door open after exiting elevator")
                            .withPlayMusic(false)
                            .withTarget(transitionFloorWaitPoint)
                            .build();
                    commands.add(cmd6);
                    getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd6.getDescription());
                }
            }

            // Move to current building's transition point
            LoraCommand cmd = new LoraCommand.Builder(getNavigationType(currentTransitionPoint),
                    currentTransitionPoint.getName())
                    .withDescription("Move to building transition point")
                    .withPlayMusic(false)
                    .withTarget(currentTransitionPoint)
                    .build();
            commands.add(cmd);
            getDebugger().log("Phase 1 - Added command " + commands.size() + ": " + cmd.getDescription());

        } else {
            getDebugger().log("Phase 1: Already on transition floor " + transitionFloor);

            // ✅ CHECK: Don't add move command if already at the transition point
            if (!isAtSamePosition(currentPosition, currentTransitionPoint)) {
                LoraCommand cmd = new LoraCommand.Builder(getNavigationType(currentTransitionPoint),
                        currentTransitionPoint.getName())
                        .withDescription("Move to building transition point (already on correct floor)")
                        .withPlayMusic(false)
                        .withTarget(currentTransitionPoint)
                        .build();
                commands.add(cmd);
                getDebugger().log("Phase 1 - Added move command to transition point");
            } else {
                getDebugger().log("Phase 1 - Skipping move command: already at transition point " + currentTransitionPoint.getName());
            }
        }

        // ========== Phase 2: Change map to target building ==========
        getDebugger().log("Phase 2: Changing map from " + currentBuilding + " to " + targetBuilding);
        getDebugger().log("Phase 2 - targetTransitionPoint: " + targetTransitionPoint.getName() +
                ", floor=" + targetTransitionPoint.getFloor() +
                ", mapName=" + targetTransitionPoint.getMapName());

        LoraCommand cmd1 = new LoraCommand.Builder(NavigationOrderType.OP_CHANGE_MAP, targetTransitionPoint.getName())
                .withHexParam("x", targetTransitionPoint.getPosX())
                .withHexParam("y", targetTransitionPoint.getPosY())
                .withHexParam("yaw", targetTransitionPoint.getYaw())
                .withHexParam("mapName", targetTransitionPoint.getMapName())
                .withExpectedResponse("0")
                .withRetries(100, 2000)
                .withDescription("Change map to target building " + targetBuilding + " at transition point")
                .withPlayMusic(false)
                .withTarget(targetTransitionPoint)
                .build();
        commands.add(cmd1);
        getDebugger().log("Phase 2 - Added command " + commands.size() + ": " + cmd1.getDescription());

        // ✅ FIX: Only add move command if not already at the target transition point
        if (!isAtSamePosition(currentTransitionPoint, targetTransitionPoint)) {
            LoraCommand cmd2 = new LoraCommand.Builder(getNavigationType(targetTransitionPoint),
                    targetTransitionPoint.getName())
                    .withDescription("Move to target building transition point")
                    .withPlayMusic(false)
                    .withTarget(targetTransitionPoint)
                    .build();
            commands.add(cmd2);
            getDebugger().log("Phase 2 - Added command " + commands.size() + ": " + cmd2.getDescription());
        } else {
            getDebugger().log("Phase 2 - Skipping move command: target transition point is at same location as current transition point");
        }

        // ========== Phase 3: Navigate from target building's transition point to target position ==========
        int targetFloorNum = Integer.parseInt(nextPosition.getFloor());
        String targetNextBuilding = extractBuildingId(nextPosition.getMapName());
        int targetTransitionFloor = Integer.parseInt(targetTransitionPoint.getFloor());
        String targetTransitionBuilding = extractBuildingId(targetTransitionPoint.getMapName());

        getDebugger().log("Phase 3: targetFloorNum=" + targetFloorNum + ", targetTransitionFloor=" + targetTransitionFloor);

        if (targetFloorNum != targetTransitionFloor) {
            getDebugger().log("Phase 3: Need to move from transition floor " + targetTransitionFloor +
                    " to target floor " + targetFloorNum);

            Position targetTransitionFloorPreridePoint = findPositionWithFloor(preridePoints,
                    String.valueOf(targetTransitionFloor), nextPosition.getFloor(), targetTransitionBuilding, false);
            Position targetTransitionFloorWaitPoint = findPositionWithFloor(waitPoints,
                    String.valueOf(targetTransitionFloor), nextPosition.getFloor(), targetTransitionBuilding, false);
            Position targetTransitionFloorRidePoint = findPositionWithFloor(ridePoints,
                    String.valueOf(targetTransitionFloor), nextPosition.getFloor(), targetTransitionBuilding, false);
            Position targetFloorPreridePoint = findPositionWithFloor(preridePoints, String.valueOf(targetTransitionFloor), nextPosition.getFloor(), targetNextBuilding, true);
            Position targetFloorWaitPoint = findPositionWithFloor(waitPoints, String.valueOf(targetTransitionFloor), nextPosition.getFloor(), targetNextBuilding, true);
            Position targetFloorRidePoint = findPositionWithFloor(ridePoints, String.valueOf(targetTransitionFloor), nextPosition.getFloor(),targetNextBuilding, true);

            getDebugger().log("Phase 3 - targetTransitionFloorPreridePoint: " + (targetTransitionFloorPreridePoint != null ? targetTransitionFloorPreridePoint.getName() : "null"));
            getDebugger().log("Phase 3 - targetTransitionFloorWaitPoint: " + (targetTransitionFloorWaitPoint != null ? targetTransitionFloorWaitPoint.getName() : "null"));
            getDebugger().log("Phase 3 - targetTransitionFloorRidePoint: " + (targetTransitionFloorRidePoint != null ? targetTransitionFloorRidePoint.getName() : "null"));
            getDebugger().log("Phase 3 - targetFloorPreridePoint: " + (targetFloorPreridePoint != null ? targetFloorPreridePoint.getName() : "null"));
            getDebugger().log("Phase 3 - targetFloorWaitPoint: " + (targetFloorWaitPoint != null ? targetFloorWaitPoint.getName() : "null"));
            getDebugger().log("Phase 3 - targetFloorRidePoint: " + (targetFloorRidePoint != null ? targetFloorRidePoint.getName() : "null"));

            if (targetTransitionFloorWaitPoint != null && targetTransitionFloorWaitPoint != targetTransitionPoint) {
                LoraCommand cmd = new LoraCommand.Builder(getNavigationType(targetTransitionFloorWaitPoint),
                        targetTransitionFloorWaitPoint.getName())
                        .withDescription("Move to wait point on transition floor " + targetTransitionFloor)
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd.getDescription());
            }

            if (targetTransitionFloorRidePoint != null) {
                ElevatorViewModel.ElevatorConfig transitionConfig = getElevatorConfigFromPoint(elevatorConfigs, targetTransitionFloorRidePoint);
                int transitionChannel = transitionConfig != null ? transitionConfig.getChannel() : channel;
                int transitionAddress = transitionConfig != null ? transitionConfig.getAddress() : address;
                getDebugger().log("Phase 3 - transition config: channel=" + transitionChannel + ", address=" + transitionAddress);

                // Add QUERY_ACCESS command - use transitionChannel/transitionAddress for consistency
                LoraCommand cmd1a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_QUERY_ACCESS, targetTransitionFloorWaitPoint.getName())
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Query access for floor " + nextPosition.getFloor())
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd1a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd1a.getDescription());

                // Add CLAIM_ACCESS command - use transitionChannel/transitionAddress for consistency
                LoraCommand cmd2a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CLAIM_ACCESS, targetTransitionFloorWaitPoint.getName())
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withDescription("Claim access for floor " + nextPosition.getFloor())
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd2a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd2a.getDescription());

                LoraCommand cmd3a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, targetTransitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetTransitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                        .withDescription("Call elevator to transition floor " + targetTransitionFloor)
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd3a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd3a.getDescription());

                LoraCommand cmd4aa = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, targetTransitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetTransitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("01")
                        .withRetries(50, 2000)
                        .withDescription("Check elevator arrival and door at transition floor " + targetTransitionFloor)
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd4aa);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd4aa.getDescription());

//                LoraCommand cmd4ab = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, targetTransitionFloorRidePoint.getName())
//                        .withHexParam("floor", formatFloorHex(targetTransitionFloor))
//                        .withHexParam("channel", String.format("%02X", transitionChannel))
//                        .withHexParam("address", String.format("%02X", transitionAddress))
//                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                        .withExpectedResponse("01")
//                        .withRetries(50, 2000)
//                        .withDescription("Check elevator door at transition floor " + targetTransitionFloor)
//                        .withPlayMusic(false)
//                        .withTarget(targetTransitionFloorWaitPoint)
//                        .build();
//                commands.add(cmd4ab);
//                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd4ab.getDescription());

                LoraCommand cmd5a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, targetTransitionFloorRidePoint.getName())
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Open elevator door at transition floor")
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorWaitPoint)
                        .build();
                commands.add(cmd5a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd5a.getDescription());

                LoraCommand cmd6a = new LoraCommand.Builder(NavigationOrderType.OP_IN_ELEVATOR_MOVE, targetTransitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetTransitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withDescription("Move inside elevator")
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorRidePoint)
                        .withPreridePoint(targetTransitionFloorPreridePoint)
                        .withRidePoint(targetTransitionFloorRidePoint)
                        .build();
                commands.add(cmd6a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd6a.getDescription());

                LoraCommand cmd7a = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, targetTransitionFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetTransitionFloor))
                        .withHexParam("channel", String.format("%02X", transitionChannel))
                        .withHexParam("address", String.format("%02X", transitionAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Cancel door open before moving to target floor")
                        .withPlayMusic(false)
                        .withTarget(targetTransitionFloorRidePoint)
                        .build();
                commands.add(cmd7a);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd7a.getDescription());
            }

            if (targetFloorRidePoint != null) {
                ElevatorViewModel.ElevatorConfig targetConfig = getElevatorConfigFromPoint(elevatorConfigs, targetFloorRidePoint);
                int targetChannel = targetConfig != null ? targetConfig.getChannel() : channel;
                int targetAddress = targetConfig != null ? targetConfig.getAddress() : address;
                getDebugger().log("Phase 3 - target config: channel=" + targetChannel + ", address=" + targetAddress);

                // 先呼叫电梯，让电梯提前过来（优化：呼叫和切图可并行进行）
                LoraCommand cmdCallTarget = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, targetFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetFloorNum))
                        .withHexParam("channel", String.format("%02X", targetChannel))
                        .withHexParam("address", String.format("%02X", targetAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                        .withDescription("Call elevator to target floor " + targetFloorNum)
                        .withPlayMusic(false)
                        .withTarget(targetFloorRidePoint)
                        .build();
                commands.add(cmdCallTarget);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmdCallTarget.getDescription());

                // 然后切图
                LoraCommand cmdMapTarget = new LoraCommand.Builder(NavigationOrderType.OP_CHANGE_MAP, targetFloorRidePoint.getName())
                        .withHexParam("x", targetFloorRidePoint.getPosX())
                        .withHexParam("y", targetFloorRidePoint.getPosY())
                        .withHexParam("yaw", targetFloorRidePoint.getYaw())
                        .withHexParam("mapName", targetFloorRidePoint.getMapName())
                        .withExpectedResponse("0")
                        .withRetries(100, 2000)
                        .withDescription("Change map to target floor " + targetFloorNum)
                        .withPlayMusic(false)
                        .withTarget(targetFloorRidePoint)
                        .build();
                commands.add(cmdMapTarget);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmdMapTarget.getDescription());

                // 检查电梯是否到达
                LoraCommand cmdCheckTarget = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, targetFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetFloorNum))
                        .withHexParam("channel", String.format("%02X", targetChannel))
                        .withHexParam("address", String.format("%02X", targetAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("01")
                        .withRetries(50, 2000)
                        .withDescription("Check elevator arrival and door at target floor " + targetFloorNum)
                        .withPlayMusic(false)
                        .withTarget(targetFloorRidePoint)
                        .build();
                commands.add(cmdCheckTarget);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmdCheckTarget.getDescription());

//                LoraCommand cmdDoorCheckTarget = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, targetFloorRidePoint.getName())
//                        .withHexParam("floor", formatFloorHex(targetFloorNum))
//                        .withHexParam("channel", String.format("%02X", targetChannel))
//                        .withHexParam("address", String.format("%02X", targetAddress))
//                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                        .withExpectedResponse("01")
//                        .withRetries(50, 2000)
//                        .withDescription("Check elevator door at target floor " + targetFloorNum)
//                        .withPlayMusic(false)
//                        .withTarget(targetFloorRidePoint)
//                        .build();
//                commands.add(cmdDoorCheckTarget);
//                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmdDoorCheckTarget.getDescription());

                // 开门
                LoraCommand cmdOpenTarget = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, targetFloorRidePoint.getName())
                        .withHexParam("floor", formatFloorHex(targetFloorNum))
                        .withHexParam("channel", String.format("%02X", targetChannel))
                        .withHexParam("address", String.format("%02X", targetAddress))
                        .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                        .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                        .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                        .withExpectedResponse("00")
                        .withDescription("Open elevator door at target floor " + targetFloorNum)
                        .withPlayMusic(false)
                        .withTarget(targetFloorRidePoint)
                        .build();
                commands.add(cmdOpenTarget);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmdOpenTarget.getDescription());

                if (targetFloorWaitPoint != null) {
                    LoraCommand cmd5b = new LoraCommand.Builder(NavigationOrderType.OP_OUT_ELEVATOR_MOVE, targetFloorWaitPoint.getName())
                            .withHexParam("floor", formatFloorHex(targetFloorNum))
                            .withHexParam("channel", String.format("%02X", targetChannel))
                            .withHexParam("address", String.format("%02X", targetAddress))
                            .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                            .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                            .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                            .withDescription("Move out of elevator to wait point on target floor")
                            .withTarget(targetFloorWaitPoint)
                            .withPreridePoint(targetFloorPreridePoint)
                            .withRidePoint(targetFloorRidePoint)
                            .withPlayMusic(false)
                            .build();
                    commands.add(cmd5b);
                    getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd5b.getDescription());

                    LoraCommand cmd6b = new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, targetFloorWaitPoint.getName())
                            .withHexParam("floor", formatFloorHex(targetFloorNum))
                            .withHexParam("channel", String.format("%02X", targetChannel))
                            .withHexParam("address", String.format("%02X", targetAddress))
                            .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                            .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                            .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                            .withExpectedResponse("00")
                            .withDescription("Cancel door open after exiting elevator")
                            .withPlayMusic(false)
                            .withTarget(targetFloorWaitPoint)
                            .build();
                    commands.add(cmd6b);
                    getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd6b.getDescription());
                }
            }

            // Final move to target position
            LoraCommand cmd = new LoraCommand.Builder(getNavigationType(nextPosition), nextPosition.getName())
                    .withDescription("Move to final target position")
                    .withPlayMusic(false)
                    .withTarget(nextPosition)
                    .build();
            commands.add(cmd);
            getDebugger().log("Phase 3 - Added final command " + commands.size() + ": " + cmd.getDescription());

        } else {
            getDebugger().log("Phase 3: Already on target floor " + targetFloorNum +
                    ", moving directly to target position");

            // Only add move command if nextPosition is not the same as targetTransitionPoint
            // AND if we're not already at that position
            if (!nextPosition.getName().equals(targetTransitionPoint.getName()) &&
                    !isAtSamePosition(currentPosition, targetTransitionPoint)) {
                LoraCommand cmd = new LoraCommand.Builder(getNavigationType(nextPosition), nextPosition.getName())
                        .withDescription("Move to target position (already on correct floor)")
                        .withPlayMusic(false)
                        .withTarget(nextPosition)
                        .build();
                commands.add(cmd);
                getDebugger().log("Phase 3 - Added command " + commands.size() + ": " + cmd.getDescription());
            } else {
                getDebugger().log("Phase 3 - Skipping move command because nextPosition is the same as current position or targetTransitionPoint");
            }
        }

        getDebugger().log("buildCommandSequenceType6 completed, generated " + commands.size() + " commands");
        getDebugger().log("========== Final Command List ==========");
        for (int i = 0; i < commands.size(); i++) {
            LoraCommand cmd = commands.get(i);
            getDebugger().log("  Command[" + i + "]: " + cmd.getDescription());
        }
        getDebugger().log("=====================================================");

        return commands;
    }

    // Type 1: Current is non-elevator point, next is wait point (same floor)
    private static List<LoraCommand> buildCommandSequenceType1(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

//        // Get elevator config from the wait point
//        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
//        if (elevatorConfig == null) {
//            getDebugger().logError("Type1", "No elevator config found for point " + currentPosition.getName());
//            return commands;
//        }
//
//        int channel = elevatorConfig.getChannel();
//        int address = elevatorConfig.getAddress();
//        getDebugger().log("Type1: Using elevator config: channel=" + channel + ", address=" + address);

        String nextBuilding = extractBuildingId(nextPosition.getMapName());

        // Find the actual wait point for the target floor
        Position targetWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), nextPosition.getFloor(), nextBuilding, true);
        if (targetWaitPoint == null) {
            getDebugger().logWarning("Type1", "No wait point found for floor " + nextPosition.getFloor());
            return commands;
        }

        getDebugger().log("Type1: Moving to wait point " + targetWaitPoint.getName());

        // Add MOVE command
        commands.add(new LoraCommand.Builder(getNavigationType(targetWaitPoint), targetWaitPoint.getName())
                .withDescription("Move to elevator wait point")
                .withPlayMusic(false)
                .withTarget(targetWaitPoint)
                .build());

        return commands;
    }

    // Type 2: Current is wait point, next is ride point (same floor)
    private static List<LoraCommand> buildCommandSequenceType2(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, currentPosition);
        getDebugger().log("Elevator config from current position: " + (elevatorConfig != null ?
                "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                        ", address=" + elevatorConfig.getAddress() : "null"));

        // If not found, try from the next position
        if (elevatorConfig == null) {
            elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
            getDebugger().log("Elevator config from next position: " + (elevatorConfig != null ?
                    "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                            ", address=" + elevatorConfig.getAddress() : "null"));
        }

        // If still not found, use fallback to first available config
        if (elevatorConfig == null && elevatorConfigs != null && !elevatorConfigs.isEmpty()) {
            elevatorConfig = elevatorConfigs.get(0);
            getDebugger().logWarning("buildCommandSequenceType2", "Using fallback config with ID=" + elevatorConfig.getId() +
                    ", channel=" + elevatorConfig.getChannel() + ", address=" + elevatorConfig.getAddress());
        }

        if (elevatorConfig == null) {
            getDebugger().logError("buildCommandSequenceType2", "No elevator config found!");
            return commands;
        }

        int channel = elevatorConfig.getChannel();
        int address = elevatorConfig.getAddress();
        getDebugger().log("Type2: Using elevator config: channel=" + channel + ", address=" + address);

        String currentBuilding = extractBuildingId(currentPosition.getMapName());

        Position currentWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), nextPosition.getFloor(), currentBuilding, false);
        Position currentRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), nextPosition.getFloor(), currentBuilding, false);
        Position currentPreridePoint = findPositionWithFloor(preridePoints, currentPosition.getFloor(), nextPosition.getFloor(), currentBuilding, false);

        if (currentRidePoint == null) {
            getDebugger().logWarning("Type2", "No ride point found for floor " + currentPosition.getFloor());
            return commands;
        }

        String currentFloor = currentPosition.getFloor();

        getDebugger().log("Type2: Entering elevator at floor " + currentFloor);

        // Add QUERY_ACCESS command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_QUERY_ACCESS, currentWaitPoint.getName())
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withExpectedResponse("00")
                .withDescription("Query access for floor " + nextPosition.getFloor())
                .withTarget(currentWaitPoint)
                .build());

        // Add CLAIM_ACCESS command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CLAIM_ACCESS, currentWaitPoint.getName())
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Claim access for floor " + nextPosition.getFloor())
                .withTarget(currentWaitPoint)
                .build());

        // Add ELEVATOR_CALL command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, currentRidePoint.getName())
                .withHexParam("floor", formatFloorHex(currentFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                .withDescription("Call elevator to floor " + currentFloor)
                .withPlayMusic(false)
                .withTarget(currentWaitPoint)
                .build());

        // Add ELEVATOR_CHECK command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, currentRidePoint.getName())
                .withHexParam("floor", formatFloorHex(currentFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withExpectedResponse("01")
                .withRetries(50, 2000)
                .withDescription("Check elevator arrival and door at floor " + currentFloor)
                .withPlayMusic(false)
                .withTarget(currentWaitPoint)
                .build());

//        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, currentRidePoint.getName())
//                .withHexParam("floor", formatFloorHex(currentFloor))
//                .withHexParam("channel", String.format("%02X", channel))
//                .withHexParam("address", String.format("%02X", address))
//                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                .withExpectedResponse("01")
//                .withRetries(50, 2000)
//                .withDescription("Check elevator door at floor " + currentFloor)
//                .withPlayMusic(false)
//                .withTarget(currentWaitPoint)
//                .build());

        // Add ELEVATOR_OPEN command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, currentRidePoint.getName())
                .withHexParam("floor", formatFloorHex(currentFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Open elevator door")
                .withPlayMusic(false)
                .withTarget(currentWaitPoint)
                .build());

        // Add IN_ELEVATOR_MOVE command with preride point
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_IN_ELEVATOR_MOVE, currentRidePoint.getName())
                .withHexParam("floor", formatFloorHex(currentFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Move inside elevator")
                .withPlayMusic(false)
                .withTarget(currentRidePoint)
                .withPreridePoint(currentPreridePoint)
                .withRidePoint(currentRidePoint)
                .build());

        return commands;
    }

    // Type 3: Current is ride point, next is ride point (different floors)
    private static List<LoraCommand> buildCommandSequenceType3(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

        // Get elevator config from the ride point
        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, currentPosition);
        getDebugger().log("Elevator config from current position: " + (elevatorConfig != null ?
                "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                        ", address=" + elevatorConfig.getAddress() : "null"));

        // If not found, try from the next position
        if (elevatorConfig == null) {
            elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
            getDebugger().log("Elevator config from next position: " + (elevatorConfig != null ?
                    "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                            ", address=" + elevatorConfig.getAddress() : "null"));
        }

        // If still not found, use fallback to first available config
        if (elevatorConfig == null && elevatorConfigs != null && !elevatorConfigs.isEmpty()) {
            elevatorConfig = elevatorConfigs.get(0);
            getDebugger().logWarning("buildCommandSequenceType3", "Using fallback config with ID=" + elevatorConfig.getId() +
                    ", channel=" + elevatorConfig.getChannel() + ", address=" + elevatorConfig.getAddress());
        }

        if (elevatorConfig == null) {
            getDebugger().logError("buildCommandSequenceType3", "No elevator config found!");
            return commands;
        }

        int channel = elevatorConfig.getChannel();
        int address = elevatorConfig.getAddress();
        getDebugger().log("Type3: Using elevator config: channel=" + channel + ", address=" + address);

        String currentBuilding = extractBuildingId(currentPosition.getMapName());
        String targetBuilding = extractBuildingId(nextPosition.getMapName());

        Position currentRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), nextPosition.getFloor(), currentBuilding, false);
        Position targetRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), nextPosition.getFloor(), targetBuilding, true);

        if (currentRidePoint == null || targetRidePoint == null) {
            getDebugger().logWarning("Type3", "Missing ride points for floor transition");
            return commands;
        }

        String targetFloor = nextPosition.getFloor();

        getDebugger().log("Type3: Moving from floor " + currentPosition.getFloor() + " to floor " + targetFloor);

        // Add ELEVATOR_CANCEL_OPEN command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, currentRidePoint.getName())
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Cancel door open")
                .withPlayMusic(false)
                .withTarget(currentRidePoint)
                .build());

        // Add ELEVATOR_CALL for target floor
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CALL, targetRidePoint.getName())
                .withHexParam("floor", formatFloorHex(targetFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withRetries(10, ELEVATOR_CALL_RETRY_DELAY_MS)
                .withDescription("Call elevator to floor " + targetFloor)
                .withPlayMusic(false)
                .withTarget(targetRidePoint)
                .build());

        // Add CHANGE_MAP command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_CHANGE_MAP, targetRidePoint.getName())
                .withHexParam("x", nextPosition.getPosX())
                .withHexParam("y", nextPosition.getPosY())
                .withHexParam("yaw", nextPosition.getYaw())
                .withHexParam("mapName", nextPosition.getMapName())
                .withRetries(100, 2000)
                .withDescription("Change map to floor " + targetFloor)
                .withPlayMusic(false)
                .withTarget(targetRidePoint)
                .build());

        // Add ELEVATOR_CHECK for target floor
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_ARRIVAL_DOOR_CHECK, targetRidePoint.getName())
                .withHexParam("floor", formatFloorHex(targetFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withExpectedResponse("01")
                .withRetries(50, 2000)
                .withDescription("Check elevator arrival and door at floor " + targetFloor)
                .withPlayMusic(false)
                .withTarget(targetRidePoint)
                .build());

//        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_DOOR_CHECK, targetRidePoint.getName())
//                .withHexParam("floor", formatFloorHex(targetFloor))
//                .withHexParam("channel", String.format("%02X", channel))
//                .withHexParam("address", String.format("%02X", address))
//                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
//                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
//                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
//                .withExpectedResponse("01")
//                .withRetries(50, 2000)
//                .withDescription("Check elevator door at floor " + targetFloor)
//                .withPlayMusic(false)
//                .withTarget(targetRidePoint)
//                .build());

        // Add ELEVATOR_OPEN for target floor
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_OPEN, targetRidePoint.getName())
                .withHexParam("floor", formatFloorHex(targetFloor))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Open elevator door at floor " + targetFloor)
                .withPlayMusic(false)
                .withTarget(targetRidePoint)
                .build());

        return commands;
    }

    // Type 4: Current is ride point, next is wait point (same floor, exiting elevator)
    private static List<LoraCommand> buildCommandSequenceType4(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

        // Get elevator config from the ride point
        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, currentPosition);
        getDebugger().log("Elevator config from current position: " + (elevatorConfig != null ?
                "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                        ", address=" + elevatorConfig.getAddress() : "null"));

        // If not found, try from the next position
        if (elevatorConfig == null) {
            elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
            getDebugger().log("Elevator config from next position: " + (elevatorConfig != null ?
                    "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                            ", address=" + elevatorConfig.getAddress() : "null"));
        }

        // If still not found, use fallback to first available config
        if (elevatorConfig == null && elevatorConfigs != null && !elevatorConfigs.isEmpty()) {
            elevatorConfig = elevatorConfigs.get(0);
            getDebugger().logWarning("buildCommandSequenceType4", "Using fallback config with ID=" + elevatorConfig.getId() +
                    ", channel=" + elevatorConfig.getChannel() + ", address=" + elevatorConfig.getAddress());
        }

        if (elevatorConfig == null) {
            getDebugger().logError("buildCommandSequenceType4", "No elevator config found!");
            return commands;
        }

        int channel = elevatorConfig.getChannel();
        int address = elevatorConfig.getAddress();
        getDebugger().log("Type4: Using elevator config: channel=" + channel + ", address=" + address);

        String nextBuilding = extractBuildingId(nextPosition.getMapName());

        Position targetWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), nextPosition.getFloor(), nextBuilding, true);
        Position targetPreridePoint = findPositionWithFloor(preridePoints, currentPosition.getFloor(), nextPosition.getFloor(), nextBuilding, true);  // NEW
        Position targetRidePoint = findPositionWithFloor(ridePoints, currentPosition.getFloor(), nextPosition.getFloor(), nextBuilding, true);

        if (targetWaitPoint == null) {
            getDebugger().logWarning("Type4", "No wait point found for floor " + nextPosition.getFloor());
            return commands;
        }

        getDebugger().log("Type4: Exiting elevator at floor " + nextPosition.getFloor());

        // Add OUT_ELEVATOR_MOVE command with preride point
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_OUT_ELEVATOR_MOVE, targetWaitPoint.getName())
                .withHexParam("floor", formatFloorHex(nextPosition.getFloor()))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Move outside elevator")
                .withTarget(targetWaitPoint)
                .withPreridePoint(targetPreridePoint)
                .withRidePoint(targetRidePoint)
                .withPlayMusic(false)
                .build());

        // Add ELEVATOR_CANCEL_OPEN command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN, targetWaitPoint.getName())
                .withHexParam("floor", formatFloorHex(nextPosition.getFloor()))
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Cancel door open at floor " + nextPosition.getFloor())
                .withPlayMusic(false)
                .withTarget(targetWaitPoint)
                .build());

        return commands;
    }

    // Type 5: Current is wait point, next is non-elevator point (same floor)
    private static List<LoraCommand> buildCommandSequenceType5(
            List<ElevatorViewModel.ElevatorConfig> elevatorConfigs,
            Position currentPosition,
            Position nextPosition,
            List<Position> preridePoints,
            List<Position> waitPoints,
            List<Position> ridePoints,
            List<Position> transitionPoints,
            RobotCommunicationParams robotCommParams) {

        List<LoraCommand> commands = new ArrayList<>();

        // Get elevator config from the wait point
        ElevatorViewModel.ElevatorConfig elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, currentPosition);
        getDebugger().log("Elevator config from current position: " + (elevatorConfig != null ?
                "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                        ", address=" + elevatorConfig.getAddress() : "null"));

        // If not found, try from the next position
        if (elevatorConfig == null) {
            elevatorConfig = getElevatorConfigFromPoint(elevatorConfigs, nextPosition);
            getDebugger().log("Elevator config from next position: " + (elevatorConfig != null ?
                    "ID=" + elevatorConfig.getId() + ", channel=" + elevatorConfig.getChannel() +
                            ", address=" + elevatorConfig.getAddress() : "null"));
        }

        // If still not found, use fallback to first available config
        if (elevatorConfig == null && elevatorConfigs != null && !elevatorConfigs.isEmpty()) {
            elevatorConfig = elevatorConfigs.get(0);
            getDebugger().logWarning("buildCommandSequenceType5", "Using fallback config with ID=" + elevatorConfig.getId() +
                    ", channel=" + elevatorConfig.getChannel() + ", address=" + elevatorConfig.getAddress());
        }

        if (elevatorConfig == null) {
            getDebugger().logError("buildCommandSequenceType5", "No elevator config found!");
            return commands;
        }

        int channel = elevatorConfig.getChannel();
        int address = elevatorConfig.getAddress();
        getDebugger().log("Type5: Using elevator config: channel=" + channel + ", address=" + address);

        String currentBuilding = extractBuildingId(currentPosition.getMapName());

        // Find the wait point for the current floor
        Position currentWaitPoint = findPositionWithFloor(waitPoints, currentPosition.getFloor(), nextPosition.getFloor(), currentBuilding, false);
        if (currentWaitPoint == null) {
            getDebugger().logWarning("Type5", "No wait point found for floor " + currentPosition.getFloor());
            return commands;
        }

        // Add RELEASE_ACCESS command
        commands.add(new LoraCommand.Builder(NavigationOrderType.OP_ELEVATOR_RELEASE_ACCESS, nextPosition.getName())
                .withHexParam("channel", String.format("%02X", channel))
                .withHexParam("address", String.format("%02X", address))
                .withHexParam("robotId", String.format("%02X", robotCommParams.getRobotId()))
                .withHexParam("robotChannel", String.format("%02X", robotCommParams.getLoraChannel()))
                .withHexParam("robotAddress", String.format("%02X", robotCommParams.getLoraAddress()))
                .withDescription("Release access")
                .withTarget(nextPosition)
                .build());

        // Add MOVE command
        commands.add(new LoraCommand.Builder(getNavigationType(nextPosition), nextPosition.getName())
                .withDescription("Move from elevator wait point to target")
                .withTarget(nextPosition)
                .withPlayMusic(false)
                .build());

        return commands;
    }

    private static boolean notElevatorPositionType(int positionType) {
        Set<Integer> positionTypeSet = new HashSet<>();
        positionTypeSet.add(0);
        positionTypeSet.add(1);
        positionTypeSet.add(5);
        positionTypeSet.add(6);
        positionTypeSet.add(7);
        positionTypeSet.add(8);
        positionTypeSet.add(9);
        positionTypeSet.add(10);
        positionTypeSet.add(11);
        positionTypeSet.add(12);
        positionTypeSet.add(14);
        positionTypeSet.add(15);
        positionTypeSet.add(16);
        positionTypeSet.add(17);
        positionTypeSet.add(18);
        positionTypeSet.add(19);
        return positionTypeSet.contains(positionType);
    }

    private static NavigationOrderType getNavigationType(Position position) {
        NavigationOrderType result;
        NavigationStateDebugger debugger = NavigationStateDebugger.getInstance();

        String positionInfo = String.format("Position: name='%s', type=%d, taskType=%d, virtualOrbit=%b",
                position.getName() != null ? position.getName() : "unnamed",
                position.getType(),
                position.getTaskType(),
                position.isVirtualOrbit());

        switch (position.getType()) {
            case 1:
            case 3:
            case 14:
                if (!position.isVirtualOrbit()) {
                    result = NavigationOrderType.OP_FREE_MOVE;
                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ %s -> OP_FREE_MOVE (type=1, virtualOrbit=false)", positionInfo));
                    }
                } else {
                    result = NavigationOrderType.OP_FIXED_MOVE;
                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ %s -> OP_FIXED_MOVE (type=1, virtualOrbit=true)", positionInfo));
                    }
                }
                break;
            case 5:
                result = NavigationOrderType.OP_RECOGNIZE_LOAD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_RECOGNIZE_LOAD (type=5)", positionInfo));
                }
                break;
            case 6:
                result = NavigationOrderType.OP_RECOGNIZE_UNLOAD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_RECOGNIZE_UNLOAD (type=6)", positionInfo));
                }
                break;
            case 7:
                result = NavigationOrderType.OP_EXIT_SHELF;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_EXIT_SHELF (type=7)", positionInfo));
                }
                break;
            case 8:
                result = NavigationOrderType.OP_NON_RECOGNIZE_LOAD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_NON_RECOGNIZE_LOAD (type=8)", positionInfo));
                }
                break;
            case 9:
                result = NavigationOrderType.OP_NON_RECOGNIZE_UNLOAD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_NON_RECOGNIZE_UNLOAD (type=9)", positionInfo));
                }
                break;
            case 10:
                result = NavigationOrderType.OP_CHARGE_START;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_CHARGE_START (type=10)", positionInfo));
                }
                break;
            case 11:
                result = NavigationOrderType.OP_CHARGE_STOP;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_CHARGE_STOP (type=11)", positionInfo));
                }
                break;
            case 17:
                result = NavigationOrderType.OP_FORWARD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_FORWARD", positionInfo));
                }
                 break;
            case 18:
                result = NavigationOrderType.OP_BACKWARD;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_BACKWARD", positionInfo));
                }
                break;
            case 19:
                result = NavigationOrderType.OP_RECOGNIZE_ENTRY_ONLY;
                if (debugger != null) {
                    debugger.logInfo(String.format("✅ %s -> OP_RECOGNIZE_ENTRY_ONLY (type=19)", positionInfo));
                }
                break;
            default:
                if (!position.isVirtualOrbit()) {
                    result = NavigationOrderType.OP_FREE_MOVE;
                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ %s -> OP_FREE_MOVE (type=1, virtualOrbit=false)", positionInfo));
                    }
                } else {
                    result = NavigationOrderType.OP_FIXED_MOVE;
                    if (debugger != null) {
                        debugger.logInfo(String.format("✅ %s -> OP_FIXED_MOVE (type=1, virtualOrbit=true)", positionInfo));
                    }
                }
                if (debugger != null) {
                    debugger.logWarning("Unknown position type", String.format("%s -> OP_FREE_MOVE (default, unknown type=%d)", positionInfo, position.getType()));
                }
                break;
        }

        return result;
    }

    private static boolean isAtSamePosition(Position currentPos, Position targetPos) {
        if (currentPos == null || targetPos == null) return false;

        // Compare by name, or by coordinates if within threshold
        if (currentPos.getName() != null && targetPos.getName() != null) {
            return currentPos.getName().equals(targetPos.getName());
        }

        // Fallback to coordinate comparison
        double distance = Math.sqrt(
                Math.pow(currentPos.getPosX() - targetPos.getPosX(), 2) +
                        Math.pow(currentPos.getPosY() - targetPos.getPosY(), 2)
        );
        return distance < 0.1; // 10cm threshold
    }

    /**
     * Helper function to add elevator sequence between floors within the SAME building
     * @param fromFloor Starting floor
     * @param toFloor Target floor
     * @param buildingId Building ID where the elevator sequence occurs
     * @return List of elevator positions (wait → ride → ride → wait)
     */
    private static List<Position> addElevatorSequence(
            String fromFloor,
            String toFloor,
            String buildingId,
            List<Position> waitPoints,
            List<Position> ridePoints) {
        return addElevatorSequence(fromFloor, toFloor, buildingId, waitPoints, ridePoints, null);
    }

    private static List<Position> addElevatorSequence(
            String fromFloor,
            String toFloor,
            String buildingId,
            List<Position> waitPoints,
            List<Position> ridePoints,
            String elevatorId) {

        List<Position> elevatorPoints = new ArrayList<>();

        Position fromWaitPoint = findPositionWithFloor(waitPoints, fromFloor, toFloor, buildingId, elevatorId, false);
        Position fromRidePoint = findPositionWithFloor(ridePoints, fromFloor, toFloor, buildingId, elevatorId, false);
        Position toRidePoint = findPositionWithFloor(ridePoints, toFloor, toFloor, buildingId, elevatorId, true);
        Position toWaitPoint = findPositionWithFloor(waitPoints, toFloor, toFloor, buildingId, elevatorId, true);

        getDebugger().log(String.format("Adding elevator sequence from floor %s to floor %s in building %s:",
                fromFloor, toFloor, buildingId));
        getDebugger().log("  fromWaitPoint: " + (fromWaitPoint != null ? fromWaitPoint.getName() : "null"));
        getDebugger().log("  fromRidePoint: " + (fromRidePoint != null ? fromRidePoint.getName() : "null"));
        getDebugger().log("  toRidePoint: " + (toRidePoint != null ? toRidePoint.getName() : "null"));
        getDebugger().log("  toWaitPoint: " + (toWaitPoint != null ? toWaitPoint.getName() : "null"));

        if (fromWaitPoint != null) elevatorPoints.add(fromWaitPoint);
        if (fromRidePoint != null) elevatorPoints.add(fromRidePoint);
        if (toRidePoint != null) elevatorPoints.add(toRidePoint);
        if (toWaitPoint != null) elevatorPoints.add(toWaitPoint);

        return elevatorPoints;
    }

    private static String formatFloorHex(String floorStr) {
        try {
            return String.format("%02X", Integer.parseInt(floorStr));
        } catch (NumberFormatException e) {
            // If it's already hex, parse it as hex
            return String.format("%02X", Integer.parseInt(floorStr, 16));
        }
    }

    private static String formatFloorHex(int floorNum) {
        return String.format("%02X", floorNum);
    }
}
