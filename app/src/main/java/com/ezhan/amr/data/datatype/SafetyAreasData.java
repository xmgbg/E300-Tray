package com.ezhan.amr.data.datatype;

import java.util.ArrayList;
import java.util.List;

public class SafetyAreasData {
    private List<SafetyArea> safetyAreas;

    public void SafetyAreasData() {
        safetyAreas = new ArrayList<>();
    }

    public void SafetyAreasData(List<SafetyArea> safetyAreas) {
        this.safetyAreas = safetyAreas;
    }

    public List<SafetyArea> getSafetyAreas() {
        return safetyAreas;
    }

    public void setSafetyAreas(List<SafetyArea> safetyAreas) {
        this.safetyAreas = safetyAreas;
    }

    /**
     * Get safety area by ID
     */
    public SafetyArea getSafetyAreaById(int id) {
        for (SafetyArea area : safetyAreas) {
            if (area.getId() == id) {
                return area;
            }
        }
        return null;
    }

    /**
     * Get all non-empty safety areas
     */
    public List<SafetyArea> getNonEmptySafetyAreas() {
        List<SafetyArea> nonEmpty = new ArrayList<>();
        for (SafetyArea area : safetyAreas) {
            if (area.getPolygons() != null && !area.getPolygons().isEmpty()) {
                nonEmpty.add(area);
            }
        }
        return nonEmpty;
    }

    @Override
    public String toString() {
        return "SafetyAreasData{" +
                "safetyAreas=" + safetyAreas +
                ", total=" + safetyAreas.size() +
                ", nonEmpty=" + getNonEmptySafetyAreas().size() +
                '}';
    }
}
