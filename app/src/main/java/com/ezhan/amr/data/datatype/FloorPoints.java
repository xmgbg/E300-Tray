// FloorPoints.java
package com.ezhan.amr.data.datatype;

import java.util.ArrayList;
import java.util.List;

public class FloorPoints {
    private List<Position> workPoints = new ArrayList<>();
    private Position chargePoint;
    private Position parkPoint;
    private Position preChargePoint;
    private Position relocalizePoint;
    private Position pharmacyPoint;
    private Position pivasPoint;

    // Elevator points
    private List<Position> elevatorPreridePoints = new ArrayList<>();
    private List<Position> elevatorWaitPoints = new ArrayList<>();
    private List<Position> elevatorRidePoints = new ArrayList<>();
    private List<Position> elevatorTransitionPoints = new ArrayList<>();

    // Map identification - which map prefix these points belong to
    private String mapPrefix;

    public FloorPoints() {}

    public FloorPoints(String mapPrefix) {
        this.mapPrefix = mapPrefix;
    }

    // Getter and setter for mapPrefix
    public String getMapPrefix() {
        return mapPrefix;
    }

    public void setMapPrefix(String mapPrefix) {
        this.mapPrefix = mapPrefix;
    }

    // Work points methods
    public List<Position> getWorkPoints() {
        return new ArrayList<>(workPoints);
    }

    public void setWorkPoints(List<Position> workPoints) {
        this.workPoints = new ArrayList<>(workPoints);
    }

    public void addWorkPoint(Position point) {
        this.workPoints.add(point);
    }

    public void removeWorkPoint(Position point) {
        this.workPoints.remove(point);
    }

    /**
     * Remove a work point by its ID
     * @param id The ID of the work point to remove
     * @return true if a point was removed, false otherwise
     */
    public boolean removeWorkPointById(int id) {
        int initialSize = workPoints.size();
        workPoints.removeIf(p -> p.getId() == id);
        return workPoints.size() < initialSize;
    }

    // Charge point methods
    public Position getChargePoint() {
        return chargePoint;
    }

    public void setChargePoint(Position chargePoint) {
        this.chargePoint = chargePoint;
    }

    // Park point methods
    public Position getParkPoint() {
        return parkPoint;
    }

    public void setParkPoint(Position parkPoint) {
        this.parkPoint = parkPoint;
    }

    // Pre-charge point methods
    public Position getPreChargePoint() {
        return preChargePoint;
    }

    public void setPreChargePoint(Position preChargePoint) {
        this.preChargePoint = preChargePoint;
    }

    // Pre-charge point methods
    public Position getRelocalizePoint() {
        return relocalizePoint;
    }

    public void setRelocalizePoint(Position relocalizePoint) {
        this.relocalizePoint = relocalizePoint;
    }

    public Position getPharmacyPoint() {
        return pharmacyPoint;
    }

    public void setPharmacyPoint(Position pharmacyPoint) {
        this.pharmacyPoint = pharmacyPoint;
    }

    public Position getPivasPoint() {
        return pivasPoint;
    }

    public void setPivasPoint(Position pivasPoint) {
        this.pivasPoint = pivasPoint;
    }

    // Elevator wait points methods
    public List<Position> getElevatorWaitPoints() {
        return new ArrayList<>(elevatorWaitPoints);
    }

    public void setElevatorWaitPoints(List<Position> elevatorWaitPoints) {
        this.elevatorWaitPoints = new ArrayList<>(elevatorWaitPoints);
    }

    public void addElevatorWaitPoint(Position point) {
        this.elevatorWaitPoints.add(point);
    }

    public void removeElevatorWaitPoint(Position point) {
        this.elevatorWaitPoints.remove(point);
    }

    public void removeElevatorWaitPointById(int id) {
        elevatorWaitPoints.removeIf(p -> p.getId() == id);
    }

    public void clearElevatorWaitPoints() {
        elevatorWaitPoints.clear();
    }

    // Elevator ride points methods
    public List<Position> getElevatorRidePoints() {
        return new ArrayList<>(elevatorRidePoints);
    }

    public void setElevatorRidePoints(List<Position> elevatorRidePoints) {
        this.elevatorRidePoints = new ArrayList<>(elevatorRidePoints);
    }

    public void addElevatorRidePoint(Position point) {
        this.elevatorRidePoints.add(point);
    }

    public void removeElevatorRidePoint(Position point) {
        this.elevatorRidePoints.remove(point);
    }

    public void removeElevatorRidePointById(int id) {
        elevatorRidePoints.removeIf(p -> p.getId() == id);
    }

    public void clearElevatorRidePoints() {
        elevatorRidePoints.clear();
    }

    // Elevator transition points methods
    public List<Position> getElevatorTransitionPoints() {
        return new ArrayList<>(elevatorTransitionPoints);
    }

    public void setElevatorTransitionPoints(List<Position> elevatorTransitionPoints) {
        this.elevatorTransitionPoints = new ArrayList<>(elevatorTransitionPoints);
    }

    public List<Position> getElevatorPreridePoints() {
        return elevatorPreridePoints;
    }

    public void setElevatorPreridePoints(List<Position> elevatorPreridePoints) {
        this.elevatorPreridePoints = elevatorPreridePoints;
    }

    // Update getAllPoints to include elevator points
    public List<Position> getAllPoints() {
        List<Position> allPoints = new ArrayList<>(workPoints);
        allPoints.addAll(elevatorWaitPoints);
        allPoints.addAll(elevatorRidePoints);
        allPoints.addAll(elevatorTransitionPoints);
        allPoints.addAll(elevatorPreridePoints);
        if (chargePoint != null) allPoints.add(chargePoint);
        if (parkPoint != null) allPoints.add(parkPoint);
        if (preChargePoint != null) allPoints.add(preChargePoint);
        if (relocalizePoint != null) allPoints.add(relocalizePoint);
        if (pharmacyPoint != null) allPoints.add(pharmacyPoint);
        if (pivasPoint != null) allPoints.add(pivasPoint);
        return allPoints;
    }

    // Check if has special points
    public boolean hasChargePoint() {
        return chargePoint != null;
    }

    public boolean hasParkPoint() {
        return parkPoint != null;
    }

    public boolean hasPreChargePoint() {
        return preChargePoint != null;
    }

    public boolean hasRelocalizePoint() {
        return relocalizePoint != null;
    }


    // Check if has elevator points
    public boolean hasElevatorWaitPoints() {
        return !elevatorWaitPoints.isEmpty();
    }

    public boolean hasElevatorRidePoints() {
        return !elevatorRidePoints.isEmpty();
    }

    public boolean hasElevatorTransitionPoints() {
        return !elevatorTransitionPoints.isEmpty();
    }

    // NEW METHOD: Add a position to the appropriate list
    public void addPosition(Position position) {
        if (position == null) return;

        android.util.Log.i("taskDebug7", "[FLOOR-ADD] type=" + position.getType() +
                ", name=" + position.getName() + ", id=" + position.getId() +
                ", floor=" + position.getFloor() + ", BEFORE: workPoints=" + workPoints.size());

        switch (position.getType()) {
            case 1:
                workPoints.add(position);
                break;
            case 2:
                elevatorPreridePoints.add(position);
                break;
            case 3:
                elevatorWaitPoints.add(position);
                break;
            case 4:
                elevatorRidePoints.add(position);
                break;
            case 10:
                chargePoint = position;
                break;
            case 11:
                preChargePoint = position;
                break;
            case 12:
                parkPoint = position;
                break;
            case 13:
                relocalizePoint = position;
                break;
            case 14:
                elevatorTransitionPoints.add(position);
            case 15:
                // ⚠️ case 14 fall-through here is a known bug: type=14 point also overwrites pharmacyPoint
                android.util.Log.w("taskDebug7", "[FLOOR-ADD] BUG: type=14 fall-through to case 15 (pharmacyPoint). " +
                        "name=" + position.getName() + ", pharmacyPoint was=" + (pharmacyPoint != null ? pharmacyPoint.getName() : "null"));
                pharmacyPoint = position;
                break;
            case 16:
                pivasPoint = position;
                break;
            default:
                workPoints.add(position);
                break;
        }
    }

    // NEW METHOD: Remove a position by name and type
    public boolean removePosition(String positionName, int type) {
        List<Position> listToSearch;

        android.util.Log.i("taskDebug7", "[FLOOR-RM] type=" + type +
                ", name=" + positionName + ", BEFORE: workPoints=" + workPoints.size());

        switch (type) {
            case 1:
                listToSearch = workPoints;
                break;
            case 2:
                listToSearch = elevatorPreridePoints;
            case 3:
                // ⚠️ case 2 fall-through here is a known bug: type=2 removal also searches elevatorWaitPoints
                android.util.Log.w("taskDebug7", "[FLOOR-RM] BUG: type=2 fall-through to case 3. name=" + positionName);
                listToSearch = elevatorWaitPoints;
                break;
            case 4:
                listToSearch = elevatorRidePoints;
                break;
            case 10:
                if (chargePoint != null && chargePoint.getName().equals(positionName)) {
                    chargePoint = null;
                    return true;
                }
                return false;
            case 11:
                if (preChargePoint != null && preChargePoint.getName().equals(positionName)) {
                    preChargePoint = null;
                    return true;
                }
                return false;
            case 12:
                if (parkPoint != null && parkPoint.getName().equals(positionName)) {
                    parkPoint = null;
                    return true;
                }
                return false;
            case 13:
                if (relocalizePoint != null && relocalizePoint.getName().equals(positionName)) {
                    relocalizePoint = null;
                    return true;
                }
                return false;
            case 14:
                listToSearch = elevatorTransitionPoints;
                break;
            case 15:
                if (pharmacyPoint != null && pharmacyPoint.getName().equals(positionName)) {
                    pharmacyPoint = null;
                    return true;
                }
                return false;
            case 16:
                if (pivasPoint != null && pivasPoint.getName().equals(positionName)) {
                    pivasPoint = null;
                    return true;
                }
                return false;
            default:
                listToSearch = workPoints;
                break;
        }

        return listToSearch.removeIf(p -> p.getName().equals(positionName));
    }

    // NEW METHOD: Get total count of positions in this floor
    public int getPositionCount() {
        int count = workPoints.size() +
                elevatorWaitPoints.size() +
                elevatorRidePoints.size() +
                elevatorTransitionPoints.size() +
                elevatorPreridePoints.size();
        if (chargePoint != null) count++;
        if (preChargePoint != null) count++;
        if (parkPoint != null) count++;
        if (relocalizePoint != null) count++;
        if (pharmacyPoint != null) count++;
        if (pivasPoint != null) count++;
        return count;
    }

    public Position findPositionByName(String name) {
        // Search in work points
        for (Position pos : workPoints) {
            if (name.equals(pos.getName())) {
                return pos;
            }
        }

        // Search in elevator wait points
        for (Position pos : elevatorPreridePoints) {
            if (name.equals(pos.getName())) {
                return pos;
            }
        }

        // Search in elevator wait points
        for (Position pos : elevatorWaitPoints) {
            if (name.equals(pos.getName())) {
                return pos;
            }
        }

        // Search in elevator ride points
        for (Position pos : elevatorRidePoints) {
            if (name.equals(pos.getName())) {
                return pos;
            }
        }

        // Search in special points
        if (chargePoint != null && name.equals(chargePoint.getName())) {
            return chargePoint;
        }
        if (preChargePoint != null && name.equals(preChargePoint.getName())) {
            return preChargePoint;
        }
        if (parkPoint != null && name.equals(parkPoint.getName())) {
            return parkPoint;
        }
        if (relocalizePoint != null && name.equals(relocalizePoint.getName())) {
            return relocalizePoint;
        }
        if (pharmacyPoint != null && name.equals(pharmacyPoint.getName())) {
            return pharmacyPoint;
        }
        if (pivasPoint != null && name.equals(pivasPoint.getName())) {
            return pivasPoint;
        }

        // Search in elevator ride points
        for (Position pos : elevatorTransitionPoints) {
            if (name.equals(pos.getName())) {
                return pos;
            }
        }

        return null;
    }

    public Position findPositionById(int id) {
        // Search in work points
        for (Position pos : workPoints) {
            if (id == pos.getId()) {
                return pos;
            }
        }

        // Search in elevator wait points
        for (Position pos : elevatorPreridePoints) {
            if (id == pos.getId()) {
                return pos;
            }
        }

        // Search in elevator wait points
        for (Position pos : elevatorWaitPoints) {
            if (id == pos.getId()) {
                return pos;
            }
        }

        // Search in elevator ride points
        for (Position pos : elevatorRidePoints) {
            if (id == pos.getId()) {
                return pos;
            }
        }

        // Search in special points
        if (chargePoint != null && id == chargePoint.getId()) {
            return chargePoint;
        }
        if (preChargePoint != null && id == preChargePoint.getId()) {
            return preChargePoint;
        }
        if (parkPoint != null && id == parkPoint.getId()) {
            return parkPoint;
        }
        if (relocalizePoint != null && id == relocalizePoint.getId()) {
            return relocalizePoint;
        }
        if (pharmacyPoint != null && id == pharmacyPoint.getId()) {
            return pharmacyPoint;
        }
        if (pivasPoint != null && id == pivasPoint.getId()) {
            return pivasPoint;
        }

        for (Position pos : elevatorTransitionPoints) {
            if (id == pos.getId()) {
                return pos;
            }
        }

        return null;
    }
}