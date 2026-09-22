// MapPoints.java
package com.ezhan.amr.data.datatype;

import java.util.HashMap;
import java.util.Map;

public class MapPoints {
    private Map<Integer, FloorPoints> floorPointsMap = new HashMap<>();

    // Map identification - which map prefix these points belong to
    private String mapPrefix;

    public MapPoints() {}

    public MapPoints(String mapPrefix) {
        this.mapPrefix = mapPrefix;
    }

    public String getMapPrefix() {
        return mapPrefix;
    }

    public void setMapPrefix(String mapPrefix) {
        this.mapPrefix = mapPrefix;
    }

    public void setFloorPoints(int floor, FloorPoints points) {
        floorPointsMap.put(floor, points);
    }

    public FloorPoints getFloorPoints(int floor) {
        return floorPointsMap.get(floor);
    }

    public Map<Integer, FloorPoints> getAllFloorPoints() {
        return new HashMap<>(floorPointsMap);
    }

    public boolean hasFloor(int floor) {
        return floorPointsMap.containsKey(floor);
    }

    public void removeFloor(int floor) {
        floorPointsMap.remove(floor);
    }

    public void clear() {
        floorPointsMap.clear();
    }

    // NEW METHOD: Get total count of all positions across all floors
    public int getTotalPositionCount() {
        int total = 0;
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                total += floorPoints.getAllPoints().size();
            }
        }
        return total;
    }

    // NEW METHOD: Add a position to the appropriate floor and category
    public void addPosition(Position position) {
        if (position == null) return;

        int floor;
        try {
            floor = Integer.parseInt(position.getFloor());
        } catch (NumberFormatException | NullPointerException e) {
            floor = 0; // Default floor
        }

        FloorPoints floorPoints = floorPointsMap.get(floor);
        if (floorPoints == null) {
            floorPoints = new FloorPoints(mapPrefix);
            floorPointsMap.put(floor, floorPoints);
        }

        // Ensure the position's mapName matches this MapPoints' mapPrefix
        if (position.getMapName() == null && mapPrefix != null) {
            position.setMapName(mapPrefix + "_" + floor);
        }

        floorPoints.addPosition(position);
    }

    // NEW METHOD: Remove a position by name and type
    public boolean removePosition(String positionName, int type, int floor) {
        FloorPoints floorPoints = floorPointsMap.get(floor);
        if (floorPoints == null) return false;

        return floorPoints.removePosition(positionName, type);
    }

    // NEW METHOD: Get all positions for a specific floor
    public java.util.List<Position> getAllPositionsForFloor(int floor) {
        FloorPoints floorPoints = floorPointsMap.get(floor);
        if (floorPoints == null) return new java.util.ArrayList<>();

        return floorPoints.getAllPoints();
    }

    public Position findPositionByName(String name) {
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                Position position = floorPoints.findPositionByName(name);
                if (position != null) {
                    return position;
                }
            }
        }
        return null;
    }

    public Position findPositionById(int id) {
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                Position position = floorPoints.findPositionById(id);
                if (position != null) {
                    return position;
                }
            }
        }
        return null;
    }

    public void removeFloorPoints(int floor) {
        if (floorPointsMap != null) {
            floorPointsMap.remove(floor);
        }
    }

    /**
     * Check if this MapPoints contains any points
     */
    public boolean isEmpty() {
        return floorPointsMap.isEmpty() || getTotalPositionCount() == 0;
    }
}