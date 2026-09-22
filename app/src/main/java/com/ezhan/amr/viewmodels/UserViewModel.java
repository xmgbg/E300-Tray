package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.data.datatype.User;
import com.ezhan.amr.data.datastore.DataStoreManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 用户管理ViewModel
 */
public class UserViewModel extends AndroidViewModel {
    private static final String TAG = "UserViewModel";

    private MutableLiveData<List<User>> userListLiveData;
    private List<User> userList;
    private int nextId = 1;
    private DataStoreManager dataStoreManager;
    private CompositeDisposable disposables;

    public UserViewModel(@NonNull Application application) {
        super(application);
        dataStoreManager = DataStoreManager.getInstance(application);
        disposables = new CompositeDisposable();
        // 先初始化LiveData，避免Fragment调用时为null
        userListLiveData = new MutableLiveData<>();
        userList = new ArrayList<>();
        loadUserList();
    }

    private void loadUserList() {
        disposables.add(
            dataStoreManager.getUserList()
                .subscribeOn(Schedulers.io())
                .subscribe(users -> {
                    if (users != null && !users.isEmpty()) {
                        userList.clear();
                        userList.addAll(users);
                        // 更新nextId为最大ID+1
                        for (User user : users) {
                            if (user.getId() >= nextId) {
                                nextId = user.getId() + 1;
                            }
                        }
                    } else {
                        // 如果没有数据，使用默认数据
                        initDefaultUsers();
                    }
                    userListLiveData.postValue(userList);
                    Log.i(TAG, "User list loaded: " + userList.size() + " users");
                }, error -> {
                    Log.e(TAG, "Failed to load user list", error);
                    // 加载失败时使用默认数据
                    initDefaultUsers();
                    userListLiveData.postValue(userList);
                })
        );
    }

    private void initDefaultUsers() {
        userList.clear();
        userList.add(new User(nextId++, "admin", "管理员", "管理部门", "1楼", "DemoOnly-654321", 2));
        userList.add(new User(nextId++, "super", "超级管理员", "管理部门", "1楼", "DemoOnly-654321", 9));
        userList.add(new User(nextId++, "user1", "张三", "技术部", "2楼", "DemoOnly-654321", 1));
    }

    /**
     * 获取用户列表
     */
    public LiveData<List<User>> getUserList() {
        return userListLiveData;
    }

    /**
     * 添加用户
     */
    public boolean addUser(User user) {
        // 检查用户ID是否已存在
        for (User existingUser : userList) {
            if (existingUser.getUserId().equals(user.getUserId())) {
                Log.e(TAG, "User ID already exists: " + user.getUserId());
                return false;
            }
        }

        user.setId(nextId++);
        userList.add(user);
        userListLiveData.postValue(userList);
        saveUserList();
        Log.i(TAG, "User added: " + user);
        return true;
    }

    /**
     * 更新用户
     */
    public boolean updateUser(User user) {
        for (int i = 0; i < userList.size(); i++) {
            if (userList.get(i).getId() == user.getId()) {
                // 检查用户ID是否与其他用户冲突
                for (int j = 0; j < userList.size(); j++) {
                    if (j != i && userList.get(j).getUserId().equals(user.getUserId())) {
                        Log.e(TAG, "User ID already exists: " + user.getUserId());
                        return false;
                    }
                }

                userList.set(i, user);
                userListLiveData.postValue(userList);
                saveUserList();
                Log.i(TAG, "User updated: " + user);
                return true;
            }
        }
        Log.e(TAG, "User not found: " + user.getId());
        return false;
    }

    /**
     * 删除用户
     */
    public boolean deleteUser(int userId) {
        for (int i = 0; i < userList.size(); i++) {
            if (userList.get(i).getId() == userId) {
                User removedUser = userList.remove(i);
                userListLiveData.postValue(userList);
                saveUserList();
                Log.i(TAG, "User deleted: " + removedUser);
                return true;
            }
        }
        Log.e(TAG, "User not found: " + userId);
        return false;
    }

    /**
     * 根据ID获取用户
     */
    public User getUserById(int id) {
        for (User user : userList) {
            if (user.getId() == id) {
                return user;
            }
        }
        return null;
    }

    /**
     * 验证用户信息
     */
    public boolean validateUser(User user) {
        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            return false;
        }
        if (user.getName() == null || user.getName().isEmpty()) {
            return false;
        }
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            return false;
        }
        if (user.getUserType() != 1 && user.getUserType() != 2 && user.getUserType() != 9) {
            return false;
        }
        return true;
    }

    /**
     * 保存用户列表到DataStore
     */
    private void saveUserList() {
        dataStoreManager.saveUserList(userList);
        Log.i(TAG, "User list saved: " + userList.size() + " users");
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (disposables != null) {
            disposables.clear();
        }
    }
}