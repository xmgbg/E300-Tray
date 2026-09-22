package com.ezhan.amr.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.ClipData;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.JackOperation;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.utils.AndroidBug5497Workaround;
import com.ezhan.amr.viewmodels.JackViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class EditJackActivity extends BaseActivity {
    private static final int INFINITE_JACK_LOOP_COUNT = -1;
    private static final int MIN_JACK_LOOP_COUNT = 1;
    private static final int MAX_JACK_LOOP_COUNT = 999;
    private static final int MIN_JACK_STAY_DURATION = 0;
    private static final int MAX_JACK_STAY_DURATION = 86400;
    private MapViewModel mapViewModel;
    private JackViewModel jackViewModel;
    private JackTask currentTask;
    private Boolean isFirstLoad = true;
    private EditText etJackLoopCount;
    private static final int FLOOR_TAG = R.id.floor_tag; // You'll need to add this ID in ids.xml
    private boolean isDragging = false;
    private View draggedView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置窗口背景为白色，避免Splash背景闪烁
        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.white);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit_jack);
        setupFullScreen();
        AndroidBug5497Workaround.assistActivity(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        jackViewModel = new ViewModelProvider(this).get(JackViewModel.class);
        currentTask = (JackTask) getIntent().getSerializableExtra("TASK_DATA");
        setupObservers();
        initOperationList();
        initViews();
        setupDragAndDrop();

        // ADD THIS: Initial data load
        refreshStationData();

        // 防止Activity启动时自动弹出软键盘
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setupHideKeyboardOnTouch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh data when activity comes to foreground
        refreshStationData();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Ensure we have the latest data
        Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
        if (latestData != null) {
            createStationButtons(latestData);
        }
    }

    private void setupObservers() {
        mapViewModel.getCurrentMapPoints().observe(this, mapPoints -> {
            if (mapPoints != null) {
                Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
                if (latestData != null && !latestData.isEmpty()) {
                    createStationButtons(latestData);
                } else {
                    showNoData();
                }
            } else {
                showNoData();
            }
        });
    }

    // Add this helper method
    private void refreshStationData() {
        Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
        if (latestData != null && !latestData.isEmpty()) {
            createStationButtons(latestData);
        } else {
            showNoData();
        }
    }

    private void showNoData() {
        runOnUiThread(() -> {
            findViewById(R.id.ivNoDataBackground).setVisibility(View.VISIBLE);
            findViewById(R.id.tvNoData).setVisibility(View.VISIBLE);
            findViewById(R.id.pointListContainer).setVisibility(View.GONE);

            // Also clear any existing buttons
            LinearLayout pointListContainer = findViewById(R.id.pointListContainer);
            pointListContainer.removeAllViews();
        });
    }

    private void createStationButtons(Map<Integer, List<Position>> positionMap) {
        LinearLayout pointListContainer = findViewById(R.id.pointListContainer);
        pointListContainer.removeAllViews();

        ImageView ivNoDataBackground = findViewById(R.id.ivNoDataBackground);
        TextView tvNoData = findViewById(R.id.tvNoData);

        // Enhanced validation
        if (positionMap == null || positionMap.isEmpty()) {
            ivNoDataBackground.setVisibility(View.VISIBLE);
            tvNoData.setVisibility(View.VISIBLE);
            pointListContainer.setVisibility(View.GONE);
            return;
        } else {
            ivNoDataBackground.setVisibility(View.GONE);
            tvNoData.setVisibility(View.GONE);
            pointListContainer.setVisibility(View.VISIBLE);
        }

        // Check if we actually have any positions across all floors
        boolean hasPositions = false;
        for (List<Position> positions : positionMap.values()) {
            if (positions != null && !positions.isEmpty()) {
                hasPositions = true;
                break;
            }
        }

        if (!hasPositions) {
            showNoData();
            return;
        }

        // 按楼层排序
        List<Integer> floors = new ArrayList<>(positionMap.keySet());
        Collections.sort(floors);

        int spacing = getResources().getDimensionPixelSize(R.dimen.station_button_spacing);

        for (int floor : floors) {
            List<Position> positions = positionMap.get(floor);
            if (positions == null || positions.isEmpty()) continue;

            // 添加楼层标题
            TextView floorTitle = new TextView(this);
            floorTitle.setText(getString(R.string.floor_title, floor));
            floorTitle.setTextSize(18);
            floorTitle.setTextColor(getResources().getColor(R.color.colorPrimary));
            floorTitle.setPadding(0, spacing, 0, spacing/2);

            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            floorTitle.setLayoutParams(titleParams);
            pointListContainer.addView(floorTitle);

            // 添加该楼层的站点
            Collections.sort(positions, Comparator.comparing(Position::getId));

            for (Position position : positions) {
                Button stationButton = new Button(this);
                stationButton.setText(position.getName());
                stationButton.setTag(position);

                stationButton.setBackgroundResource(R.drawable.bg_station_button);
                stationButton.setTextColor(getResources().getColor(R.color.white));
                stationButton.setTextSize(18);
                stationButton.setAllCaps(false);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, 0, 0, spacing);
                stationButton.setLayoutParams(params);

                stationButton.setOnClickListener(v -> {
                    addOperationToTask(position, floor);
                });

                pointListContainer.addView(stationButton);
            }
        }
    }

    private void addOperationToTask(Position position, int floor) {
        int operationType = 3;   // Use default action type

        // Get container
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        ImageView ivNoDataBackground1 = findViewById(R.id.ivNoDataBackground1);
        TextView tvNoData1 = findViewById(R.id.tvNoData1);

        // Hide right background
        ivNoDataBackground1.setVisibility(View.GONE);
        tvNoData1.setVisibility(View.GONE);

        // Create operation item view
        View operationView = LayoutInflater.from(this).inflate(R.layout.item_operation, operationsContainer, false);

        // Set point name
        TextView tvPointName = operationView.findViewById(R.id.tvPointName);
        tvPointName.setText(position.getName());
        tvPointName.setTag(position.getId());

        // Store floor information
        operationView.setTag(R.id.floor_tag, floor);

        // Set operation type spinner
        Spinner itemSpOperationType = operationView.findViewById(R.id.spOperationType);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.operation_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        itemSpOperationType.setAdapter(adapter);
        itemSpOperationType.setSelection(operationType);

        EditText etDuration = operationView.findViewById(R.id.etDuration);
        setupJackStayDurationInput(etDuration);

        // Set delete button
        ImageButton btnDelete = operationView.findViewById(R.id.btnDelete);
        btnDelete.setOnClickListener(v -> showDeleteConfirmationDialog(operationView));

        setupOperationItemDrag(operationView);

        // Set layout parameters
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.operation_item_spacing);
        operationView.setLayoutParams(params);

        // Add to container
        operationsContainer.addView(operationView);
    }

    private void checkOperationsVisibility() {
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        ImageView ivNoDataBackground1 = findViewById(R.id.ivNoDataBackground1);
        TextView tvNoData1 =findViewById(R.id.tvNoData1);

        // 根据操作项数量决定背景图可见性
        if (operationsContainer.getChildCount() == 0) {
            ivNoDataBackground1.setVisibility(View.VISIBLE);
            tvNoData1.setVisibility(View.VISIBLE);
        } else {
            ivNoDataBackground1.setVisibility(View.GONE);
            tvNoData1.setVisibility(View.GONE);
        }
    }

    private void updateOperationList() {
        // 实现更新操作列表UI的逻辑
        // 例如: operationAdapter.notifyDataSetChanged();
    }

    private void initViews() {
        etJackLoopCount = findViewById(R.id.etJackLoopCount);
        setupJackLoopCountInput(etJackLoopCount);
        if (currentTask != null && etJackLoopCount != null) {
            etJackLoopCount.setText(String.valueOf(currentTask.getLoopCount()));
        }

        Button btnSave = findViewById(R.id.btnSaveRoute);
        btnSave.setOnClickListener(v -> saveChanges());
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        if (currentTask != null && currentTask.getOperations() != null) {
            for (JackOperation operation : currentTask.getOperations()) {
                addOperationToView(operation);
            }
        }
        // 初始化后检查操作列表可见性
        checkOperationsVisibility();
    }

    private void setupJackLoopCountInput(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder proposedValue = new StringBuilder(dest);
            proposedValue.replace(dstart, dend, source.subSequence(start, end).toString());
            return isAllowedJackLoopCountInput(proposedValue.toString()) ? null : "";
        }});
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeJackLoopCountInput(editText);
            }
        });
    }

    private boolean isAllowedJackLoopCountInput(String value) {
        if (value.isEmpty() || "-".equals(value)) {
            return true;
        }
        if (String.valueOf(INFINITE_JACK_LOOP_COUNT).equals(value)) {
            return true;
        }
        if (value.startsWith("-") || value.length() > 3) {
            return false;
        }
        try {
            int count = Integer.parseInt(value);
            return count >= MIN_JACK_LOOP_COUNT && count <= MAX_JACK_LOOP_COUNT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int normalizeJackLoopCountInput(EditText editText) {
        int count = getValidJackLoopCount(editText == null ? "" : editText.getText().toString());
        if (editText != null) {
            String normalizedValue = String.valueOf(count);
            if (!normalizedValue.contentEquals(editText.getText())) {
                editText.setText(normalizedValue);
                editText.setSelection(normalizedValue.length());
            }
        }
        return count;
    }

    private int getValidJackLoopCount(String value) {
        try {
            int count = Integer.parseInt(value.trim());
            if (count == INFINITE_JACK_LOOP_COUNT || (count >= MIN_JACK_LOOP_COUNT && count <= MAX_JACK_LOOP_COUNT)) {
                return count;
            }
        } catch (NumberFormatException ignored) {
        }
        return MIN_JACK_LOOP_COUNT;
    }

    private void setupJackStayDurationInput(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder proposedValue = new StringBuilder(dest);
            proposedValue.replace(dstart, dend, source.subSequence(start, end).toString());
            return isAllowedJackStayDurationInput(proposedValue.toString()) ? null : "";
        }});
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeJackStayDurationInput((EditText) v);
                hideKeyboard();
            }
        });
    }

    private boolean isAllowedJackStayDurationInput(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (!value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (value.length() > String.valueOf(MAX_JACK_STAY_DURATION).length()) {
            return false;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed <= MAX_JACK_STAY_DURATION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int normalizeJackStayDurationInput(EditText editText) {
        int duration = getValidJackStayDuration(editText == null ? "" : editText.getText().toString());
        if (editText != null) {
            String normalizedValue = String.valueOf(duration);
            if (!normalizedValue.contentEquals(editText.getText())) {
                editText.setText(normalizedValue);
                editText.setSelection(normalizedValue.length());
            }
        }
        return duration;
    }

    private int getValidJackStayDuration(String value) {
        if (value == null || value.trim().isEmpty()) {
            return MIN_JACK_STAY_DURATION;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < MIN_JACK_STAY_DURATION) {
                return MIN_JACK_STAY_DURATION;
            }
            if (parsed > MAX_JACK_STAY_DURATION) {
                return MAX_JACK_STAY_DURATION;
            }
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return MIN_JACK_STAY_DURATION;
        }
    }

    private boolean validateAllStayDurations(LinearLayout operationsContainer) {
        for (int i = 0; i < operationsContainer.getChildCount(); i++) {
            View operationView = operationsContainer.getChildAt(i);
            EditText etDuration = operationView.findViewById(R.id.etDuration);
            if (etDuration == null) {
                continue;
            }
            String raw = etDuration.getText().toString().trim();
            if (raw.isEmpty()) {
                continue;
            }
            try {
                long parsed = Long.parseLong(raw);
                if (parsed < MIN_JACK_STAY_DURATION || parsed > MAX_JACK_STAY_DURATION) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void saveChanges() {
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        if (!validateAllStayDurations(operationsContainer)) {
            Toast.makeText(this, R.string.jack_stay_duration_range_error, Toast.LENGTH_SHORT).show();
            return;
        }

        List<JackOperation> currentOperationList = new ArrayList<>();

        Log.d("TaskDebug", "========== saveChanges: Starting to save task operations ==========");

        for (int i = 0; i < operationsContainer.getChildCount(); i++) {
            View operationView = operationsContainer.getChildAt(i);
            EditText etDuration = operationView.findViewById(R.id.etDuration);
            Spinner spOperationType = operationView.findViewById(R.id.spOperationType);
            TextView tvPointName = operationView.findViewById(R.id.tvPointName);

            int duration = getValidJackStayDuration(etDuration.getText().toString());

            // 获取楼层信息，默认值为1
            int floor = 1;
            Object floorTag = operationView.getTag(R.id.floor_tag);
            if (floorTag instanceof Integer) {
                floor = (Integer) floorTag;
            }

            Integer pointId = null;
            Object positionIdTag = tvPointName.getTag();
            if (positionIdTag instanceof Integer) {
                pointId = (Integer) positionIdTag;
            }

            // 增强日志：显示保存的详细信息
            Log.d("TaskDebug", String.format("  [%d] Saving operation: name='%s', pointId=%s, floor=%d, actionType=%d, duration=%d",
                    i, tvPointName.getText().toString(), pointId, floor, 
                    spOperationType.getSelectedItemPosition(), duration));

            // 创建包含楼层信息的JackOperation
            currentOperationList.add(new JackOperation(
                    tvPointName.getText().toString(),
                    pointId,
                    spOperationType.getSelectedItemPosition(),
                    duration,
                    floor
            ));
        }

        // 新增调试日志
        Log.d("TaskDebug", "========== saveChanges: Task operations saved ==========");
        Log.d("TaskDebug", "Total operations: " + currentOperationList.size());
        for (int i = 0; i < currentOperationList.size(); i++) {
            JackOperation op = currentOperationList.get(i);
            Log.d("TaskDebug", String.format("  [%d] name='%s', pointId=%s, floor=%d", 
                    i, op.getPointName(), op.getPointId(), op.getFloor()));
        }

        // 更新当前任务的操作为新列表
        currentTask.setOperations(currentOperationList);
        currentTask.setLoopCount(normalizeJackLoopCountInput(etJackLoopCount));

        // 保存到ViewModel
        jackViewModel.saveJackTaskMap(currentTask);

        // 保存后检查操作列表可见性
        checkOperationsVisibility();

        // 安全关闭 Activity
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        });
    }

    private void initOperationList() {

    }
    private void setupHideKeyboardOnTouch() {
        // 获取根布局
        View rootView = findViewById(android.R.id.content);

        rootView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard();
                    clearFocusFromEditTexts();
                    return true;
                }
                return false;
            }
        });

        // 为操作列表容器也设置触摸监听
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        operationsContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard();
                    clearFocusFromEditTexts();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 清除所有EditText的焦点
     */
    private void clearFocusFromEditTexts() {
        // 清除当前焦点
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
        }

        // 清除所有操作项中的EditText焦点
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        for (int i = 0; i < operationsContainer.getChildCount(); i++) {
            View operationView = operationsContainer.getChildAt(i);
            EditText etDuration = operationView.findViewById(R.id.etDuration);
            if (etDuration != null) {
                etDuration.clearFocus();
            }
        }
    }

    /**
     * 隐藏软键盘
     */
    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);

            // 额外确保键盘隐藏
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        } else {
            // 如果没有焦点视图，尝试隐藏任何可能显示的键盘
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
    }

    private void addOperationToView(JackOperation operation) {
        // 获取容器
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        ImageView ivNoDataBackground1 = findViewById(R.id.ivNoDataBackground1);
        TextView tvNoData1 = findViewById(R.id.tvNoData1);

        // 隐藏右侧背景图（因为即将添加操作）
        ivNoDataBackground1.setVisibility(View.GONE);
        tvNoData1.setVisibility(View.GONE);

        // 创建操作项视图
        View operationView = LayoutInflater.from(this).inflate(R.layout.item_operation, operationsContainer, false);

        // 设置点位名称
        TextView tvPointName = operationView.findViewById(R.id.tvPointName);
        tvPointName.setText(operation.getPointName());

        // ========== 关键修复：从地图查找最新的pointId，而不是直接使用旧的pointId ==========
        Integer actualPointId = findActualPointIdByName(operation.getPointName(), operation.getFloor());
        if (actualPointId != null) {
            tvPointName.setTag(actualPointId);
            Log.d("TaskDebug", "✅ Updated pointId for '" + operation.getPointName() + 
                  "': oldId=" + operation.getPointId() + ", newId=" + actualPointId);
        } else {
            // 如果找不到，使用原始ID（可能点位已被删除）
            tvPointName.setTag(operation.getPointId());
            Log.w("TaskDebug", "⚠️ Could not find point '" + operation.getPointName() + 
                  "' on floor " + operation.getFloor() + ", using original pointId=" + operation.getPointId());
        }
        // ========== 修复结束 ==========

        // 存储楼层信息
        operationView.setTag(R.id.floor_tag, operation.getFloor());

        // 设置操作类型选择器
        Spinner itemSpOperationType = operationView.findViewById(R.id.spOperationType);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.operation_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        itemSpOperationType.setAdapter(adapter);

        // 设置选择（防御性边界检查，避免 actionType 超出 adapter 范围导致崩溃）
        int actionType = operation.getActionType();
        if (actionType < 0 || actionType >= adapter.getCount()) {
            actionType = 0;
        }
        itemSpOperationType.setSelection(actionType);

        // 设置停留时间
        EditText etDuration = operationView.findViewById(R.id.etDuration);
        setupJackStayDurationInput(etDuration);
        etDuration.setText(String.valueOf(getValidJackStayDuration(String.valueOf(operation.getDuration()))));

        // 设置删除按钮
        ImageButton btnDelete = operationView.findViewById(R.id.btnDelete);
        btnDelete.setOnClickListener(v -> showDeleteConfirmationDialog(operationView));

        setupOperationItemDrag(operationView);

        // 设置布局参数
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.operation_item_spacing);
        operationView.setLayoutParams(params);

        // 添加到容器
        operationsContainer.addView(operationView);
    }

    /**
     * 从地图数据中查找点位名称对应的最新pointId
     * 这是解决pointId不匹配问题的关键方法
     */
    private Integer findActualPointIdByName(String pointName, int floor) {
        if (pointName == null || pointName.isEmpty()) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: pointName is null or empty");
            return null;
        }

        if (mapViewModel == null) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: mapViewModel is null");
            return null;
        }

        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: mapPoints is null");
            return null;
        }

        String currentBuildingId = mapViewModel.getCurrentBuilding();
        Building currentBuilding = mapPoints.getBuilding(currentBuildingId);
        if (currentBuilding == null) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: Building '" + currentBuildingId + "' not found");
            return null;
        }

        FloorPoints floorPoints = currentBuilding.getFloorPoints(floor);
        if (floorPoints == null) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: Floor " + floor + " not found in building '" + currentBuildingId + "'");
            return null;
        }

        List<Position> workPoints = floorPoints.getWorkPoints();
        if (workPoints == null || workPoints.isEmpty()) {
            Log.e("TaskDebug", "❌ findActualPointIdByName: No work points on floor " + floor);
            return null;
        }

        // 通过点位名称查找最新的pointId
        for (Position position : workPoints) {
            if (position.getName() != null && position.getName().equals(pointName)) {
                Log.d("TaskDebug", "✅ Found point '" + pointName + "' on floor " + floor + 
                      " with actual pointId=" + position.getId());
                return position.getId();
            }
        }

        Log.w("TaskDebug", "⚠️ Point '" + pointName + "' not found on floor " + floor);
        return null;
    }

    private void showDeleteConfirmationDialog(View operationView) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.editjack_delete_dialog, null);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
            operationsContainer.removeView(operationView);
            checkOperationsVisibility();
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showAddPointDialog() {
        // 实现添加点位弹窗逻辑
    }

    /**
     * 设置全屏沉浸式模式
     */
    private void setupFullScreen() {
        // 设置内容延伸到系统栏
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 获取窗口控制器
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (windowInsetsController != null) {
            // 隐藏状态栏和导航栏
            windowInsetsController.hide(
                    WindowInsetsCompat.Type.systemBars() |
                            WindowInsetsCompat.Type.navigationBars()
            );
            // 设置沉浸式行为
            windowInsetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        // 兼容旧版本API的全屏设置
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }

        // 添加窗口焦点变化监听
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                setupFullScreen(); // 如果系统栏显示，重新隐藏
            }
        });
    }

    /**
     * 设置操作项的长按拖拽功能。
     */
    private void setupDragAndDrop() {
        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        if (operationsContainer == null) {
            return;
        }

        operationsContainer.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() == draggedView;

                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setBackgroundColor(getResources().getColor(R.color.bg_tab_indicator));
                    return true;

                case DragEvent.ACTION_DRAG_EXITED:
                    v.setBackgroundColor(getResources().getColor(android.R.color.transparent));
                    return true;

                case DragEvent.ACTION_DRAG_LOCATION:
                    return true;

                case DragEvent.ACTION_DROP:
                    v.setBackgroundColor(getResources().getColor(android.R.color.transparent));
                    handleDropPosition(event);
                    return true;

                case DragEvent.ACTION_DRAG_ENDED:
                    v.setBackgroundColor(getResources().getColor(android.R.color.transparent));
                    resetDragState();
                    return true;

                default:
                    return false;
            }
        });
    }

    private void setupOperationItemDrag(View operationView) {
        operationView.setOnLongClickListener(v -> {
            if (isDragging) {
                return true;
            }

            hideKeyboard();
            clearFocusFromEditTexts();
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            startDragOperation(v);
            return true;
        });
    }

    private void startDragOperation(View view) {
        if (view == null || isDragging) {
            return;
        }

        draggedView = view;
        isDragging = true;
        draggedView.setAlpha(0.7f);

        ClipData data = ClipData.newPlainText("operation_item", "");
        View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(draggedView) {
            @Override
            public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
                super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint);
                outShadowTouchPoint.set(outShadowSize.x / 2, outShadowSize.y / 2);
            }
        };

        boolean dragStarted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dragStarted = draggedView.startDragAndDrop(data, shadowBuilder, draggedView, 0);
        } else {
            dragStarted = draggedView.startDrag(data, shadowBuilder, draggedView, 0);
        }

        if (!dragStarted) {
            resetDragState();
        }
    }

    private void handleDropPosition(DragEvent event) {
        if (draggedView == null) {
            return;
        }

        LinearLayout operationsContainer = findViewById(R.id.operationsContainer);
        int originalIndex = operationsContainer.indexOfChild(draggedView);
        int targetIndex = operationsContainer.getChildCount();
        float dropY = event.getY();

        for (int i = 0; i < operationsContainer.getChildCount(); i++) {
            View child = operationsContainer.getChildAt(i);
            if (child == draggedView) {
                continue;
            }

            float childCenter = child.getTop() + (child.getBottom() - child.getTop()) / 2f;
            if (dropY < childCenter) {
                targetIndex = i;
                break;
            }
        }

        if (originalIndex >= 0 && originalIndex < targetIndex) {
            targetIndex--;
        }

        View movedView = draggedView;
        operationsContainer.removeView(movedView);
        if (targetIndex >= operationsContainer.getChildCount()) {
            operationsContainer.addView(movedView);
        } else {
            operationsContainer.addView(movedView, Math.max(0, targetIndex));
        }

        showDragSuccessFeedback(movedView);
    }

    private void showDragSuccessFeedback(View movedView) {
        movedView.setBackgroundColor(getResources().getColor(R.color.bg_tab_indicator));
        movedView.postDelayed(() ->
                movedView.setBackgroundColor(getResources().getColor(android.R.color.transparent)), 300);
    }

    private void resetDragState() {
        if (draggedView != null) {
            draggedView.setAlpha(1.0f);
            draggedView = null;
        }
        isDragging = false;
    }
}
