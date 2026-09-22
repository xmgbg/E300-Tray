package com.ezhan.amr.data.datatype;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiBuildingMapPoints {
    private Map<String, Building> buildings = new HashMap<>();  // Key: buildingId
    private String baseMapPrefix;

    // Update the patterns at the top of MultiBuildingMapPoints class
    private static final Pattern MULTI_BUILDING_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)_(\\d+)$");
    private static final Pattern LEGACY_TWO_PART_PATTERN = Pattern.compile("^([A-Za-z]+)_(\\d+)$");

    // Pattern for legacy one-part format: {mapPrefix}{floorId?}
    // Example: Test26 -> mapPrefix="Test", buildingId="0", floor=0
    private static final Pattern LEGACY_ONE_PART_PATTERN = Pattern.compile("^([A-Za-z]+)\\d*$");

    public MultiBuildingMapPoints() {}

    public MultiBuildingMapPoints(String baseMapPrefix) {
        this.baseMapPrefix = baseMapPrefix;
    }

    public String getBaseMapPrefix() { return baseMapPrefix; }
    public void setBaseMapPrefix(String baseMapPrefix) { this.baseMapPrefix = baseMapPrefix; }

    // Building management
    public void addBuilding(Building building) {
        if (building != null) {
            buildings.put(building.getBuildingId(), building);
        }
    }

    public void addBuilding(String buildingId) {
        String mapPrefix = baseMapPrefix != null ? baseMapPrefix : "map";
        buildings.put(buildingId, new Building(buildingId, mapPrefix));
    }

    public Building getBuilding(String buildingId) {
        return buildings.get(buildingId);
    }

    public void removeBuilding(String buildingId) {
        buildings.remove(buildingId);
    }

    public Map<String, Building> getAllBuildings() {
        return new HashMap<>(buildings);
    }

    public boolean hasBuilding(String buildingId) {
        return buildings.containsKey(buildingId);
    }

    // Position management with building+floor
    public void addPosition(String buildingId, Position position) {
        Building building = buildings.get(buildingId);
        if (building == null) {
            building = new Building(buildingId, baseMapPrefix);
            buildings.put(buildingId, building);
        }
        building.addPosition(position);
    }

    public void addPosition(Position position) {
        // Extract building from position's mapName or use default
        String buildingId = extractBuildingIdFromPosition(position);
        addPosition(buildingId, position);
    }

    public FloorPoints getFloorPoints(String buildingId, int floor) {
        Building building = buildings.get(buildingId);
        return building != null ? building.getFloorPoints(floor) : null;
    }

    public void setFloorPoints(String buildingId, int floor, FloorPoints points) {
        Building building = buildings.get(buildingId);
        if (building == null) {
            building = new Building(buildingId, baseMapPrefix);
            buildings.put(buildingId, building);
        }
        building.setFloorPoints(floor, points);
    }

    public List<Position> getAllPositionsForFloor(String buildingId, int floor) {
        FloorPoints floorPoints = getFloorPoints(buildingId, floor);
        return floorPoints != null ? floorPoints.getAllPoints() : new ArrayList<>();
    }

    public Position findPositionByName(String name) {
        for (Building building : buildings.values()) {
            Position position = building.findPositionByName(name);
            if (position != null) return position;
        }
        return null;
    }

    public Position findPositionById(int id) {
        for (Building building : buildings.values()) {
            Position position = building.findPositionById(id);
            if (position != null) return position;
        }
        return null;
    }

    public Position findPosition(String buildingId, String name) {
        Building building = buildings.get(buildingId);
        return building != null ? building.findPositionByName(name) : null;
    }

    public int getTotalPositionCount() {
        int total = 0;
        for (Building building : buildings.values()) {
            total += building.getTotalPositionCount();
        }
        return total;
    }

    public boolean isEmpty() {
        return buildings.isEmpty() || getTotalPositionCount() == 0;
    }

    public void clear() {
        buildings.clear();
    }

    // Helper methods
    private String extractBuildingIdFromPosition(Position position) {
        if (position.getMapName() != null) {
            return extractBuildingId(position.getMapName());
        }
        return "0";
    }

    // Generate map name: {basePrefix}{buildingId}_{floor}
    public static String generateMapName(String basePrefix, String buildingId, int floor) {
        // If buildingId is "0", use legacy format without building ID
        if ("0".equals(buildingId) || "default".equals(buildingId)) {
            return basePrefix + "_" + floor;
        }
        return basePrefix + buildingId + "_" + floor;
    }

    /**
     * Parse building ID from map name
     * Priority:
     * 1. Multi-building format: prefix_buildingId_floor -> returns buildingId
     * 2. Legacy two-part format: prefix_floor -> returns "0"
     * 3. Legacy one-part format: prefix -> returns "0"
     */
    public static String extractBuildingId(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "0";
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix_buildingId_floor
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            return multiMatcher.group(2);
        }

        // Check legacy two-part format: prefix_floor
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            return "0";
        }

        // Legacy one-part format or any other format
        return "0";
    }

    /**
     * Extract map prefix from map name
     * Examples:
     * - "test13_11" -> "test"
     * - "Test_12" -> "Test"
     * - "Test26" -> "Test"
     */
    public static String extractMapPrefix(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return "";
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix_buildingId_floor
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            return multiMatcher.group(1);
        }

        // Check legacy two-part format: prefix_floor
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            return twoPartMatcher.group(1);
        }

        // Legacy one-part format: extract alphabetic prefix
        Matcher onePartMatcher = LEGACY_ONE_PART_PATTERN.matcher(fullMapName);
        if (onePartMatcher.matches()) {
            return onePartMatcher.group(1);
        }

        // Return as is if no pattern matches
        return fullMapName;
    }

    /**
     * Extract floor number from map name
     * Examples:
     * - "test13_11" -> 11
     * - "Test_12" -> 12
     * - "Test26" -> 0
     */
    public static Integer extractMapFloor(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return 0;
        }

        fullMapName = removeFileExtension(fullMapName);

        // Check multi-building format: prefix_buildingId_floor
        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            try {
                return Integer.parseInt(multiMatcher.group(3));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // Check legacy two-part format: prefix_floor
        Matcher twoPartMatcher = LEGACY_TWO_PART_PATTERN.matcher(fullMapName);
        if (twoPartMatcher.matches()) {
            try {
                return Integer.parseInt(twoPartMatcher.group(2));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // Legacy one-part format has no floor
        return 0;
    }

    /**
     * Check if the map name uses the new multi-building format
     * (has pattern: prefix_buildingId_floor where buildingId is not "0")
     */
    public static boolean isMultiBuildingFormat(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return false;
        }

        fullMapName = removeFileExtension(fullMapName);

        Matcher multiMatcher = MULTI_BUILDING_PATTERN.matcher(fullMapName);
        if (multiMatcher.matches()) {
            String buildingId = multiMatcher.group(2);
            // If buildingId is "0", treat as legacy format
            return !"0".equals(buildingId);
        }

        return false;
    }

    /**
     * Check if the map name uses legacy two-part format (prefix_floor)
     */
    public static boolean isLegacyTwoPartFormat(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return false;
        }

        fullMapName = removeFileExtension(fullMapName);
        return LEGACY_TWO_PART_PATTERN.matcher(fullMapName).matches();
    }

    /**
     * Check if the map name uses legacy one-part format (prefix only)
     */
    public static boolean isLegacyOnePartFormat(String fullMapName) {
        if (fullMapName == null || fullMapName.isEmpty()) {
            return false;
        }

        fullMapName = removeFileExtension(fullMapName);

        // Not two-part and not multi-building
        return !LEGACY_TWO_PART_PATTERN.matcher(fullMapName).matches()
                && !MULTI_BUILDING_PATTERN.matcher(fullMapName).matches();
    }

    private static String removeFileExtension(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return "";
        }
        return mapName.replaceFirst("\\.[^.]+$", "");
    }

    /**
     * Get the first non-zero building (building ID not "0" or "default")
     * @return The first building with ID not equal to "0" or "default", or null if none exists
     */
    public Building getFirstNonZeroBuilding() {
        for (Map.Entry<String, Building> entry : buildings.entrySet()) {
            String buildingId = entry.getKey();
            if (!"0".equals(buildingId) && !"default".equals(buildingId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get the building ID of the first non-zero building
     * @return The building ID of the first non-zero building, or null if none exists
     */
    public String getFirstNonZeroBuildingId() {
        Building building = getFirstNonZeroBuilding();
        return building != null ? building.getBuildingId() : null;
    }

    /**
     * Check if there are any non-zero buildings in the map
     * @return true if at least one building with ID not "0" or "default" exists
     */
    public boolean hasNonZeroBuildings() {
        for (String buildingId : buildings.keySet()) {
            if (!"0".equals(buildingId) && !"default".equals(buildingId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the first non-zero building, or return the default building if no non-zero exists
     * @return The first non-zero building, or the building with ID "0" if it exists, otherwise null
     */
    public Building getFirstNonZeroBuildingOrDefault() {
        // First try to find non-zero building
        Building nonZeroBuilding = getFirstNonZeroBuilding();
        if (nonZeroBuilding != null) {
            return nonZeroBuilding;
        }

        // Fallback to default building (ID "0")
        return buildings.get("0");
    }

    /**
     * Get all non-zero buildings
     * @return Map of building ID to Building for all buildings with ID not "0" or "default"
     */
    public Map<String, Building> getNonZeroBuildings() {
        Map<String, Building> nonZeroBuildings = new HashMap<>();
        for (Map.Entry<String, Building> entry : buildings.entrySet()) {
            String buildingId = entry.getKey();
            if (!"0".equals(buildingId) && !"default".equals(buildingId)) {
                nonZeroBuildings.put(buildingId, entry.getValue());
            }
        }
        return nonZeroBuildings;
    }
}