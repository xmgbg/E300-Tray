package com.ezhan.amr.data.datatype;

public class ChargePoint {
    private String floor;
    private String mapName;
    private String name;
    private int type;
    private Float x;
    private Float y;
    private Float yaw;

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

    public Float getX() {
        return x;
    }

    public Float getY() {
        return y;
    }

    public Float getYaw() {
        return yaw;
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

    public void setX(Float x) {
        this.x = x;
    }

    public void setY(Float y) {
        this.y = y;
    }

    public void setYaw(Float yaw) {
        this.yaw = yaw;
    }

    @Override
    public String toString() {
        return "ChargePoint{" +
                "floor='" + floor + '\'' +
                ", mapName='" + mapName + '\'' +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", x=" + x +
                ", y=" + y +
                ", yaw=" + yaw +
                '}';
    }
}
