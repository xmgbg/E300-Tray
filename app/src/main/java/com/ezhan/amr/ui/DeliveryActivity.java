package com.ezhan.amr.ui;

import static com.ezhan.amr.ui.MainActivity.DEFAULT_CONFIDENCE;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.MapPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.BatteryLevelManager;
import com.ezhan.amr.navigation.BatteryState;
import com.ezhan.amr.navigation.GeneralNavigationHandler;
import com.ezhan.amr.utils.GifTypeConstants;
import com.ezhan.amr.utils.VoiceKeyConstants;
import com.ezhan.amr.viewmodels.DeliveryViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配送任务管理Activity，实现站点选择、任务派发和状态监控功能
 */
public class DeliveryActivity extends BaseNavigationActivity {
    private String TAG = "DeliveryActivity";
    private DeliveryViewModel deliveryViewModel;
    private MapViewModel mapViewModel;
    private GeneralNavigationHandler navigationHandler;

    // 修改数据结构：使用楼层到站点列表的映射
    private Map<Integer, List<Position>> floorPositionsMap = new HashMap<>();
    private LinearLayout stationContainer; // 替换原来的stationGrid
    private Set<String> selectedStationIndices = new LinkedHashSet<>(); // 修改为String类型
    private long lastTouchTime = 0;
    private Button[] stationButtons;
    private Map<String, Integer> stationOrderMap = new HashMap<>(); // 修改为String类型，存储站点序号

    /**
     * Activity创建时初始化
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery);
        navigationHandler = getNavigationHandler();
        setupViews();
        setupObservers();
        setupUIListeners();

        // ADD THIS: Initial data load
        refreshStationData();

        int taskId = getIntent().getIntExtra("task_id", -1);
        if (taskId != -1) {
            // 根据任务ID执行任务
            executeDeliveryByTaskId(taskId);
        }

        List<Position> deliveryTask = (List<Position>) getIntent().getSerializableExtra("task_object");
        if (deliveryTask != null) {
            // 根据任务ID执行任务
            executeDeliveryByTaskObject(deliveryTask);
        }
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

    /**
     * Activity销毁时清理资源
     */
    @Override
    protected void onDestroy() {
        //clear all selected button status
        clearAllSelections();

        // Stop any GIF playback first
        onGifPlayback(false, GifTypeConstants.NAVIGATING_NORMAL, "");

        // Clean up dialogs
        if (pauseDialog != null && pauseDialog.isShowing()) {
            pauseDialog.dismiss();
            pauseDialog = null;
        }

        // Stop music
        if (musicPlayer != null) {
            musicPlayer.stop();
            musicPlayer.setListener(null);
        }

        // Clean up TTS
        if (ttsHelper != null) {
            ttsHelper.shutdown();
        }

        // Clean up navigation handler
        if (navigationHandler != null) {
            navigationHandler.cleanup(false);
        }

        // 8. Clear view references BEFORE parent cleanup
        gifView = null;
        stationContainer = null;
        stationButtons = null;

        // Let parent handle its cleanup (including Glide)
        super.onDestroy();
    }

    /**
     * 初始化视图组件
     */
    private void setupViews() {
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        deliveryViewModel = new ViewModelProvider(this).get(DeliveryViewModel.class);
        stationContainer = findViewById(R.id.stationContainer); // 修改布局ID
        // 初始化GIF视图
        gifView = findViewById(R.id.gifView);
        gifView.setVisibility(View.GONE);
        gifView.setScaleType(ImageView.ScaleType.CENTER_CROP);  // 修改自适应类型


        gifView.setOnTouchListener((v, event) -> {
            // Debounce rapid touches
            if (System.currentTimeMillis() - lastTouchTime < 300) {
                return true;
            }
            lastTouchTime = System.currentTimeMillis();

            if (navigationHandler.isTaskRunning() && navigationHandler.isGifPlaying()) {
                // Disable further touches while dialog is showing
                gifView.setClickable(false);
                pauseAndShowDialog();
                return true;
            }
            return false;
        });
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        tvNavigationStatus = findViewById(R.id.tvNavigationStatus);
    }

    /**
     * 设置数据观察者
     */
    private void setupObservers() {
        Log.d(TAG, "setupObservers - Setting up observers");

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
        Log.d(TAG, "refreshStationData - Refreshing station data");
        Map<Integer, List<Position>> latestData = mapViewModel.getAllFloorPositionsFromMapPoints();
        Log.d(TAG, "refreshStationData - Latest data: " +
                (latestData != null ? latestData.size() + " floors" : "null"));

        if (latestData != null) {
            floorPositionsMap = latestData;
            createStationButtons(latestData);
        } else {
            Log.w(TAG, "refreshStationData - No data available, showing no data");
            showNoData();
        }
    }

    private void setupUIListeners() {
        // 开始任务按钮
        MaterialButton btnStartTask = findViewById(R.id.btnStartTask);
        btnStartTask.setOnClickListener(v -> {
            // 按钮防抖
            if (isButtonClickDebounced()) {
                return;
            }
            List<Position> selectedPositions = getSelectedPositions();
            if (selectedPositions.isEmpty()) {
                onShowToast(R.string.select_at_least_one_station);
            } else {
                if (latestStatusResponse == null ||
                        latestStatusResponse.data == null) {
                    onShowToast(getString(R.string.waiting_robot_status));
                    return;
                }

                if (latestStatusResponse.data.chargeStatus != null) {
                    int chargeState = latestStatusResponse.data.chargeStatus.state;
                    if (chargeState == 300) {
                        onVoicePrompt(VoiceKeyConstants.CHARGING_TASK_ERROR);
                        return;
                    }
                }
                // 检查置信度状态
                if (latestStatusResponse.data.poseProbability < DEFAULT_CONFIDENCE) {
                    onVoicePrompt(VoiceKeyConstants.LOW_CONFIDENCE_ERROR);
                    return; // 直接返回，不执行后续逻辑
                }
//                if (latestStatusResponse.data.electricCurrentIn > 0.01) {
//                    onVoicePrompt(VoiceKeyConstants.CHARGING_TASK_ERROR);
//                    return; // 直接返回，不执行后续逻辑
//                }

                // 根据当前位置判断是否与第一个点相同楼层， 读取电梯配置， 插入侯梯点+乘梯点，
                List<Position> fullPositionList = generateFullPositionList(currentPosition,
                        generateDeliveryPositionList(selectedPositions));
                if (fullPositionList == null) {
                    return;
                }
                StringBuilder taskInfo = new StringBuilder(getString(R.string.start_task_prefix));
                for (Position pos : selectedPositions) {
                    taskInfo.append(pos.getName()).append(", ");
                }
                taskInfo.setLength(taskInfo.length() - 2); // 移除最后的逗号和空格
                // 开始任务的逻辑
                onVoicePrompt(VoiceKeyConstants.DEPARTURE_BROADCAST);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isActivityValid()) {
                            Log.w(TAG, "Activity destroyed before task could start");
                            return;
                        }
                        Log.d(TAG, "start delivery task in setupUIListeners");
                        // 低电量拦截
                        if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
                            BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
                            int level = BatteryLevelManager.getInstance().getCurrentBatteryLevel();
                            Log.w(TAG, "Delivery task rejected: battery too low (" + level + "%, state=" + state + ")");
                            runOnUiThread(() -> android.widget.Toast.makeText(DeliveryActivity.this,
                                    getString(R.string.delivery_battery_too_low_backpack, level), android.widget.Toast.LENGTH_SHORT).show());
                            return;
                        }
                        MyApplication.getInstance().getTaskViewModel()
                                .markTaskExecutingIfUnset("delivery", buildTaskName(selectedPositions));
                        navigationHandler.startNavigation(fullPositionList, () -> {
                            // Completion callback
                            //runOnUiThread(() -> onShowToast(R.string.task_completed));
                            runOnUiThread(()->{
                                clearAllSelections();
                            });
                        });
                    }
                },600);//延迟500ms,等待语音播报完成
            }
        });

        // 取消任务按钮
        MaterialButton btnCancelTask = findViewById(R.id.btnCancelTask);
        btnCancelTask.setOnClickListener(v -> {
            navigationHandler.cancelNavigation(true);
            musicPlayer.stop();
            onShowToast(R.string.task_canceled);
        });
    }

    /**
     * 清空所有选中站点的按钮状态
     */
        public void clearAllSelections() {
            // 清空选中索引集合
            selectedStationIndices.clear();
            stationOrderMap.clear();

            // 更新所有按钮的外观
            if (stationButtons != null) {
                for (Button button : stationButtons) {
                    if (button != null) {
                        // 恢复按钮原始状态
                        Object originalName = button.getTag(R.id.original_name_tag);
                        if (originalName != null) {
                            button.setText(originalName.toString());
                        }
                        button.setBackgroundResource(R.drawable.bg_button_station_normal);
                        button.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    }
                }
            }
        }
    // --------------------------------------------------------------------------------------------
    // 导航逻辑

     void executeDeliveryByTaskId(int taskId) {
        // 低电量拦截
        if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
            BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
            int level = BatteryLevelManager.getInstance().getCurrentBatteryLevel();
            Log.w(TAG, "Delivery task rejected: battery too low (" + level + "%, state=" + state + ")");
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    getString(R.string.delivery_battery_too_low_backpack, level), android.widget.Toast.LENGTH_SHORT).show());
            return;
        }

        Position task = getPositionById(taskId);

        List<Position> taskRoute = new ArrayList<>();
        taskRoute.add(task);
        List<Position> fullPositionList = generateFullPositionList(currentPosition,
                generateDeliveryPositionList(taskRoute));
         if (fullPositionList == null) {
             return;
         }
        Log.d(TAG, "start delivery task in executeDeliveryByTaskId");
        MyApplication.getInstance().getTaskViewModel()
                .markTaskExecuting("delivery", buildTaskName(taskRoute));
        // 开始任务的逻辑
        navigationHandler.startNavigation(fullPositionList, () -> {
            // Completion callback
            //runOnUiThread(() -> onShowToast(R.string.task_completed));
            runOnUiThread(() ->{
                clearAllSelections();
            });
        });
    }

    void executeDeliveryByTaskObject(List<Position> selectedPosition) {
        // 低电量拦截
        if (!BatteryLevelManager.getInstance().canAcceptNewTask()) {
            BatteryState state = BatteryLevelManager.getInstance().getCurrentState();
            int level = BatteryLevelManager.getInstance().getCurrentBatteryLevel();
            Log.w(TAG, "Delivery task rejected: battery too low (" + level + "%, state=" + state + ")");
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    getString(R.string.delivery_battery_too_low_backpack, level), android.widget.Toast.LENGTH_SHORT).show());
            return;
        }

        List<Position> fullPositionList = generateFullPositionList(currentPosition,
                generateDeliveryPositionList(selectedPosition));
        if (fullPositionList == null) {
            return;
        }
        Log.d(TAG, "start delivery task in executeDeliveryByTaskObject");
        MyApplication.getInstance().getTaskViewModel()
                .markTaskExecutingIfUnset("delivery", buildTaskName(selectedPosition));
        // 开始任务的逻辑
        navigationHandler.startNavigation(fullPositionList, () -> {
            // Completion callback
            //runOnUiThread(() -> onShowToast(R.string.task_completed));
            runOnUiThread(() ->{
                clearAllSelections();
            });
        });
    }

    private String buildTaskName(List<Position> positions) {
        List<String> names = new ArrayList<>();
        if (positions != null) {
            for (Position position : positions) {
                if (position != null && position.getName() != null
                        && !position.getName().trim().isEmpty()) {
                    names.add(position.getName().trim());
                }
            }
        }
        return names.isEmpty() ? "delivery" : String.join(",", names);
    }

    private List<Position> generateDeliveryPositionList(List<Position> selectedPositions) {
        List<Position> positions = new ArrayList<>();

        // 获取当前轨道模式设置（固定路径/自由导航）
        boolean isVirtualOrbitMode = basicViewModel != null && 
                basicViewModel.getIsVirtualOrbitMode().getValue() != null && 
                basicViewModel.getIsVirtualOrbitMode().getValue();
        
        // 获取绕障时间设置
        Integer obstacleAvoidanceTime = null;
        if (basicViewModel != null && basicViewModel.getVirtualOrbitObstacleTime().getValue() != null) {
            obstacleAvoidanceTime = basicViewModel.getVirtualOrbitObstacleTime().getValue();
        }

        // Step 1: Convert selected IDs to Positions
        for (Position position: selectedPositions) {
            String positionName = position.getName() != null ? position.getName() : "null";
            position.setMessage(getString(R.string.navigating_to_task_point, positionName));
            position.setTaskType(1);
            
            // 设置轨道模式，使配送任务支持固定路径与自由导航切换
            position.setVirtualOrbit(isVirtualOrbitMode);
            
            // 设置虚拟轨道绕障时间
            if (obstacleAvoidanceTime != null) {
                position.setVirtualOrbitObstacleTime(obstacleAvoidanceTime);
            }
            
            positions.add(position);
        }
        return positions;
    }

    @SuppressLint("StringFormatMatches")
    private Position getPositionById(int id) {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        Building firstBuilding = mapPoints != null ? mapPoints.getFirstNonZeroBuilding() : null;
        int buildings = mapPoints != null ? mapPoints.getAllBuildings().size() : 0;
        int totalPoints = mapPoints != null ? mapPoints.getTotalPositionCount() : 0;

        if (firstBuilding == null) {
            Log.e("taskDebug7", "[DELIVERY-FAIL] getPositionById: firstBuilding=null, id=" + id +
                    ", mapPoints buildings=" + buildings + ", totalPoints=" + totalPoints);
            onShowToast(getString(R.string.station_not_exist, id));
            return null;
        }

        Position position = firstBuilding.findPositionById(id);
        if (position == null) {
            Log.e("taskDebug7", "[DELIVERY-FAIL] findPositionById returned null: id=" + id +
                    ", firstBuilding=" + firstBuilding.getBuildingId() +
                    ", buildings=" + buildings + ", totalPoints=" + totalPoints);
            onShowToast(getString(R.string.station_not_exist, id));
        } else {
            Log.i("taskDebug7", "[DELIVERY] getPositionById FOUND: id=" + id + ", name=" + position.getName());
        }
        return position;
    }
    // --------------------------------------------------------------------------------------------

    // 导航相关UI

    // 显示无数据状态
    private void showNoData() {
        Log.d(TAG, "showNoData - Showing no data UI");
        runOnUiThread(() -> {
            findViewById(R.id.ivNoDataBackground1).setVisibility(View.VISIBLE);
            findViewById(R.id.tvNoData1).setVisibility(View.VISIBLE);
            findViewById(R.id.scrollView).setVisibility(View.GONE);

            if (stationContainer != null) {
                int childCount = stationContainer.getChildCount();
                Log.d(TAG, "showNoData - Removed " + childCount + " children from station container");
                stationContainer.removeAllViews();
            }
            selectedStationIndices.clear();
            stationOrderMap.clear();

            onShowToast(R.string.no_position_data);
        });
    }
    // -------------------------------------------------------------------------------------------
    // 布局相关UI

    private void createStationButtons(Map<Integer, List<Position>> floorMap) {
        Log.d(TAG, "createStationButtons - Creating buttons for " +
                (floorMap != null ? floorMap.size() + " floors" : "null"));

        if (stationContainer == null) {
            Log.e(TAG, "createStationButtons - stationContainer is null!");
            return;
        }

        stationContainer.removeAllViews();
        Log.d(TAG, "createStationButtons - Cleared station container");

        if (floorMap == null || floorMap.isEmpty()) {
            Log.w(TAG, "createStationButtons - Floor map is empty or null, showing no data");
            showNoData();
            return;
        }

        Log.d(TAG, "createStationButtons - Processing floor map with floors: " + floorMap.keySet());

        findViewById(R.id.scrollView).setVisibility(View.VISIBLE);
        findViewById(R.id.ivNoDataBackground1).setVisibility(View.GONE);
        findViewById(R.id.tvNoData1).setVisibility(View.GONE);

        List<Integer> sortedFloors = new ArrayList<>(floorMap.keySet());
        Collections.sort(sortedFloors);
        selectedStationIndices.clear();

        // Calculate total buttons and check if we have any positions
        int totalButtons = 0;
        boolean hasValidPositions = false;

        for (int floor : sortedFloors) {
            List<Position> positions = floorMap.get(floor);
            if (positions != null && !positions.isEmpty()) {
                totalButtons += positions.size();
                hasValidPositions = true;
            }
        }

        if (!hasValidPositions || totalButtons == 0) {
            showNoData();
            return;
        }

        stationButtons = new Button[totalButtons];
        int buttonIndex = 0;

        for (int floor : sortedFloors) {
            List<Position> positions = floorMap.get(floor);
            if (positions == null || positions.isEmpty()) continue;

            // Add floor title
            addFloorTitle(floor);

            // Create floor grid
            GridLayout floorGrid = createFloorGrid(positions, floor);
            stationContainer.addView(floorGrid);

            // Create buttons for each position
            for (int i = 0; i < positions.size(); i++) {
                Position position = positions.get(i);
                String key = floor + "_" + i; // Create the unique key

                Button button = createStationButton(position, selectedStationIndices.contains(key));
                button.setTag(key); // Store the key in the tag for identification

                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                // 设置固定宽度而不是权重
                params.width = dpToPx(280); // 固定宽度为120dp
                params.height = dpToPx(48);
                // 移除columnSpec或者设置为固定列
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
                params.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
                button.setLayoutParams(params);

                floorGrid.addView(button);

                // Set click listener
                button.setOnClickListener(v -> {
                    String stationKey = (String) v.getTag();
                    toggleStationSelection(stationKey, (Button) v);
                });

                stationButtons[buttonIndex] = button;
                buttonIndex++;
            }
        }
    }

    private List<Position> getSelectedPositions() {
        List<Position> selectedPositions = new ArrayList<>();
        Map<Integer, List<Position>> floorMap = mapViewModel.getAllFloorPositionsFromMapPoints();

        if (floorMap == null) return selectedPositions;

        for (String key : selectedStationIndices) {
            String[] parts = key.split("_");
            if (parts.length != 2) continue;

            try {
                int floor = Integer.parseInt(parts[0]);
                int index = Integer.parseInt(parts[1]);

                List<Position> positions = floorMap.get(floor);
                if (positions != null && index >= 0 && index < positions.size()) {
                    selectedPositions.add(positions.get(index));
                }
            } catch (NumberFormatException e) {
                Log.e("Selection", "Invalid key format: " + key);
            }
        }

        return selectedPositions;
    }

    private void updateStationButtonAppearance() {
        if (stationButtons == null) return;

        // Recalculate station order
        recalculateStationOrder();

        // Update all button states
        for (Button button : stationButtons) {
            if (button == null) continue;

            String key = (String) button.getTag();
            String stationName = button.getText().toString(); // Get original name

            // If this button was recently added and doesn't have the original name stored,
            // you might need to store the original name separately
            Object originalNameTag = button.getTag(R.id.original_name_tag);
            if (originalNameTag != null) {
                stationName = (String) originalNameTag;
            }

            if (selectedStationIndices.contains(key)) {
                // Get order number
                Integer order = stationOrderMap.get(key);
                if (order != null) {
                    // Create styled text
                    SpannableStringBuilder builder = new SpannableStringBuilder();

                    // Add order number (orange)
                    String orderText = order + "、";
                    SpannableString orderSpan = new SpannableString(orderText);
                    orderSpan.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.battery_medium)),
                            0, orderText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    orderSpan.setSpan(new StyleSpan(Typeface.BOLD), 0, orderText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    builder.append(orderSpan);
                    builder.append(stationName);

                    button.setText(builder);
                }
                button.setBackgroundResource(R.drawable.bg_button_station_selected);
                button.setTextColor(Color.WHITE);
            } else {
                button.setText(stationName);
                button.setBackgroundResource(R.drawable.bg_button_station_normal);
                button.setTextColor(Color.LTGRAY);
            }
        }
    }

    /**
     * 重新计算站点序号（修复序号显示问题）
     */
    private void recalculateStationOrder() {
        stationOrderMap.clear();
        int order = 1;
        for (String key : selectedStationIndices) {
            stationOrderMap.put(key, order++);
        }
    }

    private void addFloorTitle(int floor) {
        TextView title = new TextView(this);
        title.setText(getString(R.string.floor_title, floor));
        title.setTextSize(18);
        title.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        title.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(8));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        title.setLayoutParams(params);

        stationContainer.addView(title);
    }

    private Button createStationButton(Position position, boolean isSelected) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(position.getName());
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);

        // Store both the key and original name
        button.setTag(position.getName()); // Key will be set separately
        button.setTag(R.id.original_name_tag, position.getName()); // Store original name

        if (isSelected) {
            button.setBackgroundResource(R.drawable.bg_button_station_selected);
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setBackgroundResource(R.drawable.bg_button_station_normal);
            button.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        button.setMinWidth(dpToPx(120));
        return button;
    }

    private GridLayout createFloorGrid(List<Position> positions, int floor) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3); // 每行3个按钮

        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        grid.setLayoutParams(gridParams);

        return grid;
    }

    private void toggleStationSelection(String key, Button button) {
        boolean isNowSelected = false;

        if (selectedStationIndices.contains(key)) {
            selectedStationIndices.remove(key);
            isNowSelected = false;
        } else {
            selectedStationIndices.add(key);
            isNowSelected = true;
        }

        // Update button appearance
        if (isNowSelected) {
            button.setBackgroundResource(R.drawable.bg_button_station_selected);
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setBackgroundResource(R.drawable.bg_button_station_normal);
            button.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        // Update all button displays with order numbers
        updateStationButtonAppearance();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
