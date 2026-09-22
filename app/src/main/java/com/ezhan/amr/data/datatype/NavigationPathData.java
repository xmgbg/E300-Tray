package com.ezhan.amr.data.datatype;

import java.util.List;

public class NavigationPathData {
    private List<Position> line;
    private String talk;
    private String time;

    // Getters and setters
    public List<Position> getLine() { return line; }
    public void setLine(List<Position> line) { this.line = line; }
    public String getTalk() { return talk; }
    public void setTalk(String talk) { this.talk = talk; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
}