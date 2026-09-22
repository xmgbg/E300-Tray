package com.ezhan.amr.ui.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.User;
import com.ezhan.amr.viewmodels.UserViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理设置Fragment
 */
public class UserManagementFragment extends Fragment {
    private UserViewModel userViewModel;
    private RecyclerView userRecyclerView;
    private UserAdapter userAdapter;
    private List<User> userList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_user_management, container, false);
        
        // 初始化ViewModel
        userViewModel = new ViewModelProvider(this).get(UserViewModel.class);
        
        // 初始化RecyclerView
        userRecyclerView = view.findViewById(R.id.user_recycler_view);
        userRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        userAdapter = new UserAdapter(new ArrayList<>());
        userRecyclerView.setAdapter(userAdapter);
        
        // 观察用户列表
        userViewModel.getUserList().observe(getViewLifecycleOwner(), users -> {
            userList = users;
            userAdapter.setUserList(users);
        });
        
        // 新增用户按钮
        Button addUserButton = view.findViewById(R.id.add_user_button);
        addUserButton.setOnClickListener(v -> showAddUserDialog());
        
        return view;
    }

    /**
     * 显示新增用户对话框
     */
    private void showAddUserDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.user_add_user_title);
        
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
        builder.setView(dialogView);
        
        EditText userIdEditText = dialogView.findViewById(R.id.user_id_edit_text);
        EditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        EditText departmentEditText = dialogView.findViewById(R.id.department_edit_text);
        EditText floorEditText = dialogView.findViewById(R.id.floor_edit_text);
        EditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        Spinner userTypeSpinner = dialogView.findViewById(R.id.user_type_spinner);
        
        // 设置用户类型选项
        String[] userTypes = getResources().getStringArray(R.array.user_type_array);
        ArrayAdapter<String> userTypeAdapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, userTypes);
        userTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userTypeSpinner.setAdapter(userTypeAdapter);
        
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String userId = userIdEditText.getText().toString().trim();
            String name = nameEditText.getText().toString().trim();
            String department = departmentEditText.getText().toString().trim();
            String floor = floorEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString().trim();
            int userType = userTypeSpinner.getSelectedItemPosition() + 1;
            if (userType == 3) userType = 9; // 超级管理员
            
            if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(name) || TextUtils.isEmpty(password)) {
                showAlert(getString(R.string.user_error), getString(R.string.user_fields_required));
                return;
            }
            
            User user = new User(0, userId, name, department, floor, password, userType);
            if (userViewModel.addUser(user)) {
                showAlert(getString(R.string.user_success), getString(R.string.user_add_success));
            } else {
                showAlert(getString(R.string.user_error), getString(R.string.user_id_exists));
            }
        });
        
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * 显示编辑用户对话框
     */
    private void showEditUserDialog(User user) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.user_edit_user_title);
        
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
        builder.setView(dialogView);
        
        EditText userIdEditText = dialogView.findViewById(R.id.user_id_edit_text);
        EditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        EditText departmentEditText = dialogView.findViewById(R.id.department_edit_text);
        EditText floorEditText = dialogView.findViewById(R.id.floor_edit_text);
        EditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        Spinner userTypeSpinner = dialogView.findViewById(R.id.user_type_spinner);
        
        // 设置用户类型选项
        String[] userTypes2 = getResources().getStringArray(R.array.user_type_array);
        ArrayAdapter<String> userTypeAdapter2 = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, userTypes2);
        userTypeAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userTypeSpinner.setAdapter(userTypeAdapter2);
        
        // 填充现有数据
        userIdEditText.setText(user.getUserId());
        nameEditText.setText(user.getName());
        departmentEditText.setText(user.getDepartment());
        floorEditText.setText(user.getFloor());
        passwordEditText.setText(user.getPassword());
        
        // 设置用户类型
        int userTypePosition = 0;
        switch (user.getUserType()) {
            case 1:
                userTypePosition = 0;
                break;
            case 2:
                userTypePosition = 1;
                break;
            case 9:
                userTypePosition = 2;
                break;
        }
        userTypeSpinner.setSelection(userTypePosition);
        
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String userId = userIdEditText.getText().toString().trim();
            String name = nameEditText.getText().toString().trim();
            String department = departmentEditText.getText().toString().trim();
            String floor = floorEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString().trim();
            int userType = userTypeSpinner.getSelectedItemPosition() + 1;
            if (userType == 3) userType = 9; // 超级管理员
            
            if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(name) || TextUtils.isEmpty(password)) {
                showAlert(getString(R.string.user_error), getString(R.string.user_fields_required));
                return;
            }
            
            User updatedUser = new User(user.getId(), userId, name, department, floor, password, userType);
            if (userViewModel.updateUser(updatedUser)) {
                showAlert(getString(R.string.user_success), getString(R.string.user_edit_success));
            } else {
                showAlert(getString(R.string.user_error), getString(R.string.user_id_exists));
            }
        });
        
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * 显示删除确认对话框
     */
    private void showDeleteConfirmDialog(User user) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.user_confirm_delete);
        builder.setMessage(getString(R.string.user_confirm_delete_message, user.getName()));
        
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            if (userViewModel.deleteUser(user.getId())) {
                showAlert(getString(R.string.user_success), getString(R.string.user_delete_success));
            } else {
                showAlert(getString(R.string.user_error), getString(R.string.user_delete_failed_not_exist));
            }
        });
        
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * 显示提示对话框
     */
    private void showAlert(String title, String message) {
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * 用户列表适配器
     */
    private class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserViewHolder> {
        private List<User> userList;

        public UserAdapter(List<User> userList) {
            this.userList = userList;
        }

        public void setUserList(List<User> userList) {
            this.userList = userList;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user, parent, false);
            return new UserViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
            User user = userList.get(position);
            holder.bind(user);
        }

        @Override
        public int getItemCount() {
            return userList.size();
        }

        class UserViewHolder extends RecyclerView.ViewHolder {
            private TextView idTextView;
            private TextView userIdTextView;
            private TextView nameTextView;
            private TextView departmentTextView;
            private TextView floorTextView;
            private TextView userTypeTextView;
            private Button editButton;
            private Button deleteButton;

            public UserViewHolder(@NonNull View itemView) {
                super(itemView);
                idTextView = itemView.findViewById(R.id.user_id_text);
                userIdTextView = itemView.findViewById(R.id.user_user_id_text);
                nameTextView = itemView.findViewById(R.id.user_name_text);
                departmentTextView = itemView.findViewById(R.id.user_department_text);
                floorTextView = itemView.findViewById(R.id.user_floor_text);
                userTypeTextView = itemView.findViewById(R.id.user_type_text);
                editButton = itemView.findViewById(R.id.edit_button);
                deleteButton = itemView.findViewById(R.id.delete_button);
            }

            public void bind(User user) {
                idTextView.setText(String.valueOf(user.getId()));
                userIdTextView.setText(user.getUserId());
                nameTextView.setText(user.getName());
                departmentTextView.setText(user.getDepartment());
                floorTextView.setText(user.getFloor());
                userTypeTextView.setText(user.getUserTypeString(itemView.getContext()));
                
                editButton.setOnClickListener(v -> showEditUserDialog(user));
                deleteButton.setOnClickListener(v -> showDeleteConfirmDialog(user));
            }
        }
    }
}