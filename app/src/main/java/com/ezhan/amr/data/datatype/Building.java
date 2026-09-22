// Building.java
package com.ezhan.amr.data.datatype;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class Building {
    private String buildingId;  // e.g., "A", "B", "C", or "01", "02"
    private String buildingName;
    private Map<Integer, FloorPoints> floorPointsMap = new HashMap<>();
    private String mapPrefix;

    /** No-arg constructor required by Gson for proper deserialization. */
    public Building() {}

    public Building(String buildingId, String mapPrefix) {
        this.buildingId = buildingId;
        this.mapPrefix = mapPrefix;
    }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }

    public String getMapPrefix() { return mapPrefix; }
    public void setMapPrefix(String mapPrefix) { this.mapPrefix = mapPrefix; }

    public void setFloorPoints(int floor, FloorPoints points) {
        floorPointsMap.put(floor, points);
    }

    public FloorPoints getFloorPoints(int floor) {
        return floorPointsMap.get(floor);
    }

    public Map<Integer, FloorPoints> getAllFloorPoints() {
        if (floorPointsMap == null) {
            floorPointsMap = new HashMap<>();
        }
        return new HashMap<>(floorPointsMap);
    }

    public boolean hasFloor(int floor) {
        return floorPointsMap.containsKey(floor);
    }

    public void removeFloor(int floor) {
        floorPointsMap.remove(floor);
    }

    public void addPosition(Position position) {
        if (position == null) return;

        int floor;
        try {
            floor = Integer.parseInt(position.getFloor());
        } catch (NumberFormatException | NullPointerException e) {
            floor = 0;
        }

        FloorPoints floorPoints = floorPointsMap.get(floor);
        if (floorPoints == null) {
            // Map name format: mapPrefix-buildingXX-floorYY
            String floorMapName = mapPrefix + "-" + buildingId + "-floor" + floor;
            floorPoints = new FloorPoints(floorMapName);
            floorPointsMap.put(floor, floorPoints);
        }

        if (position.getMapName() == null) {
            String fullMapName = generateMapName(mapPrefix, buildingId, floor);
            position.setMapName(fullMapName);
        }

        floorPoints.addPosition(position);
    }

    public Position findPositionByName(String name) {
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                Position position = floorPoints.findPositionByName(name);
                if (position != null) return position;
            }
        }
        return null;
    }

    public Position findPositionById(int id) {
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                Position position = floorPoints.findPositionById(id);
                if (position != null) return position;
            }
        }
        return null;
    }

    public int getTotalPositionCount() {
        int total = 0;
        for (FloorPoints floorPoints : floorPointsMap.values()) {
            if (floorPoints != null) {
                total += floorPoints.getPositionCount();
            }
        }
        return total;
    }

    public boolean isEmpty() {
        return floorPointsMap.isEmpty() || getTotalPositionCount() == 0;
    }

    public void clear() {
        floorPointsMap.clear();
    }
}