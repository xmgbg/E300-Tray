package com.ezhan.amr.data.datatype;

import java.util.List;

public class SafetyArea {
    private int id;
    private float speed;
    private List<List<Position>> polygons; // List of polygons, each polygon is a list of vertices

    public SafetyArea() {
    }

    public SafetyArea(int id, float speed, List<List<Position>> polygons) {
        this.id = id;
        this.speed = speed;
        this.polygons = polygons;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public List<List<Position>> getPolygons() {
        return polygons;
    }

    public void setPolygons(List<List<Position>> polygons) {
        this.polygons = polygons;
    }

    @Override
    public String toString() {
        return "SafetyArea{" +
                "id=" + id +
                ", speed=" + speed +
                ", polygons=" + (polygons != null ? polygons.size() : 0) + " polygons" +
                '}';
    }
}
