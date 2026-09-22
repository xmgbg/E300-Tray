package com.ezhan.amr.data.datatype;

public class MarkerPoint {
    private String floor;
    private String mapName;
    private String name;
    private int type;
    private float x;
    private float y;
    private float yaw;
    private int id;

    private float prefixPointX;
    private float prefixPointY;
    private float prefixPointYaw;

    // Getters and setters
    public float getPrefixPointX() {
        return prefixPointX;
    }

    public void setPrefixPointX(float prefixPointX) {
        this.prefixPointX = prefixPointX;
    }

    public double getPrefixPointY() {
        return prefixPointY;
    }

    public void setPrefixPointY(float prefixPointY) {
        this.prefixPointY = prefixPointY;
    }

    public float getPrefixPointYaw() {
        return prefixPointYaw;
    }

    public void setPrefixPointYaw(float prefixPointYaw) {
        this.prefixPointYaw = prefixPointYaw;
    }

    // Getters
    public String getFloor() {
        return floor;
    }

    public String getMapName() {
        return mapName;
    }

    public String getName() {
        return name;
    }

    public int getType() {
        return type;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getYaw() {
        return yaw;
    }

    public int getId() {
        return id;
    }

    // Setters
    public void setFloor(String floor) {
        this.floor = floor;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(int type) {
        this.type = type;
    }

    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "MarkerPoint{" +
                "floor='" + floor + '\'' +
                ", mapName='" + mapName + '\'' +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", x=" + x +
                ", y=" + y +
                ", yaw=" + yaw +
                ", id=" + id +
                '}';
    }
}
