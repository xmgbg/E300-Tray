package com.ezhan.amr.data.datatype;

import android.content.Context;

import com.ezhan.amr.R;

import java.io.Serializable;

/**
 * 用户数据模型
 */
public class User implements Serializable {
    private int id;
    private String userId;
    private String name;
    private String department;
    private String floor;
    private String password;
    private int userType; // 1-操作人员，2-管理员，9-超级管理员

    public User() {
    }

    public User(int id, String userId, String name, String department, String floor, String password, int userType) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.department = department;
        this.floor = floor;
        this.password = password;
        this.userType = userType;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getFloor() {
        return floor;
    }

    public void setFloor(String floor) {
        this.floor = floor;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getUserType() {
        return userType;
    }

    public void setUserType(int userType) {
        this.userType = userType;
    }

    public String getUserTypeString() {
        return getUserTypeString(null);
    }

    public String getUserTypeString(Context context) {
        if (context == null) {
            switch (userType) {
                case 1:
                    return "操作人员";
                case 2:
                    return "管理员";
                case 9:
                    return "超级管理员";
                default:
                    return "未知";
            }
        }
        switch (userType) {
            case 1:
                return context.getString(R.string.user_type_operator);
            case 2:
                return context.getString(R.string.user_type_admin);
            case 9:
                return context.getString(R.string.user_type_super_admin);
            default:
                return context.getString(R.string.user_type_unknown);
        }
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", name='" + name + '\'' +
                ", department='" + department + '\'' +
                ", floor='" + floor + '\'' +
                ", userType=" + getUserTypeString() +
                '}';
    }
}