package com.ezhan.amr.data.datatype;

import java.util.List;

public class MarkerData {
    private ChargePoint charge;
    private List<MarkerPoint> data;
    private String elevatorNumber;

    public ChargePoint getCharge() {
        return charge;
    }

    public List<MarkerPoint> getData() {
        return data;
    }

    public String getElevatorNumber() {
        return elevatorNumber;
    }

    public void setData(List<MarkerPoint> markerPoints) {
        this.data = markerPoints; // This line was missing!
    }

    // You might also want to add these setters for completeness:
    public void setCharge(ChargePoint charge) {
        this.charge = charge;
    }

    public void setElevatorNumber(String elevatorNumber) {
        this.elevatorNumber = elevatorNumber;
    }
}