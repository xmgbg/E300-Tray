package com.ezhan.amr.data.datatype;

import java.util.List;

public class MapArea {
    private String name;
    private String type;
    private String channel;
    private String address;
    private String mapName;
    private List<Float> polygonPoints;
    private float resolution;
    private float mapOriginX;
    private float mapOriginY;
    private float mapOriginZ;
    /** 区域膨胀参数 (单位: 米, 支持负数)。正数放大区域(提前触发), 负数缩小区域(延后触发) */
    private float expand = 0f;
    /** 交管区域: 其他参与协调的机器人列表; 非交管区域为 null/空 */
    private List<OtherRobot> otherRobots;

    public MapArea(String name, String type, String channel, String address, String mapName) {
        this.name = name;
        this.type = type;
        this.channel = channel;
        this.address = address;
        this.mapName = mapName;
    }

    public MapArea(String name, String type, String channel, String address, String mapName, List<Float> polygonPoints) {
        this.name = name;
        this.type = type;
        this.channel = channel;
        this.address = address;
        this.mapName = mapName;
        this.polygonPoints = polygonPoints;
    }

    public MapArea(String name, String type, String channel, String address, String mapName, List<Float> polygonPoints, float resolution, float mapOriginX, float mapOriginY, float mapOriginZ) {
        this.name = name;
        this.type = type;
        this.channel = channel;
        this.address = address;
        this.mapName = mapName;
        this.polygonPoints = polygonPoints;
        this.resolution = resolution;
        this.mapOriginX = mapOriginX;
        this.mapOriginY = mapOriginY;
        this.mapOriginZ = mapOriginZ;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getMapName() {
        return mapName;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    public List<Float> getPolygonPoints() {
        return polygonPoints;
    }

    public void setPolygonPoints(List<Float> polygonPoints) {
        this.polygonPoints = polygonPoints;
    }

    public float getResolution() {
        return resolution;
    }

    public void setResolution(float resolution) {
        this.resolution = resolution;
    }

    public float getMapOriginX() {
        return mapOriginX;
    }

    public void setMapOriginX(float mapOriginX) {
        this.mapOriginX = mapOriginX;
    }

    public float getMapOriginY() {
        return mapOriginY;
    }

    public void setMapOriginY(float mapOriginY) {
        this.mapOriginY = mapOriginY;
    }

    public float getMapOriginZ() {
        return mapOriginZ;
    }

    public void setMapOriginZ(float mapOriginZ) {
        this.mapOriginZ = mapOriginZ;
    }

    public List<OtherRobot> getOtherRobots() {
        return otherRobots;
    }

    public void setOtherRobots(List<OtherRobot> otherRobots) {
        this.otherRobots = otherRobots;
    }

    /** 区域膨胀参数 (米), 正数放大区域, 负数缩小区域, 0 表示不膨胀 */
    public float getExpand() {
        return expand;
    }

    public void setExpand(float expand) {
        this.expand = expand;
    }
}