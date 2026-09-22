package com.ezhan.amr.ui.fragments;

import static com.ezhan.amr.data.datatype.MultiBuildingMapPoints.generateMapName;
import static com.ezhan.amr.navigation.task.NavigationOrderType.OP_ELEVATOR_CALL;
import static com.ezhan.amr.navigation.task.NavigationOrderType.OP_ELEVATOR_CANCEL_OPEN;
import static com.ezhan.amr.navigation.task.NavigationOrderType.OP_ELEVATOR_OPEN;
import static com.ezhan.amr.navigation.task.NavigationOrderType.OP_ELEVATOR_RELEASE_ACCESS;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ezhan.amr.ui.FloatingTipView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datastore.DataStoreKeys;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.task.CommandBuilder;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.viewmodels.ElevatorViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ElevatorFragment extends Fragment {
    private static final String TAG = "ElevatorFragment";
    private static final String DEBUG_TAG = "测试一"; // 用于日志过滤排查数据丢失

    private MaterialButton btnAddElevator;
    private FrameLayout backgroundContainer, dataContainer;
    private ImageView ivNoDataBackground;
    private TextView tvNoData;
    private RecyclerView rvElevatorList;
    private TabLayout buildingTabLayout;
    private static SharedViewModel sharedViewModel;
    private ElevatorViewModel elevatorViewModel;
    private MapViewModel mapViewModel;
    private BasicViewModel basicViewModel;
    private ElevatorAdapter elevatorAdapter;
    private boolean hasLoadedData = false;
    private String currentSelectedBuilding = "default";
    private List<String> availableBuildings = new ArrayList<>();
    private boolean isRestoringTab = false;
    private FloatingTipView elevatorTipView;
    private static final int TIP_EXPANDED_WIDTH_DP = 320;
    private static final int TIP_DEFAULT_MARGIN_DP = 16;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: Creating ElevatorFragment view");
        return inflater.inflate(R.layout.fragment_elevator, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: Initializing ElevatorFragment");

        elevatorViewModel = MyApplication.getInstance().getElevatorViewModel();
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        basicViewModel = MyApplication.getInstance().getBasicViewModel();

        initViews(view);
        setupRecyclerView();
        setupListeners();
        setupBuildingTabs();

        observeData();
        observeMapPointsForElevatorData();

        // 初始化电梯操作规范浮动提示
        initElevatorTipView(view);
    }

    private void setupBuildingTabs() {
        buildingTabLayout = getView().findViewById(R.id.buildingTabLayout);

        // Load previously selected building FIRST
        loadSelectedBuilding();
        Log.d(TAG, "setupBuildingTabs: Loaded selected building = " + currentSelectedBuilding);

        if (buildingTabLayout != null) {
            buildingTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    if (!isRestoringTab) {
                        String buildingId = (String) tab.getTag();
                        if (buildingId != null && !buildingId.equals(currentSelectedBuilding)) {
                            currentSelectedBuilding = buildingId;
                            saveSelectedBuilding(buildingId);
                            Log.d(TAG, "onTabSelected: Building changed to " + buildingId);
                            loadElevatorDataForBuilding(buildingId);
                        }
                    }
                }

                @Override
                public void onTabUnselected(TabLayout.Tab tab) {}

                @Override
                public void onTabReselected(TabLayout.Tab tab) {
                    if (!isRestoringTab) {
                        String buildingId = (String) tab.getTag();
                        if (buildingId != null && !buildingId.equals(currentSelectedBuilding)) {
                            currentSelectedBuilding = buildingId;
                            saveSelectedBuilding(buildingId);
                            Log.d(TAG, "onTabReselected: Building changed to " + buildingId);
                            loadElevatorDataForBuilding(buildingId);
                        }
                    }
                }
            });
        }
    }

    // Add methods to save/load selected building
    private void saveSelectedBuilding(String buildingId) {
        if (getContext() != null) {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            prefs.edit().putString("selected_elevator_building", buildingId).apply();
        }
    }

    private void loadSelectedBuilding() {
        if (getContext() != null) {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            String savedBuilding = prefs.getString("selected_elevator_building", "default");
            currentSelectedBuilding = savedBuilding;
        }
    }

    private void updateBuildingTabs(List<String> buildings) {
        if (buildingTabLayout != null) {
            if (buildings.size() > 1) {
                // Multiple buildings - show tabs
                buildingTabLayout.setVisibility(View.VISIBLE);
                buildingTabLayout.removeAllTabs();

                int selectedIndex = 0;
                isRestoringTab = true;

                for (int i = 0; i < buildings.size(); i++) {
                    String buildingId = buildings.get(i);
                    String displayName = buildingId.equals("default") ?
                            getString(R.string.default_building_short) : buildingId.toUpperCase();
                    TabLayout.Tab tab = buildingTabLayout.newTab().setText(displayName);
                    tab.setTag(buildingId);
                    buildingTabLayout.addTab(tab);

                    if (buildingId.equals(currentSelectedBuilding)) {
                        selectedIndex = i;
                        Log.d(TAG, "updateBuildingTabs: Found matching building tab at index " + i);
                    }
                }

                if (selectedIndex < buildingTabLayout.getTabCount()) {
                    TabLayout.Tab selectedTab = buildingTabLayout.getTabAt(selectedIndex);
                    if (selectedTab != null) {
                        selectedTab.select();
                    }
                }

                isRestoringTab = false;
            } else if (buildings.size() == 1) {
                // Single building - hide tabs but ensure currentSelectedBuilding is correct
                buildingTabLayout.setVisibility(View.GONE);

                // Make sure currentSelectedBuilding is set to the single building
                String singleBuilding = buildings.get(0);
                if (!currentSelectedBuilding.equals(singleBuilding)) {
                    currentSelectedBuilding = singleBuilding;
                    saveSelectedBuilding(currentSelectedBuilding);
                    Log.d(TAG, "updateBuildingTabs: Single building, set currentSelectedBuilding to " + currentSelectedBuilding);
                    // Reload data for this building
                    loadElevatorDataForBuilding(currentSelectedBuilding);
                }
            } else {
                // No buildings - hide tabs
                buildingTabLayout.setVisibility(View.GONE);
            }
        }
    }

    private void observeMapPointsForElevatorData() {
        mapViewModel.getCurrentMapPoints().observe(getViewLifecycleOwner(), mapPoints -> {
            Log.d(TAG, "observeMapPointsForElevatorData: MapPoints received = " + (mapPoints != null));
            if (mapPoints != null) {
                Log.d(DEBUG_TAG, "[Fragment数据加载] observeMapPointsForElevatorData: MapPoints更新"
                        + ", hasLoadedData=" + hasLoadedData
                        + ", 建筑数=" + mapPoints.getAllBuildings().size()
                        + ", 总点位=" + mapPoints.getTotalPositionCount());
                updateAvailableBuildings(mapPoints);
                if (!hasLoadedData) {
                    hasLoadedData = true;
                    Log.d(DEBUG_TAG, "[Fragment数据加载] 首次加载, 调用loadElevatorDataFromMapPoints");
                    loadElevatorDataFromMapPoints();
                }
            }
        });
    }

    private void updateAvailableBuildings(MultiBuildingMapPoints mapPoints) {
        if (mapPoints == null) return;

        availableBuildings.clear();
        availableBuildings.addAll(mapPoints.getAllBuildings().keySet());
        Log.d(TAG, "updateAvailableBuildings: Available buildings = " + availableBuildings);

        if (availableBuildings.isEmpty()) {
            availableBuildings.add("default");
        }

        // If currentSelectedBuilding is not in available buildings, update it
        if (!availableBuildings.contains(currentSelectedBuilding) && !availableBuildings.isEmpty()) {
            String newBuilding = availableBuildings.get(0);
            currentSelectedBuilding = newBuilding;
            saveSelectedBuilding(currentSelectedBuilding);
            Log.d(TAG, "updateAvailableBuildings: Updated currentSelectedBuilding from previous value to " + newBuilding);
        }

        updateBuildingTabs(availableBuildings);
    }

    private void loadElevatorDataForBuilding(String buildingId) {
        Log.d(TAG, "loadElevatorDataForBuilding: Loading elevator data for building " + buildingId);
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints != null) {
            loadElevatorDataFromMapPointsForBuilding(mapPoints, buildingId);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Refreshing elevator data");

        Log.d(DEBUG_TAG, "[Fragment生命周期] onResume: 开始, hasLoadedData=" + hasLoadedData
                + ", currentSelectedBuilding=" + currentSelectedBuilding);

        // Check if we already have configs
        List<ElevatorViewModel.ElevatorConfig> existingConfigs = elevatorViewModel.getElevatorConfigs().getValue();
        Log.d(DEBUG_TAG, "[Fragment生命周期] onResume: existingConfigs.size="
                + (existingConfigs != null ? existingConfigs.size() : 0));
        if (existingConfigs != null && !existingConfigs.isEmpty()) {
            Log.d(TAG, "onResume: Already have " + existingConfigs.size() + " configs, not reloading");
            // Just refresh the adapter with existing configs
            List<ElevatorViewModel.ElevatorConfig> filteredConfigs = new ArrayList<>();
            for (ElevatorViewModel.ElevatorConfig config : existingConfigs) {
                String configBuilding = config.getBuildingId();
                if (configBuilding == null) configBuilding = "default";
                if (configBuilding.equals(currentSelectedBuilding)) {
                    filteredConfigs.add(config);
                }
            }
            Log.d(DEBUG_TAG, "[Fragment生命周期] onResume: 过滤后configs数=" + filteredConfigs.size());
            elevatorAdapter.updateData(filteredConfigs);
            updateBackgroundVisibility(filteredConfigs.isEmpty());
        } else {
            hasLoadedData = false;
            MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
            Log.d(DEBUG_TAG, "[Fragment生命周期] onResume: configs为空, 重新加载, mapPoints=" + (mapPoints != null));
            if (mapPoints != null) {
                // 先设置为 true，防止 Observer 重复触发
                hasLoadedData = true;
                loadElevatorDataFromMapPoints();
            }
        }

        if (elevatorTipView != null) {
            elevatorTipView.resumeTimer();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (elevatorTipView != null) {
            elevatorTipView.pauseTimer();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (elevatorTipView != null) {
            elevatorTipView.destroy();
            elevatorTipView = null;
        }
    }

    /**
     * 初始化电梯操作规范浮动提示
     */
    private void initElevatorTipView(@NonNull View rootView) {
        elevatorTipView = rootView.findViewById(R.id.elevatorTipView);
        if (elevatorTipView == null) return;

        // 设置提示类型和内容
        elevatorTipView.setTipType("elevator");
        elevatorTipView.setTipContent(
                getString(R.string.elevator_tip_title),
                getString(R.string.elevator_tip_content));

        rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int hostWidth = rootView.getWidth();
                        int hostHeight = rootView.getHeight();
                        if (hostWidth > 0 && hostHeight > 0) {
                            rootView.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);

                            float density = getResources().getDisplayMetrics().density;
                            float margin = TIP_DEFAULT_MARGIN_DP * density;
                            float defaultX = hostWidth - TIP_EXPANDED_WIDTH_DP * density - margin;
                            float defaultY = margin;

                            elevatorTipView.setX(defaultX);
                            elevatorTipView.setY(defaultY);
                            elevatorTipView.setVisibility(View.VISIBLE);
                            elevatorTipView.initPosition(defaultX, defaultY);
                        }
                    }
                });
    }

    private void initViews(View view) {
        Log.d(TAG, "initViews: Initializing views");
        btnAddElevator = view.findViewById(R.id.btnAddElevator);
        backgroundContainer = view.findViewById(R.id.backgroundContainer);
        dataContainer = view.findViewById(R.id.dataContainer);
        ivNoDataBackground = view.findViewById(R.id.ivNoDataBackground);
        tvNoData = view.findViewById(R.id.tvNoData);
        rvElevatorList = view.findViewById(R.id.rvElevatorList);
    }

    private void observeData() {
        Log.d(TAG, "observeData: Setting up observer for elevator configs");
        elevatorViewModel.getElevatorConfigs().observe(getViewLifecycleOwner(), configs -> {
            Log.d(TAG, "observeData: Elevator configs changed, size=" + (configs != null ? configs.size() : 0));
            if (configs != null) {
                Log.d(DEBUG_TAG, "[Fragment数据加载] observeData: configs变更, 总数=" + configs.size()
                        + ", currentSelectedBuilding=" + currentSelectedBuilding);
                // Filter configs by current building
                List<ElevatorViewModel.ElevatorConfig> filteredConfigs = new ArrayList<>();
                for (ElevatorViewModel.ElevatorConfig config : configs) {
                    String configBuilding = config.getBuildingId();
                    if (configBuilding == null) configBuilding = "default";
                    if (configBuilding.equals(currentSelectedBuilding)) {
                        filteredConfigs.add(config);
                    }
                }
                Log.d(DEBUG_TAG, "[Fragment数据加载] observeData: 过滤后configs数=" + filteredConfigs.size());
                elevatorAdapter.updateData(filteredConfigs);
                updateBackgroundVisibility(filteredConfigs.isEmpty());
            }
        });
    }

    private void setupRecyclerView() {
        Log.d(TAG, "setupRecyclerView: Setting up RecyclerView");
        rvElevatorList.setLayoutManager(new LinearLayoutManager(requireContext()));
        elevatorAdapter = new ElevatorAdapter(new ArrayList<>(), mapViewModel, elevatorViewModel, basicViewModel, this);
        rvElevatorList.setAdapter(elevatorAdapter);
    }

    private void setupListeners() {
        Log.d(TAG, "setupListeners: Setting up button listeners");
        btnAddElevator.setOnClickListener(v -> {
            Log.d(TAG, "Add Elevator button clicked");
            showAddElevatorDialog();
        });
    }

    private void loadElevatorDataFromMapPoints() {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Log.e(TAG, "loadElevatorDataFromMapPoints: MapPoints is NULL!");
            return;
        }

        // Tell ViewModel we're about to update configs
        elevatorViewModel.beginConfigUpdate();

        try {
            // Load data for all buildings
            for (String buildingId : mapPoints.getAllBuildings().keySet()) {
                loadElevatorDataFromMapPointsForBuilding(mapPoints, buildingId);
            }
        } finally {
            // Always end the update
            elevatorViewModel.endConfigUpdate();
        }
    }

    private void loadElevatorDataFromMapPointsForBuilding(MultiBuildingMapPoints mapPoints, String buildingId) {
        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) {
            Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: buildingId=" + buildingId + " 不存在");
            return;
        }

        Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: buildingId=" + buildingId
                + ", 楼层数=" + building.getAllFloorPoints().size());

        Map<String, ElevatorData> elevatorDataMap = new HashMap<>();

        for (Map.Entry<Integer, FloorPoints> entry : building.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();

            if (floorPoints == null) continue;

            // Process elevator preride points (type 2) - NEW
            for (Position point : floorPoints.getElevatorPreridePoints()) {
                // Generate default ElevatorInfo if missing
                if (point.getElevatorInfo() == null) {
                    Log.w(TAG, "    NO ElevatorInfo in preride point! Generating default ElevatorInfo...");
                    Position.ElevatorInfo defaultInfo = generateDefaultElevatorInfo(point);
                    if (defaultInfo != null) {
                        point.setElevatorInfo(defaultInfo);
                        Log.d(TAG, "    Generated default ElevatorInfo: channel=" + defaultInfo.getLoraChannel() +
                                ", address=" + defaultInfo.getLoraAddress() +
                                ", elevatorId=" + defaultInfo.getElevatorId());
                    } else {
                        Log.w(TAG, "    Could not generate ElevatorInfo for point: " + point.getName() + ", skipping.");
                        continue;
                    }
                }

                String elevatorId = extractElevatorIdFromPoint(point);
                if (elevatorId == null) continue;

                ElevatorData data = elevatorDataMap.get(elevatorId);
                if (data == null) {
                    data = new ElevatorData();
                    data.id = elevatorId;
                    data.buildingId = buildingId;
                    data.configId = extractConfigIdFromElevatorId(elevatorId);
                    if (point.getElevatorInfo() != null) {
                        data.channel = point.getElevatorInfo().getLoraChannel();
                        data.address = point.getElevatorInfo().getLoraAddress();
                    }
                    elevatorDataMap.put(elevatorId, data);
                }
                data.floors.add(floor);
                data.preridePoints.put(floor, point);
            }

            // Process elevator wait points (type 3)
            for (Position point : floorPoints.getElevatorWaitPoints()) {
                // Generate default ElevatorInfo if missing
                if (point.getElevatorInfo() == null) {
                    Log.w(TAG, "    NO ElevatorInfo in wait point! Generating default ElevatorInfo...");
                    Position.ElevatorInfo defaultInfo = generateDefaultElevatorInfo(point);
                    if (defaultInfo != null) {
                        point.setElevatorInfo(defaultInfo);
                        Log.d(TAG, "    Generated default ElevatorInfo: channel=" + defaultInfo.getLoraChannel() +
                                ", address=" + defaultInfo.getLoraAddress() +
                                ", elevatorId=" + defaultInfo.getElevatorId());
                    } else {
                        Log.w(TAG, "    Could not generate ElevatorInfo for point: " + point.getName() + ", skipping.");
                        continue;
                    }
                }

                String elevatorId = extractElevatorIdFromPoint(point);
                if (elevatorId == null) continue;

                ElevatorData data = elevatorDataMap.get(elevatorId);
                if (data == null) {
                    data = new ElevatorData();
                    data.id = elevatorId;
                    data.buildingId = buildingId;
                    data.configId = extractConfigIdFromElevatorId(elevatorId);
                    if (point.getElevatorInfo() != null) {
                        data.channel = point.getElevatorInfo().getLoraChannel();
                        data.address = point.getElevatorInfo().getLoraAddress();
                    }
                    elevatorDataMap.put(elevatorId, data);
                }
                data.floors.add(floor);
                data.waitPoints.put(floor, point);
            }

            // Process elevator ride points (type 4)
            for (Position point : floorPoints.getElevatorRidePoints()) {
                // Generate default ElevatorInfo if missing
                if (point.getElevatorInfo() == null) {
                    Log.w(TAG, "    NO ElevatorInfo in ride point! Generating default ElevatorInfo...");
                    Position.ElevatorInfo defaultInfo = generateDefaultElevatorInfo(point);
                    if (defaultInfo != null) {
                        point.setElevatorInfo(defaultInfo);
                        Log.d(TAG, "    Generated default ElevatorInfo: channel=" + defaultInfo.getLoraChannel() +
                                ", address=" + defaultInfo.getLoraAddress() +
                                ", elevatorId=" + defaultInfo.getElevatorId());
                    } else {
                        Log.w(TAG, "    Could not generate ElevatorInfo for point: " + point.getName() + ", skipping.");
                        continue;
                    }
                }

                String elevatorId = extractElevatorIdFromPoint(point);
                if (elevatorId == null) continue;

                ElevatorData data = elevatorDataMap.get(elevatorId);
                if (data == null) {
                    data = new ElevatorData();
                    data.id = elevatorId;
                    data.buildingId = buildingId;
                    data.configId = extractConfigIdFromElevatorId(elevatorId);
                    if (point.getElevatorInfo() != null) {
                        data.channel = point.getElevatorInfo().getLoraChannel();
                        data.address = point.getElevatorInfo().getLoraAddress();
                    }
                    elevatorDataMap.put(elevatorId, data);
                }
                data.floors.add(floor);
                data.ridePoints.put(floor, point);
            }

            // Process elevator transition points (type 14)
            for (Position point : floorPoints.getElevatorTransitionPoints()) {
                // Generate default ElevatorInfo if missing
                if (point.getElevatorInfo() == null) {
                    Log.w(TAG, "    NO ElevatorInfo in transition point! Generating default ElevatorInfo...");
                    Position.ElevatorInfo defaultInfo = generateDefaultElevatorInfo(point);
                    if (defaultInfo != null) {
                        point.setElevatorInfo(defaultInfo);
                        Log.d(TAG, "    Generated default ElevatorInfo: channel=" + defaultInfo.getLoraChannel() +
                                ", address=" + defaultInfo.getLoraAddress() +
                                ", elevatorId=" + defaultInfo.getElevatorId());
                    } else {
                        Log.w(TAG, "    Could not generate ElevatorInfo for point: " + point.getName() + ", skipping.");
                        continue;
                    }
                }

                String elevatorId = extractElevatorIdFromPoint(point);
                if (elevatorId == null) continue;

                ElevatorData data = elevatorDataMap.get(elevatorId);
                if (data == null) {
                    data = new ElevatorData();
                    data.id = elevatorId;
                    data.buildingId = buildingId;
                    data.configId = extractConfigIdFromElevatorId(elevatorId);
                    if (point.getElevatorInfo() != null) {
                        data.channel = point.getElevatorInfo().getLoraChannel();
                        data.address = point.getElevatorInfo().getLoraAddress();
                    }
                    elevatorDataMap.put(elevatorId, data);
                }
                data.floors.add(floor);
                data.transitionPoints.put(floor, point);
            }
        }

        Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: elevatorDataMap.size=" + elevatorDataMap.size()
                + ", dataMap内容=" + elevatorDataMap.keySet());

        // Get existing configs
        List<ElevatorViewModel.ElevatorConfig> existingConfigs = elevatorViewModel.getElevatorConfigs().getValue();
        List<ElevatorViewModel.ElevatorConfig> updatedConfigs = new ArrayList<>();

        Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: existingConfigs.size="
                + (existingConfigs != null ? existingConfigs.size() : 0));

        // Collect config IDs found in map points for this building
        Set<String> mapPointConfigIds = new HashSet<>();
        for (ElevatorData data : elevatorDataMap.values()) {
            mapPointConfigIds.add(data.configId);
        }
        Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: mapPointConfigIds=" + mapPointConfigIds);

        if (existingConfigs != null) {
            for (ElevatorViewModel.ElevatorConfig config : existingConfigs) {
                String configBuilding = config.getBuildingId();
                if (configBuilding == null) configBuilding = "default";
                if (!configBuilding.equals(buildingId)) {
                    // Keep configs from other buildings
                    updatedConfigs.add(config);
                } else {
                    // For this building: preserve configs that don't have corresponding map points
                    // (e.g., loaded from config file but not yet synced to map points)
                    if (!mapPointConfigIds.contains(config.getId())) {
                        updatedConfigs.add(config);
                    }
                }
            }
        }

        // Add/update configs for this building
        for (ElevatorData data : elevatorDataMap.values()) {
            // CRITICAL: Only process if channel and address are valid (not both 0)
            boolean hasValidChannelAndAddress = (data.channel != 0 || data.address != 0);
            Log.d(TAG, "  Has valid channel/address? " + hasValidChannelAndAddress +
                    " (channel=" + data.channel + ", address=" + data.address + ")");

            // Skip if no valid channel/address - prevents creation of zero configs
            if (!hasValidChannelAndAddress) {
                Log.w(TAG, "  SKIPPING elevator data because channel and address are both 0!");
                continue; // SKIP - don't process this elevator at all
            }

            // IMPORTANT: First check if this config already exists in the original list
            // 使用 buildingId 进行双重匹配，确保不会跨楼栋匹配
            ElevatorViewModel.ElevatorConfig existingConfig = findConfigById(existingConfigs, data.configId, buildingId);

            // 如果按 buildingId 找不到，尝试只按 configId 查找（处理 buildingId 不一致的情况）
            if (existingConfig == null) {
                for (ElevatorViewModel.ElevatorConfig cfg : existingConfigs) {
                    if (cfg.getId().equals(data.configId)) {
                        existingConfig = cfg;
                        break;
                    }
                }
            }

            if (existingConfig != null) {
                // Config exists - only update the points, don't create a new one
                // But also need to add it to updatedConfigs if not already there
                if (!checkConfigById(updatedConfigs, data.configId, buildingId)) {
                    // 如果 buildingId 不同，更新为正确的 buildingId
                    if (!buildingId.equals(existingConfig.getBuildingId())) {
                        existingConfig.setBuildingId(buildingId);
                    }
                    updatedConfigs.add(existingConfig);
                }
                updateConfigFromData(existingConfig, data);
            } else {
                // Config doesn't exist - create new
                ElevatorViewModel.ElevatorConfig newConfig = createConfigFromData(data);
                updatedConfigs.add(newConfig);
            }
        }

        Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: 最终updatedConfigs.size=" + updatedConfigs.size());
        for (ElevatorViewModel.ElevatorConfig cfg : updatedConfigs) {
            Log.d(DEBUG_TAG, "[数据加载]   updatedConfig: id=" + cfg.getId() + ", buildingId=" + cfg.getBuildingId()
                    + ", channel=" + cfg.getChannel() + ", address=" + cfg.getAddress()
                    + ", 楼层数=" + cfg.getFloors().size());
        }

        // Add a helper method to check if config exists in list
        // Tell ViewModel we're about to update configs
        elevatorViewModel.beginConfigUpdate();

        try {
            // Post to LiveData
            elevatorViewModel.elevatorConfigs.postValue(updatedConfigs);
            Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: postValue完成");

            // Save to DataStore
            elevatorViewModel.saveElevatorConfigs();
            Log.d(DEBUG_TAG, "[数据加载] loadElevatorDataFromMapPointsForBuilding: saveElevatorConfigs完成");
        } finally {
            // Always end the update
            elevatorViewModel.endConfigUpdate();
        }
    }

    // Add this helper method
    private boolean checkConfigById(List<ElevatorViewModel.ElevatorConfig> configs, String id, String buildingId) {
        if (configs == null || id == null) return false;
        for (ElevatorViewModel.ElevatorConfig config : configs) {
            // 必须同时匹配 id 和 buildingId，确保不会跨楼栋匹配
            String configBuildingId = config.getBuildingId();
            if (configBuildingId == null) configBuildingId = "default";
            if (id.equals(config.getId()) && configBuildingId.equals(buildingId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate default ElevatorInfo for a point that doesn't have one
     * Uses default channel 9 and address 1
     */
    private Position.ElevatorInfo generateDefaultElevatorInfo(Position point) {
        String pointName = point.getName();
        if (pointName == null) return null;

        // Parse elevator ID from point name (e.g., elevator_preride_16_5 -> 5)
        String[] parts = pointName.split("_");
        if (parts.length < 4) return null;

        String elevatorId = parts[3]; // Get the last part as elevator ID

        // Use default channel 9 and address 1
        int channel = 9;
        int address = 1;

        // Try to get channel and address from existing configs if available
        List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
        if (configs != null) {
            for (ElevatorViewModel.ElevatorConfig config : configs) {
                if (config.getId().equals(elevatorId)) {
                    channel = config.getChannel();
                    address = config.getAddress();
                    break;
                }
            }
        }

        Position.ElevatorInfo info = new Position.ElevatorInfo();
        info.setElevatorId(elevatorId);
        info.setLoraChannel(channel);
        info.setLoraAddress(address);

        // Parse floor number from point name or use the point's floor field
        try {
            int floorNum = Integer.parseInt(point.getFloor());
            info.setFloorNumber(floorNum);
        } catch (NumberFormatException e) {
            // If floor parsing fails, try to extract from name
            try {
                int floorNum = Integer.parseInt(parts[2]);
                info.setFloorNumber(floorNum);
            } catch (NumberFormatException e2) {
                info.setFloorNumber(0);
            }
        }

        info.setDoorOpenTimeout(30);
        info.setCallTimeout(60);

        return info;
    }

    private String extractElevatorIdFromPoint(Position point) {
        if (point.getElevatorInfo() != null) {
            return point.getElevatorInfo().getElevatorId();
        }
        return extractElevatorIdFromPointName(point.getName());
    }

    private String extractElevatorIdFromPointName(String pointName) {
        if (pointName == null) return null;
        String[] parts = pointName.split("_");
        if (parts.length >= 4) {
            return parts[3];
        }
        return null;
    }

    private String extractConfigIdFromElevatorId(String elevatorId) {
        if (elevatorId != null && elevatorId.startsWith("elevator_")) {
            return elevatorId.substring(9);
        }
        return elevatorId;
    }

    private ElevatorViewModel.ElevatorConfig findConfigById(List<ElevatorViewModel.ElevatorConfig> configs, String id, String buildingId) {
        if (configs == null || id == null) return null;
        for (ElevatorViewModel.ElevatorConfig config : configs) {
            // 必须同时匹配 id 和 buildingId，确保不会跨楼栋匹配
            String configBuildingId = config.getBuildingId();
            if (configBuildingId == null) configBuildingId = "default";
            if (id.equals(config.getId()) && configBuildingId.equals(buildingId)) {
                return config;
            }
        }
        return null;
    }

    private ElevatorViewModel.ElevatorConfig createConfigFromData(ElevatorData data) {
        ElevatorViewModel.ElevatorConfig config = new ElevatorViewModel.ElevatorConfig();
        config.setId(data.configId);
        config.setChannel(data.channel);
        config.setAddress(data.address);
        config.setBuildingId(data.buildingId);

        List<Integer> sortedFloors = new ArrayList<>(data.floors);
        java.util.Collections.sort(sortedFloors);

        for (int floorNum : sortedFloors) {
            ElevatorViewModel.ElevatorFloor elevatorFloor = new ElevatorViewModel.ElevatorFloor();
            elevatorFloor.setId(String.valueOf(floorNum));
            elevatorFloor.setFloor(floorNum);
            elevatorFloor.setAlias(getString(R.string.floor_prefix_format, floorNum));
            elevatorFloor.setElevatorConfigId(config.getId());
            elevatorFloor.setBuildingId(config.getBuildingId());

            if (data.preridePoints.containsKey(floorNum)) {
                elevatorFloor.setPreridePoint(data.preridePoints.get(floorNum));
            }
            if (data.waitPoints.containsKey(floorNum)) {
                elevatorFloor.setWaitPoint(data.waitPoints.get(floorNum));
            }
            if (data.ridePoints.containsKey(floorNum)) {
                elevatorFloor.setRidePoint(data.ridePoints.get(floorNum));
            }
            if (data.transitionPoints.containsKey(floorNum)) {
                elevatorFloor.setTransitionPoint(data.transitionPoints.get(floorNum));
            }
            config.getFloors().add(elevatorFloor);
        }
        return config;
    }

    private void updateConfigFromData(ElevatorViewModel.ElevatorConfig config, ElevatorData data) {
        Log.d(DEBUG_TAG, "[数据更新] updateConfigFromData: configId=" + config.getId()
                + ", 原楼层数=" + config.getFloors().size()
                + ", data.floors=" + data.floors
                + ", data.waitPoints=" + data.waitPoints.keySet()
                + ", data.ridePoints=" + data.ridePoints.keySet()
                + ", data.transitionPoints=" + data.transitionPoints.keySet());
        // Only update channel and address if they are valid (non-zero)
        if (data.channel != 0) {
            config.setChannel(data.channel);
        }
        if (data.address != 0) {
            config.setAddress(data.address);
        }
        if (data.buildingId != null) {
            config.setBuildingId(data.buildingId);
        }

        // Create a map of existing floors for quick lookup
        Map<Integer, ElevatorViewModel.ElevatorFloor> existingFloorsMap = new HashMap<>();
        for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
            existingFloorsMap.put(floor.getFloor(), floor);
        }

        List<ElevatorViewModel.ElevatorFloor> updatedFloors = new ArrayList<>();
        List<Integer> sortedFloors = new ArrayList<>(data.floors);
        java.util.Collections.sort(sortedFloors);

        for (int floorNum : sortedFloors) {
            ElevatorViewModel.ElevatorFloor elevatorFloor = existingFloorsMap.get(floorNum);

            if (elevatorFloor == null) {
                // Create new floor if it doesn't exist
                elevatorFloor = new ElevatorViewModel.ElevatorFloor();
                elevatorFloor.setId(String.valueOf(floorNum));
                elevatorFloor.setFloor(floorNum);
                elevatorFloor.setAlias(getString(R.string.floor_prefix_format, floorNum));
                elevatorFloor.setElevatorConfigId(config.getId());
                elevatorFloor.setBuildingId(config.getBuildingId());
            }

            // Update preride points - NEW
            if (data.preridePoints.containsKey(floorNum)) {
                Position newPreridePoint = data.preridePoints.get(floorNum);
                Position existingPreridePoint = elevatorFloor.getPreridePoint();

                if (existingPreridePoint != null && (newPreridePoint.getPosX() == 0 && newPreridePoint.getPosY() == 0)) {
                    newPreridePoint.setPosX(existingPreridePoint.getPosX());
                    newPreridePoint.setPosY(existingPreridePoint.getPosY());
                    newPreridePoint.setYaw(existingPreridePoint.getYaw());
                }
                elevatorFloor.setPreridePoint(newPreridePoint);
            }

            // Update points - but preserve existing coordinates if the new points have zero coordinates
            if (data.waitPoints.containsKey(floorNum)) {
                Position newWaitPoint = data.waitPoints.get(floorNum);
                Position existingWaitPoint = elevatorFloor.getWaitPoint();

                if (existingWaitPoint != null && (newWaitPoint.getPosX() == 0 && newWaitPoint.getPosY() == 0)) {
                    // Preserve existing coordinates
                    newWaitPoint.setPosX(existingWaitPoint.getPosX());
                    newWaitPoint.setPosY(existingWaitPoint.getPosY());
                    newWaitPoint.setYaw(existingWaitPoint.getYaw());
                }
                elevatorFloor.setWaitPoint(newWaitPoint);
            }

            if (data.ridePoints.containsKey(floorNum)) {
                Position newRidePoint = data.ridePoints.get(floorNum);
                Position existingRidePoint = elevatorFloor.getRidePoint();

                if (existingRidePoint != null && (newRidePoint.getPosX() == 0 && newRidePoint.getPosY() == 0)) {
                    newRidePoint.setPosX(existingRidePoint.getPosX());
                    newRidePoint.setPosY(existingRidePoint.getPosY());
                    newRidePoint.setYaw(existingRidePoint.getYaw());
                }
                elevatorFloor.setRidePoint(newRidePoint);
            }

            if (data.transitionPoints.containsKey(floorNum)) {
                Position newTransitionPoint = data.transitionPoints.get(floorNum);
                Position existingTransitionPoint = elevatorFloor.getTransitionPoint();

                if (existingTransitionPoint != null && (newTransitionPoint.getPosX() == 0 && newTransitionPoint.getPosY() == 0)) {
                    newTransitionPoint.setPosX(existingTransitionPoint.getPosX());
                    newTransitionPoint.setPosY(existingTransitionPoint.getPosY());
                    newTransitionPoint.setYaw(existingTransitionPoint.getYaw());
                }
                elevatorFloor.setTransitionPoint(newTransitionPoint);
            }

            updatedFloors.add(elevatorFloor);
        }

        Log.d(DEBUG_TAG, "[数据更新] updateConfigFromData: 更新后楼层数=" + updatedFloors.size()
                + ", 楼层列表=" + sortedFloors);
        config.setFloors(updatedFloors);
    }

    private ElevatorViewModel.ElevatorFloor findFloorByNumber(List<ElevatorViewModel.ElevatorFloor> floors, int floorNum) {
        if (floors == null) return null;
        for (ElevatorViewModel.ElevatorFloor floor : floors) {
            if (floor.getFloor() == floorNum) {
                return floor;
            }
        }
        return null;
    }

    private void showAddElevatorDialog() {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Toast.makeText(requireContext(), getString(R.string.elevator_download_map_first), Toast.LENGTH_LONG).show();
            return;
        }

        Building currentBuilding = mapPoints.getBuilding(currentSelectedBuilding);
        if (currentBuilding == null) {
            Toast.makeText(requireContext(), getString(R.string.elevator_no_building_data), Toast.LENGTH_LONG).show();
            return;
        }

        Set<Integer> availableFloors = new HashSet<>();
        for (Map.Entry<Integer, FloorPoints> entry : currentBuilding.getAllFloorPoints().entrySet()) {
            int floor = entry.getKey();
            FloorPoints floorPoints = entry.getValue();
            if (floorPoints != null && floorPoints.getPositionCount() > 0) {
                availableFloors.add(floor);
            }
        }

        if (availableFloors.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.elevator_no_floor_data_download_first), Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_elevator, null);
        builder.setView(dialogView);

        EditText etChannel = dialogView.findViewById(R.id.etChannel);
        EditText etAddress = dialogView.findViewById(R.id.etAddress);
        Button btnConfirm = dialogView.findViewById(R.id.btnConfirm);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        EditText etFloors = dialogView.findViewById(R.id.etFloors);
        if (etFloors != null) {
            etFloors.setVisibility(View.GONE);
        }

        AlertDialog dialog = builder.create();

        btnConfirm.setOnClickListener(v -> {
            try {
                int channel = Integer.parseInt(etChannel.getText().toString());
                int address = Integer.parseInt(etAddress.getText().toString());

                ElevatorViewModel.ElevatorConfig config = new ElevatorViewModel.ElevatorConfig();
                config.setChannel(channel);
                config.setAddress(address);
                config.setBuildingId(currentSelectedBuilding);

                List<Integer> sortedFloors = new ArrayList<>(availableFloors);
                java.util.Collections.sort(sortedFloors);

                for (int floorNum : sortedFloors) {
                    ElevatorViewModel.ElevatorFloor floor = new ElevatorViewModel.ElevatorFloor();
                    floor.setAlias(String.format(getString(R.string.floor_prefix_format), floorNum));
                    floor.setFloor(floorNum);
                    floor.setElevatorConfigId(config.getId());
                    floor.setBuildingId(config.getBuildingId());
                    config.getFloors().add(floor);
                }

                elevatorViewModel.addElevatorConfig(config);
                addElevatorPointsToMapPoints(config);
                dialog.dismiss();
                Toast.makeText(requireContext(), getString(R.string.elevator_added_with_floors, sortedFloors.size()), Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void addElevatorPointsToMapPoints(ElevatorViewModel.ElevatorConfig config) {
        String currentMap = mapViewModel.getCurrentMap();
        String buildingId = config.getBuildingId();
        if (buildingId == null) buildingId = "default";

        if (currentMap == null || currentMap.isEmpty()) {
            Log.e(TAG, "addElevatorPointsToMapPoints: current map is null");
            return;
        }

        for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
            int floorNum = floor.getFloor();
            String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floorNum);

            // Preride point (type 2) - 保留其他电梯的点位
            Position preridePoint = createElevatorPoint(floorNum, "elevator_preride", config, fullMapName);
            floor.setPreridePoint(preridePoint);
            addSingleElevatorPoint(buildingId, currentMap, floorNum, 2, preridePoint);

            // Wait point (type 3) - 保留其他电梯的点位
            Position waitPoint = createElevatorPoint(floorNum, "elevator_wait", config, fullMapName);
            floor.setWaitPoint(waitPoint);
            addSingleElevatorPoint(buildingId, currentMap, floorNum, 3, waitPoint);

            // Ride point (type 4) - 保留其他电梯的点位
            Position ridePoint = createElevatorPoint(floorNum, "elevator_ride", config, fullMapName);
            floor.setRidePoint(ridePoint);
            addSingleElevatorPoint(buildingId, currentMap, floorNum, 4, ridePoint);

            // Transition point (type 14) - 保留其他电梯的点位
            Position transitionPoint = createElevatorPoint(floorNum, "elevator_transition", config, fullMapName);
            floor.setTransitionPoint(transitionPoint);
            addSingleElevatorPoint(buildingId, currentMap, floorNum, 14, transitionPoint);
        }
    }

    /**
     * 添加单个电梯点位，同时保留该楼层其他电梯的点位
     */
    private void addSingleElevatorPoint(String buildingId, String currentMap, int floorNum,
                                        int pointType, Position newPoint) {
        List<Position> currentPoints;

        switch (pointType) {
            case 2:
                currentPoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                break;
            case 3:
                currentPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                break;
            case 4:
                currentPoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                break;
            case 14:
                currentPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                break;
            default:
                return;
        }

        if (currentPoints == null) {
            currentPoints = new ArrayList<>();
        }

        // 检查是否已存在同名点位
        boolean found = false;
        List<Position> updatedPoints = new ArrayList<>();
        for (Position point : currentPoints) {
            if (point.getName() != null && point.getName().equals(newPoint.getName())) {
                updatedPoints.add(newPoint);
                found = true;
            } else {
                updatedPoints.add(point);
            }
        }

        if (!found) {
            updatedPoints.add(newPoint);
        }

        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, pointType, updatedPoints);
    }

    private Position createElevatorPoint(int floorNum, String prefix, ElevatorViewModel.ElevatorConfig config, String fullMapName) {
        Position point = new Position();
        point.setId(mapViewModel.generateNewId());
        point.setName(prefix + "_" + floorNum + "_" + config.getId());
        point.setPosX(0.0);
        point.setPosY(0.0);
        point.setYaw(0.0);
        point.setType(prefix.equals("elevator_preride") ? 2 : prefix.equals("elevator_wait") ? 3 : prefix.equals("elevator_ride") ? 4 : 14);
        point.setFloor(String.valueOf(floorNum));
        point.setMapName(fullMapName);
        point.setTaskType(0);

        Position.ElevatorInfo elevatorInfo = new Position.ElevatorInfo();
        elevatorInfo.setLoraChannel(config.getChannel());
        elevatorInfo.setLoraAddress(config.getAddress());
        elevatorInfo.setElevatorId(config.getId());
        elevatorInfo.setFloorNumber(floorNum);
        point.setElevatorInfo(elevatorInfo);

        return point;
    }

    public void updateMapPointsWithElevatorInfoPreservingPositions(ElevatorViewModel.ElevatorConfig config) {
        String currentMap = mapViewModel.getCurrentMap();
        String buildingId = config.getBuildingId();
        if (buildingId == null) buildingId = "default";

        if (currentMap == null || currentMap.isEmpty()) return;

        for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
            int floorNum = floor.getFloor();

            // 更新 preride 点位 - 保留其他电梯的点位
            if (floor.getPreridePoint() != null) {
                floor.getPreridePoint().setName("elevator_preride_" + floorNum + "_" + config.getId());
                floor.getPreridePoint().setType(2);
                updateSingleElevatorPoint(buildingId, currentMap, floorNum, 2, floor.getPreridePoint(), config.getId());
            }

            // 更新 wait 点位 - 保留其他电梯的点位
            if (floor.getWaitPoint() != null) {
                floor.getWaitPoint().setName("elevator_wait_" + floorNum + "_" + config.getId());
                floor.getWaitPoint().setType(3);
                updateSingleElevatorPoint(buildingId, currentMap, floorNum, 3, floor.getWaitPoint(), config.getId());
            }

            // 更新 ride 点位 - 保留其他电梯的点位
            if (floor.getRidePoint() != null) {
                floor.getRidePoint().setName("elevator_ride_" + floorNum + "_" + config.getId());
                floor.getRidePoint().setType(4);
                updateSingleElevatorPoint(buildingId, currentMap, floorNum, 4, floor.getRidePoint(), config.getId());
            }

            // 更新 transition 点位 - 保留其他电梯的点位
            if (floor.getTransitionPoint() != null) {
                floor.getTransitionPoint().setName("elevator_transition_" + floorNum + "_" + config.getId());
                floor.getTransitionPoint().setType(14);
                updateSingleElevatorPoint(buildingId, currentMap, floorNum, 14, floor.getTransitionPoint(), config.getId());
            }
        }
    }

    /**
     * 更新单个电梯点位，同时保留该楼层其他电梯的点位
     */
    private void updateSingleElevatorPoint(String buildingId, String currentMap, int floorNum,
                                           int pointType, Position updatedPoint, String configId) {
        List<Position> currentPoints;

        switch (pointType) {
            case 2:
                currentPoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                break;
            case 3:
                currentPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                break;
            case 4:
                currentPoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                break;
            case 14:
                currentPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                break;
            default:
                return;
        }

        if (currentPoints == null) {
            currentPoints = new ArrayList<>();
        }

        // 构建更新后的列表：保留其他电梯的点位，只更新当前电梯的点位
        List<Position> updatedPoints = new ArrayList<>();
        boolean found = false;
        String targetName = updatedPoint.getName();

        for (Position point : currentPoints) {
            if (point.getName() != null && point.getName().equals(targetName)) {
                // 替换当前电梯的点位
                updatedPoints.add(updatedPoint);
                found = true;
            } else {
                // 保留其他电梯的点位
                updatedPoints.add(point);
            }
        }

        if (!found) {
            // 如果没找到，说明是新点位，添加进去
            updatedPoints.add(updatedPoint);
        }

        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, pointType, updatedPoints);
    }

    public void removeElevatorPointsFromMapPoints(ElevatorViewModel.ElevatorConfig config) {
        String currentMap = mapViewModel.getCurrentMap();
        String buildingId = config.getBuildingId();
        if (buildingId == null) buildingId = "default";

        if (currentMap == null || currentMap.isEmpty()) return;

        for (ElevatorViewModel.ElevatorFloor floor : config.getFloors()) {
            int floorNum = floor.getFloor();
            // 只删除当前电梯的点位，保留其他电梯的点位
            removeSingleElevatorPoint(buildingId, currentMap, floorNum, 2, config.getId());
            removeSingleElevatorPoint(buildingId, currentMap, floorNum, 3, config.getId());
            removeSingleElevatorPoint(buildingId, currentMap, floorNum, 4, config.getId());
            removeSingleElevatorPoint(buildingId, currentMap, floorNum, 14, config.getId());
        }
    }

    /**
     * 删除单个电梯的点位，同时保留该楼层其他电梯的点位
     */
    private void removeSingleElevatorPoint(String buildingId, String currentMap, int floorNum,
                                           int pointType, String configId) {
        List<Position> currentPoints;

        switch (pointType) {
            case 2:
                currentPoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                break;
            case 3:
                currentPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                break;
            case 4:
                currentPoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                break;
            case 14:
                currentPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                break;
            default:
                return;
        }

        if (currentPoints == null || currentPoints.isEmpty()) {
            return;
        }

        // 构建点位的名称前缀，用于匹配要删除的点位
        String pointPrefix;
        switch (pointType) {
            case 2:
                pointPrefix = "elevator_preride_" + floorNum + "_" + configId;
                break;
            case 3:
                pointPrefix = "elevator_wait_" + floorNum + "_" + configId;
                break;
            case 4:
                pointPrefix = "elevator_ride_" + floorNum + "_" + configId;
                break;
            case 14:
                pointPrefix = "elevator_transition_" + floorNum + "_" + configId;
                break;
            default:
                return;
        }

        // 只删除当前电梯的点位，保留其他电梯的点位
        List<Position> remainingPoints = new ArrayList<>();
        for (Position point : currentPoints) {
            if (point.getName() == null || !point.getName().equals(pointPrefix)) {
                // 保留不匹配的点位（其他电梯的点位）
                remainingPoints.add(point);
            }
        }

        mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, pointType, remainingPoints);
    }

    public Position findElevatorPointByFloorAndType(String buildingId, int floor, int type) {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) return null;

        Building building = mapPoints.getBuilding(buildingId);
        if (building == null) return null;

        FloorPoints floorPoints = building.getFloorPoints(floor);
        if (floorPoints == null) return null;

        List<Position> points;
        if (type == 2) {
            points = floorPoints.getElevatorPreridePoints();
        } else if (type == 3) {
            points = floorPoints.getElevatorWaitPoints();
        } else if (type == 4) {
            points = floorPoints.getElevatorRidePoints();
        } else if (type == 14) {
            points = floorPoints.getElevatorTransitionPoints();
        } else {
            return null;
        }

        for (Position point : points) {
            if (point.getType() == type) {
                return point;
            }
        }
        return null;
    }

    /**
     * 保存地图点位到 DataStore，防止重启后丢失
     */
    private void saveMapPointsToDataStore() {
        MultiBuildingMapPoints mapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (mapPoints == null) {
            Log.e(TAG, "saveMapPointsToDataStore: mapPoints is null");
            return;
        }

        try {
            String json = new Gson().toJson(mapPoints, DataStoreKeys.MAP_POINTS_TYPE);
            DataStoreManager.getInstance(requireContext())
                    .putStringBlocking(DataStoreKeys.CURRENT_MAP_POINTS, json);
        } catch (Exception e) {
            Log.e(TAG, "saveMapPointsToDataStore: 保存失败", e);
        }
    }

    public void showConfirmDeleteDialog(Context context, String message, Runnable confirmAction) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_delete, null);
        builder.setView(dialogView);

        TextView tvConfirmMessage = dialogView.findViewById(R.id.dialog_message);
        Button btnCancelDelete = dialogView.findViewById(R.id.negative_button);
        Button btnConfirmDelete = dialogView.findViewById(R.id.positive_button);

        tvConfirmMessage.setText(message);
        AlertDialog dialog = builder.create();

        btnCancelDelete.setOnClickListener(v -> dialog.dismiss());
        btnConfirmDelete.setOnClickListener(v -> {
            confirmAction.run();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void updateBackgroundVisibility(boolean showBackground) {
        backgroundContainer.setVisibility(showBackground ? View.VISIBLE : View.GONE);
        dataContainer.setVisibility(showBackground ? View.GONE : View.VISIBLE);
    }

    //----------------------------------------------------------------------------------------------

    private static class ElevatorAdapter extends RecyclerView.Adapter<ElevatorAdapter.ElevatorViewHolder> {
        private List<ElevatorViewModel.ElevatorConfig> elevatorConfigs;
        private final ElevatorViewModel elevatorViewModel;
        private final MapViewModel mapViewModel;
        private final BasicViewModel basicViewModel;
        private final ElevatorFragment fragment;

        public ElevatorAdapter(List<ElevatorViewModel.ElevatorConfig> configs, MapViewModel mapViewModel,
                               ElevatorViewModel elevatorViewModel, BasicViewModel basicViewModel, ElevatorFragment fragment) {
            this.elevatorConfigs = configs;
            this.elevatorViewModel = elevatorViewModel;
            this.mapViewModel = mapViewModel;
            this.basicViewModel = basicViewModel;
            this.fragment = fragment;
        }

        public void updateData(List<ElevatorViewModel.ElevatorConfig> newConfigs) {
            this.elevatorConfigs = newConfigs != null ? new ArrayList<>(newConfigs) : new ArrayList<>();
            notifyDataSetChanged();
        }

        public List<ElevatorViewModel.ElevatorConfig> getCurrentList() {
            return elevatorConfigs;
        }

        @NonNull
        @Override
        public ElevatorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_elevator, parent, false);
            return new ElevatorViewHolder(view, mapViewModel, elevatorViewModel, basicViewModel, fragment);
        }

        @Override
        public void onBindViewHolder(@NonNull ElevatorViewHolder holder, int position) {
            holder.bind(elevatorConfigs.get(position));
        }

        @Override
        public int getItemCount() {
            return elevatorConfigs.size();
        }

        static class ElevatorViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvElevatorInfo;
            private final MaterialButton btnElevatorSettings;
            private final MaterialButton btnElevatorControl;
            private final RecyclerView rvFloors;
            private final ElevatorViewModel elevatorViewModel;
            private final MapViewModel mapViewModel;
            private final BasicViewModel basicViewModel;
            private FloorAdapter floorAdapter;
            private final ElevatorFragment fragment;
            private ElevatorViewModel.ElevatorConfig currentConfig;

            public ElevatorViewHolder(@NonNull View itemView, MapViewModel mapViewModel,
                                      ElevatorViewModel elevatorViewModel, BasicViewModel basicViewModel, ElevatorFragment fragment) {
                super(itemView);
                tvElevatorInfo = itemView.findViewById(R.id.tvElevatorInfo);
                btnElevatorSettings = itemView.findViewById(R.id.btnElevatorSettings);
                btnElevatorControl = itemView.findViewById(R.id.btnElevatorControl);
                rvFloors = itemView.findViewById(R.id.rvFloors);
                this.elevatorViewModel = elevatorViewModel;
                this.mapViewModel = mapViewModel;
                this.basicViewModel = basicViewModel;
                this.fragment = fragment;

                rvFloors.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
                floorAdapter = new FloorAdapter(new ArrayList<>(), mapViewModel, elevatorViewModel, basicViewModel, fragment);
                rvFloors.setAdapter(floorAdapter);
            }

            // 辅助方法获取字符串资源
            private String getString(int resId) {
                return itemView.getContext().getString(resId);
            }

            public void bind(ElevatorViewModel.ElevatorConfig config) {
                this.currentConfig = config;
                // Display format: (ID: 1, 地址: 1, 信道: 9)
                tvElevatorInfo.setText(String.format(getString(R.string.elevator_display_format),
                        config.getId(), config.getAddress(), config.getChannel()));
                floorAdapter.updateData(config.getFloors());

                btnElevatorSettings.setOnClickListener(v -> showElevatorSettingsDialog(config));
                btnElevatorControl.setOnClickListener(v -> showElevatorControlDialog(config));
            }

            private void showElevatorControlDialog(ElevatorViewModel.ElevatorConfig config) {
                Context context = itemView.getContext();
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_elevator_control, null);
                builder.setView(dialogView);

                Button btnOpenDoor = dialogView.findViewById(R.id.btnOpenDoor);
                Button btnCloseDoor = dialogView.findViewById(R.id.btnCloseDoor);
                Button btnClearOccupy = dialogView.findViewById(R.id.btnClearOccupy);

                AlertDialog dialog = builder.create();
                ProgressDialog progressDialog = new ProgressDialog(context);
                progressDialog.setMessage(getString(R.string.elevator_processing));
                progressDialog.setCancelable(false);

                int channel = config.getChannel();
                int address = config.getAddress();

                int robotId = basicViewModel.getRobotId().getValue() != null ? basicViewModel.getRobotId().getValue() : 1;
                int loraChannel = basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0;
                int loraAddress = basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0;

                View.OnClickListener operationListener = v -> {
                    String command;
                    String successMsg;
                    String errorMsg;
                    Map<String, Object> hexParams = new HashMap<>();
                    hexParams.put("channel", String.format("%02X", channel));
                    hexParams.put("address", String.format("%02X", address));
                    hexParams.put("robotId", String.format("%02X", robotId));
                    hexParams.put("robotChannel", String.format("%02X", loraChannel));
                    hexParams.put("robotAddress", String.format("%02X", loraAddress));

                    if (v == btnClearOccupy) {
                        command = CommandBuilder.buildHexMessage(OP_ELEVATOR_RELEASE_ACCESS, hexParams);
                        successMsg = getString(R.string.clear_occupy_success);
                        errorMsg = getString(R.string.clear_occupy_error);
                    } else if (v == btnOpenDoor) {
                        command = CommandBuilder.buildHexMessage(OP_ELEVATOR_OPEN, hexParams);
                        successMsg = getString(R.string.open_door_success);
                        errorMsg = getString(R.string.open_door_error);
                    } else if (v == btnCloseDoor) {
                        command = CommandBuilder.buildHexMessage(OP_ELEVATOR_CANCEL_OPEN, hexParams);
                        successMsg = getString(R.string.close_door_success);
                        errorMsg = getString(R.string.close_door_error);
                    } else {
                        command = "";
                        errorMsg = "";
                        successMsg = "";
                    }

                    progressDialog.show();

                    new Thread(() -> {
                        try {
                            String response = sharedViewModel.loraCommunicator.sendMessage(command, true);
                            itemView.post(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(context,
                                        successMsg + ": " + response,
                                        Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            itemView.post(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(context,
                                        errorMsg + ": " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    }).start();
                };

                btnClearOccupy.setOnClickListener(operationListener);
                btnOpenDoor.setOnClickListener(operationListener);
                btnCloseDoor.setOnClickListener(operationListener);

                dialog.show();
            }

            private void showElevatorSettingsDialog(ElevatorViewModel.ElevatorConfig config) {
                AlertDialog.Builder builder = new AlertDialog.Builder(itemView.getContext());
                View dialogView = LayoutInflater.from(itemView.getContext())
                        .inflate(R.layout.dialog_elevator_settings, null);
                builder.setView(dialogView);

                EditText etChannel = dialogView.findViewById(R.id.etChannel);
                EditText etAddress = dialogView.findViewById(R.id.etAddress);
                Button btnSave = dialogView.findViewById(R.id.btnSave);
                Button btnDelete = dialogView.findViewById(R.id.btnDelete);

                etChannel.setText(String.valueOf(config.getChannel()));
                etAddress.setText(String.valueOf(config.getAddress()));

                AlertDialog dialog = builder.create();

                btnSave.setOnClickListener(v -> {
                    try {
                        int channel = Integer.parseInt(etChannel.getText().toString());
                        int address = Integer.parseInt(etAddress.getText().toString());

                        // Get the existing config to preserve all data
                        ElevatorViewModel.ElevatorConfig existingConfig = elevatorViewModel.getElevatorConfigs().getValue()
                                .stream()
                                .filter(c -> c.getId().equals(config.getId()))
                                .findFirst()
                                .orElse(config);

                        // Create a new config but preserve the existing floors with their positions
                        ElevatorViewModel.ElevatorConfig newConfig = new ElevatorViewModel.ElevatorConfig();
                        newConfig.setId(config.getId()); // Preserve the same ID
                        newConfig.setChannel(channel);
                        newConfig.setAddress(address);
                        newConfig.setBuildingId(existingConfig.getBuildingId()); // Preserve building ID

                        // IMPORTANT: Preserve existing floors with their positions - don't create new ones
                        List<ElevatorViewModel.ElevatorFloor> preservedFloors = new ArrayList<>();
                        for (ElevatorViewModel.ElevatorFloor oldFloor : existingConfig.getFloors()) {
                            ElevatorViewModel.ElevatorFloor preservedFloor = new ElevatorViewModel.ElevatorFloor();

                            // Copy all properties from the existing floor
                            preservedFloor.setId(oldFloor.getId());
                            preservedFloor.setAlias(oldFloor.getAlias());
                            preservedFloor.setFloor(oldFloor.getFloor());
                            preservedFloor.setElevatorConfigId(config.getId());
                            preservedFloor.setBuildingId(existingConfig.getBuildingId());

                            // Preserve existing points with their coordinates
                            if (oldFloor.getPreridePoint() != null) {
                                Position preridePoint = oldFloor.getPreridePoint();
                                // Update only the elevator info, preserve coordinates
                                if (preridePoint.getElevatorInfo() == null) {
                                    preridePoint.setElevatorInfo(new Position.ElevatorInfo(channel, address, config.getId()));
                                } else {
                                    preridePoint.getElevatorInfo().setLoraChannel(channel);
                                    preridePoint.getElevatorInfo().setLoraAddress(address);
                                }
                                preservedFloor.setPreridePoint(preridePoint);
                            }

                            // Preserve existing points with their coordinates
                            if (oldFloor.getWaitPoint() != null) {
                                Position waitPoint = oldFloor.getWaitPoint();
                                // Update only the elevator info, preserve coordinates
                                if (waitPoint.getElevatorInfo() == null) {
                                    waitPoint.setElevatorInfo(new Position.ElevatorInfo(channel, address, config.getId()));
                                } else {
                                    waitPoint.getElevatorInfo().setLoraChannel(channel);
                                    waitPoint.getElevatorInfo().setLoraAddress(address);
                                }
                                preservedFloor.setWaitPoint(waitPoint);
                            }

                            if (oldFloor.getRidePoint() != null) {
                                Position ridePoint = oldFloor.getRidePoint();
                                if (ridePoint.getElevatorInfo() == null) {
                                    ridePoint.setElevatorInfo(new Position.ElevatorInfo(channel, address, config.getId()));
                                } else {
                                    ridePoint.getElevatorInfo().setLoraChannel(channel);
                                    ridePoint.getElevatorInfo().setLoraAddress(address);
                                }
                                preservedFloor.setRidePoint(ridePoint);
                            }

                            if (oldFloor.getTransitionPoint() != null) {
                                Position transitionPoint = oldFloor.getTransitionPoint();
                                if (transitionPoint.getElevatorInfo() == null) {
                                    transitionPoint.setElevatorInfo(new Position.ElevatorInfo(channel, address, config.getId()));
                                } else {
                                    transitionPoint.getElevatorInfo().setLoraChannel(channel);
                                    transitionPoint.getElevatorInfo().setLoraAddress(address);
                                }
                                preservedFloor.setTransitionPoint(transitionPoint);
                            }

                            preservedFloors.add(preservedFloor);
                        }

                        newConfig.setFloors(preservedFloors);

                        // Update in ViewModel (this will replace the old config)
                        elevatorViewModel.updateElevatorConfig(newConfig);

                        // Update MapPoints with the new config data (preserving positions)
                        fragment.updateMapPointsWithElevatorInfoPreservingPositions(newConfig);

                        // 保存地图点位到 DataStore，防止重启后丢失
                        fragment.saveMapPointsToDataStore();

                        dialog.dismiss();
                    } catch (NumberFormatException e) {
                        Toast.makeText(itemView.getContext(),
                                getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
                    }
                });

                btnDelete.setOnClickListener(v -> {
                    fragment.showConfirmDeleteDialog(itemView.getContext(),
                            getString(R.string.confirm_delete_elevator_message), () -> {
                                fragment.removeElevatorPointsFromMapPoints(config);
                                elevatorViewModel.deleteElevatorConfig(config.getId());
                                dialog.dismiss();
                            });
                });

                dialog.show();
            }
        }
    }

    private static class FloorAdapter extends RecyclerView.Adapter<FloorAdapter.FloorViewHolder> {
        private final MapViewModel mapViewModel;
        private List<ElevatorViewModel.ElevatorFloor> floors;
        private final ElevatorViewModel elevatorViewModel;
        private final BasicViewModel basicViewModel;
        private final ElevatorFragment fragment;

        public FloorAdapter(List<ElevatorViewModel.ElevatorFloor> floors, MapViewModel mapViewModel,
                            ElevatorViewModel elevatorViewModel, BasicViewModel basicViewModel, ElevatorFragment fragment) {
            this.floors = floors;
            this.elevatorViewModel = elevatorViewModel;
            this.mapViewModel = mapViewModel;
            this.basicViewModel = basicViewModel;
            this.fragment = fragment;
        }

        public void updateData(List<ElevatorViewModel.ElevatorFloor> newFloors) {
            this.floors = newFloors != null ? new ArrayList<>(newFloors) : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FloorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_floor, parent, false);
            return new FloorViewHolder(view, mapViewModel, elevatorViewModel, basicViewModel, fragment);
        }

        @Override
        public void onBindViewHolder(@NonNull FloorViewHolder holder, int position) {
            holder.bind(floors.get(position));
        }

        @Override
        public int getItemCount() {
            return floors.size();
        }

        static class FloorViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvAlias;
            private final TextView tvPreridePosition;
            private final TextView tvWaitPosition;
            private final TextView tvRidePosition;
            private final TextView tvTransitionPosition;
            private final MaterialButton btnUpdatePreridePoint;
            private final MaterialButton btnUpdateWaitPoint;
            private final MaterialButton btnUpdateRidePoint;
            private final MaterialButton btnUpdateTransitionPoint;
            // Add reset button fields
            private final MaterialButton btnResetPreridePoint;
            private final MaterialButton btnResetWaitPoint;
            private final MaterialButton btnResetRidePoint;
            private final MaterialButton btnResetTransitionPoint;
            private final MaterialButton btnFloorSettings;
            private final ElevatorViewModel elevatorViewModel;
            private final MaterialButton btnToggleDetails;
            private final LinearLayout detailsContainer;
            private final ElevatorFragment fragment;
            private final MapViewModel mapViewModel;
            private final BasicViewModel basicViewModel;

            public FloorViewHolder(@NonNull View itemView, MapViewModel mapViewModel,
                                   ElevatorViewModel elevatorViewModel, BasicViewModel basicViewModel, ElevatorFragment fragment) {
                super(itemView);
                tvAlias = itemView.findViewById(R.id.tvAlias);
                tvPreridePosition = itemView.findViewById(R.id.tvPreridePosition);
                tvWaitPosition = itemView.findViewById(R.id.tvWaitPosition);
                tvRidePosition = itemView.findViewById(R.id.tvRidePosition);
                tvTransitionPosition = itemView.findViewById(R.id.tvTransitionPosition);
                btnUpdatePreridePoint = itemView.findViewById(R.id.btnUpdatePreridePoint);
                btnUpdateWaitPoint = itemView.findViewById(R.id.btnUpdateWaitPoint);
                btnUpdateRidePoint = itemView.findViewById(R.id.btnUpdateRidePoint);
                btnUpdateTransitionPoint = itemView.findViewById(R.id.btnUpdateTransitionPoint);
                // Initialize reset buttons
                btnResetPreridePoint = itemView.findViewById(R.id.btnResetPreridePoint);
                btnResetWaitPoint = itemView.findViewById(R.id.btnResetWaitPoint);
                btnResetRidePoint = itemView.findViewById(R.id.btnResetRidePoint);
                btnResetTransitionPoint = itemView.findViewById(R.id.btnResetTransitionPoint);
                btnFloorSettings = itemView.findViewById(R.id.btnFloorSettings);
                btnToggleDetails = itemView.findViewById(R.id.btnToggleDetails);
                detailsContainer = itemView.findViewById(R.id.detailsContainer);
                this.elevatorViewModel = elevatorViewModel;
                this.mapViewModel = mapViewModel;
                this.basicViewModel = basicViewModel;
                this.fragment = fragment;

                detailsContainer.setVisibility(View.GONE);
                btnToggleDetails.setText("▶");
                btnToggleDetails.setOnClickListener(v -> toggleDetails());
            }

            // 辅助方法获取字符串资源
            private String getString(int resId) {
                return itemView.getContext().getString(resId);
            }

            void bind(ElevatorViewModel.ElevatorFloor floor) {
                tvAlias.setText(floor.getAlias());
                updatePositionDisplay(floor);

                btnUpdatePreridePoint.setOnClickListener(v -> updatePreridePoint(floor));
                btnUpdateWaitPoint.setOnClickListener(v -> updateWaitPoint(floor));
                btnUpdateRidePoint.setOnClickListener(v -> updateRidePoint(floor));
                btnUpdateTransitionPoint.setOnClickListener(v -> updateTransitionPoint(floor));

                // Add reset button click handlers
                btnResetPreridePoint.setOnClickListener(v -> resetPoint(floor, "preride"));
                btnResetWaitPoint.setOnClickListener(v -> resetPoint(floor, "wait"));
                btnResetRidePoint.setOnClickListener(v -> resetPoint(floor, "ride"));
                btnResetTransitionPoint.setOnClickListener(v -> resetPoint(floor, "transition"));

                btnFloorSettings.setOnClickListener(v -> showFloorSettingsDialog(floor));
            }

            // Add reset method
            private void resetPoint(ElevatorViewModel.ElevatorFloor floor, String pointType) {
                Position point = null;
                String pointName = "";

                switch (pointType) {
                    case "preride":
                        point = floor.getPreridePoint();
                        pointName = getString(R.string.preride_point);
                        break;
                    case "wait":
                        point = floor.getWaitPoint();
                        pointName = getString(R.string.wait_point);
                        break;
                    case "ride":
                        point = floor.getRidePoint();
                        pointName = getString(R.string.ride_point);
                        break;
                    case "transition":
                        point = floor.getTransitionPoint();
                        pointName = getString(R.string.transition_point);
                        break;
                }

                if (point != null) {
                    // Reset coordinates to zero
                    point.setPosX(0.0);
                    point.setPosY(0.0);
                    point.setYaw(0.0);

                    updatePositionDisplay(floor);
                    elevatorViewModel.updateElevatorFloor(floor);

                    // Update MapPoints with reset coordinates
                    switch (pointType) {
                        case "preride":
                            updateMapPointsWithPreridePoint(floor);
                            break;
                        case "wait":
                            updateMapPointsWithWaitPoint(floor);
                            break;
                        case "ride":
                            updateMapPointsWithRidePoint(floor);
                            break;
                        case "transition":
                            updateMapPointsWithTransitionPoint(floor);
                            break;
                    }

                    Toast.makeText(itemView.getContext(), itemView.getContext().getString(R.string.elevator_coordinate_reset, pointName), Toast.LENGTH_SHORT).show();
                }
            }

            private void toggleDetails() {
                if (detailsContainer.getVisibility() == View.VISIBLE) {
                    detailsContainer.setVisibility(View.GONE);
                    btnToggleDetails.setText("▶");
                } else {
                    detailsContainer.setVisibility(View.VISIBLE);
                    btnToggleDetails.setText("▼");
                }
            }

            private void updatePositionDisplay(ElevatorViewModel.ElevatorFloor floor) {
                Position preridePoint = floor.getPreridePoint();
                Position waitPoint = floor.getWaitPoint();
                Position ridePoint = floor.getRidePoint();
                Position transitionPoint = floor.getTransitionPoint();

                if (preridePoint != null) {
                    String prerideText = String.format("X:%.2f  Y:%.2f  T:%.2f",
                            preridePoint.getPosX(), preridePoint.getPosY(), preridePoint.getYaw());
                    tvPreridePosition.setText(prerideText);
                }

                if (waitPoint != null) {
                    String waitText = String.format("X:%.2f  Y:%.2f  T:%.2f",
                            waitPoint.getPosX(), waitPoint.getPosY(), waitPoint.getYaw());
                    tvWaitPosition.setText(waitText);
                }

                if (ridePoint != null) {
                    String rideText = String.format("X:%.2f  Y:%.2f  T:%.2f",
                            ridePoint.getPosX(), ridePoint.getPosY(), ridePoint.getYaw());
                    tvRidePosition.setText(rideText);
                }

                if (transitionPoint != null) {
                    String transitionText = String.format("X:%.2f  Y:%.2f  T:%.2f",
                            transitionPoint.getPosX(), transitionPoint.getPosY(), transitionPoint.getYaw());
                    tvTransitionPosition.setText(transitionText);
                }
            }

            private void updatePreridePoint(ElevatorViewModel.ElevatorFloor floor) {
                Position currentPosition = sharedViewModel.getCurrentPosition();

                // VERIFICATION: Check if current building matches floor's building
                String currentBuilding = mapViewModel.getCurrentBuilding();
                String floorBuilding = floor.getBuildingId();
                if (floorBuilding == null) floorBuilding = "default";

                if (currentBuilding == null || !currentBuilding.equals(floorBuilding)) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_cannot_update_other_building),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                // VERIFICATION: Check if current floor matches the floor we're trying to update
                int currentFloorNum = mapViewModel.getCurrentFloor();
                if (currentFloorNum != floor.getFloor()) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_update_point_on_floor,
                                    floor.getFloor(), currentFloorNum),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (currentPosition != null) {
                    floor.getPreridePoint().setName("elevator_preride_" + floor.getFloor() + "_" + floor.getElevatorConfigId());
                    floor.getPreridePoint().setPosX(currentPosition.getPosX());
                    floor.getPreridePoint().setPosY(currentPosition.getPosY());
                    floor.getPreridePoint().setYaw(currentPosition.getYaw());
                    floor.getPreridePoint().setType(2);
                    floor.getPreridePoint().setFloor(Integer.toString(floor.getFloor()));

                    // Set the current map name
                    String currentMap = mapViewModel.getCurrentMap();
                    String fullMapName = generateMapName(currentMap, currentBuilding, floor.getFloor());
                    floor.getPreridePoint().setMapName(fullMapName);

                    // Ensure elevator info is preserved
                    if (floor.getPreridePoint().getElevatorInfo() == null) {
                        // Try to find existing elevator info from MapPoints
                        Position existingPoint = fragment.findElevatorPointByFloorAndType(floor.getBuildingId(), floor.getFloor(), 2);
                        if (existingPoint != null && existingPoint.getElevatorInfo() != null) {
                            floor.getPreridePoint().setElevatorInfo(existingPoint.getElevatorInfo());
                        }
                    }

                    updatePositionDisplay(floor);
                    elevatorViewModel.updateElevatorFloor(floor);

                    // Update MapPoints with the updated preride point
                    updateMapPointsWithPreridePoint(floor);

                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_wait_point_updated), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_cannot_get_position), Toast.LENGTH_SHORT).show();
                }
            }

            private void updateWaitPoint(ElevatorViewModel.ElevatorFloor floor) {
                Position currentPosition = sharedViewModel.getCurrentPosition();

                // VERIFICATION: Check if current building matches floor's building
                String currentBuilding = mapViewModel.getCurrentBuilding();
                String floorBuilding = floor.getBuildingId();
                if (floorBuilding == null) floorBuilding = "default";

                if (currentBuilding == null || !currentBuilding.equals(floorBuilding)) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_cannot_update_other_building),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                // VERIFICATION: Check if current floor matches the floor we're trying to update
                int currentFloorNum = mapViewModel.getCurrentFloor();
                if (currentFloorNum != floor.getFloor()) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_update_point_on_floor,
                                    floor.getFloor(), currentFloorNum),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (currentPosition != null) {
                    floor.getWaitPoint().setName("elevator_wait_" + floor.getFloor() + "_" + floor.getElevatorConfigId());
                    floor.getWaitPoint().setPosX(currentPosition.getPosX());
                    floor.getWaitPoint().setPosY(currentPosition.getPosY());
                    floor.getWaitPoint().setYaw(currentPosition.getYaw());
                    floor.getWaitPoint().setType(3);
                    floor.getWaitPoint().setFloor(Integer.toString(floor.getFloor()));

                    // Set the current map name
                    String currentMap = mapViewModel.getCurrentMap();
                    String fullMapName = generateMapName(currentMap, currentBuilding, floor.getFloor());
                    floor.getWaitPoint().setMapName(fullMapName);

                    // Ensure elevator info is preserved
                    if (floor.getWaitPoint().getElevatorInfo() == null) {
                        // Try to find existing elevator info from MapPoints
                        Position existingPoint = fragment.findElevatorPointByFloorAndType(floor.getBuildingId(), floor.getFloor(), 3);
                        if (existingPoint != null && existingPoint.getElevatorInfo() != null) {
                            floor.getWaitPoint().setElevatorInfo(existingPoint.getElevatorInfo());
                        }
                    }

                    updatePositionDisplay(floor);
                    elevatorViewModel.updateElevatorFloor(floor);

                    // Update MapPoints with the updated wait point
                    updateMapPointsWithWaitPoint(floor);

                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_wait_point_updated), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_cannot_get_position), Toast.LENGTH_SHORT).show();
                }
            }

            private void updateRidePoint(ElevatorViewModel.ElevatorFloor floor) {
                Position currentPosition = sharedViewModel.getCurrentPosition();

                // VERIFICATION: Check if current building matches floor's building
                String currentBuilding = mapViewModel.getCurrentBuilding();
                String floorBuilding = floor.getBuildingId();
                if (floorBuilding == null) floorBuilding = "default";

                if (currentBuilding == null || !currentBuilding.equals(floorBuilding)) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_cannot_update_other_building),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                // VERIFICATION: Check if current floor matches the floor we're trying to update
                int currentFloorNum = mapViewModel.getCurrentFloor();
                if (currentFloorNum != floor.getFloor()) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_update_point_on_floor,
                                    floor.getFloor(), currentFloorNum),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (currentPosition != null) {
                    floor.getRidePoint().setName("elevator_ride_" + floor.getFloor() + "_" + floor.getElevatorConfigId());
                    floor.getRidePoint().setPosX(currentPosition.getPosX());
                    floor.getRidePoint().setPosY(currentPosition.getPosY());
                    floor.getRidePoint().setYaw(currentPosition.getYaw());
                    floor.getRidePoint().setType(4);
                    floor.getRidePoint().setFloor(Integer.toString(floor.getFloor()));

                    // Set the current map name
                    String currentMap = mapViewModel.getCurrentMap();
                    String fullMapName = generateMapName(currentMap, currentBuilding, floor.getFloor());
                    floor.getRidePoint().setMapName(fullMapName);

                    // Ensure elevator info is preserved
                    if (floor.getRidePoint().getElevatorInfo() == null) {
                        // Try to find existing elevator info from MapPoints
                        Position existingPoint = fragment.findElevatorPointByFloorAndType(floor.getBuildingId(), floor.getFloor(), 4);
                        if (existingPoint != null && existingPoint.getElevatorInfo() != null) {
                            floor.getRidePoint().setElevatorInfo(existingPoint.getElevatorInfo());
                        }
                    }

                    updatePositionDisplay(floor);
                    elevatorViewModel.updateElevatorFloor(floor);

                    // Update MapPoints with the updated ride point
                    updateMapPointsWithRidePoint(floor);

                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_ride_point_updated), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_cannot_get_position), Toast.LENGTH_SHORT).show();
                }
            }

            private void updateTransitionPoint(ElevatorViewModel.ElevatorFloor floor) {
                Position currentPosition = sharedViewModel.getCurrentPosition();

                // VERIFICATION: Check if current building matches floor's building
                String currentBuilding = mapViewModel.getCurrentBuilding();
                String floorBuilding = floor.getBuildingId();
                if (floorBuilding == null) floorBuilding = "default";

                if (currentBuilding == null || !currentBuilding.equals(floorBuilding)) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_cannot_update_other_building),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                // VERIFICATION: Check if current floor matches the floor we're trying to update
                int currentFloorNum = mapViewModel.getCurrentFloor();
                if (currentFloorNum != floor.getFloor()) {
                    Toast.makeText(itemView.getContext(),
                            itemView.getContext().getString(R.string.elevator_update_point_on_floor,
                                    floor.getFloor(), currentFloorNum),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (currentPosition != null) {
                    Position transitionPoint = floor.getTransitionPoint();
                    if (transitionPoint == null) {
                        transitionPoint = new Position();
                        floor.setTransitionPoint(transitionPoint);
                    }

                    transitionPoint.setName("elevator_transition_" + floor.getFloor() + "_" + floor.getElevatorConfigId());
                    transitionPoint.setPosX(currentPosition.getPosX());
                    transitionPoint.setPosY(currentPosition.getPosY());
                    transitionPoint.setYaw(currentPosition.getYaw());
                    transitionPoint.setType(14);
                    transitionPoint.setFloor(Integer.toString(floor.getFloor()));

                    String currentMap = mapViewModel.getCurrentMap();
                    String buildingId = floor.getBuildingId();
                    String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floor.getFloor());
                    transitionPoint.setMapName(fullMapName);

                    // Ensure elevator info is preserved
                    if (floor.getTransitionPoint().getElevatorInfo() == null) {
                        // Try to find existing elevator info from MapPoints
                        Position existingPoint = fragment.findElevatorPointByFloorAndType(floor.getBuildingId(), floor.getFloor(), 14);
                        if (existingPoint != null && existingPoint.getElevatorInfo() != null) {
                            floor.getTransitionPoint().setElevatorInfo(existingPoint.getElevatorInfo());
                        }
                    }

                    updatePositionDisplay(floor);
                    elevatorViewModel.updateElevatorFloor(floor);
                    updateMapPointsWithTransitionPoint(floor);

                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_transition_point_updated), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(itemView.getContext(), getString(R.string.elevator_cannot_get_position), Toast.LENGTH_SHORT).show();
                }
            }

            /**
             * Update MapPoints with the updated preride point - PRESERVE other points
             */
            private void updateMapPointsWithPreridePoint(ElevatorViewModel.ElevatorFloor floor) {
                String currentMap = mapViewModel.getCurrentMap();
                String buildingId = floor.getBuildingId();
                if (buildingId == null) buildingId = "default";
                int floorNum = floor.getFloor();

                if (currentMap == null || currentMap.isEmpty()) {
                    return;
                }

                // Get existing wait points for this floor
                List<Position> currentPreridePoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                List<Position> updatedPreridePoints = new ArrayList<>();

                String targetPointName = "elevator_preride_" + floorNum + "_" + floor.getElevatorConfigId();
                boolean found = false;

                for (Position point : currentPreridePoints) {
                    if (point.getName() != null && point.getName().equals(targetPointName)) {
                        // Update the existing point with new coordinates, but preserve all other properties
                        point.setPosX(floor.getPreridePoint().getPosX());
                        point.setPosY(floor.getPreridePoint().getPosY());
                        point.setYaw(floor.getPreridePoint().getYaw());
                        point.setMapName(floor.getPreridePoint().getMapName());
                        // IMPORTANT: Preserve elevator info
                        if (floor.getPreridePoint().getElevatorInfo() != null) {
                            point.setElevatorInfo(floor.getPreridePoint().getElevatorInfo());
                        }
                        updatedPreridePoints.add(point);
                        found = true;
                    } else {
                        updatedPreridePoints.add(point);
                    }
                }

                if (!found && floor.getPreridePoint() != null) {
                    // Only add if the point has valid coordinates (not all zeros)
                    if (floor.getPreridePoint().getPosX() != 0 || floor.getPreridePoint().getPosY() != 0) {
                        updatedPreridePoints.add(floor.getPreridePoint());
                    }
                }

                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 2, updatedPreridePoints);
            }

            /**
             * Update MapPoints with the updated wait point - PRESERVE other points
             */
            private void updateMapPointsWithWaitPoint(ElevatorViewModel.ElevatorFloor floor) {
                String currentMap = mapViewModel.getCurrentMap();
                String buildingId = floor.getBuildingId();
                if (buildingId == null) buildingId = "default";
                int floorNum = floor.getFloor();

                if (currentMap == null || currentMap.isEmpty()) {
                    return;
                }

                // Get existing wait points for this floor
                List<Position> currentWaitPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                List<Position> updatedWaitPoints = new ArrayList<>();

                String targetPointName = "elevator_wait_" + floorNum + "_" + floor.getElevatorConfigId();
                boolean found = false;

                for (Position point : currentWaitPoints) {
                    if (point.getName() != null && point.getName().equals(targetPointName)) {
                        // Update the existing point with new coordinates, but preserve all other properties
                        point.setPosX(floor.getWaitPoint().getPosX());
                        point.setPosY(floor.getWaitPoint().getPosY());
                        point.setYaw(floor.getWaitPoint().getYaw());
                        point.setMapName(floor.getWaitPoint().getMapName());
                        // IMPORTANT: Preserve elevator info
                        if (floor.getWaitPoint().getElevatorInfo() != null) {
                            point.setElevatorInfo(floor.getWaitPoint().getElevatorInfo());
                        }
                        updatedWaitPoints.add(point);
                        found = true;
                    } else {
                        updatedWaitPoints.add(point);
                    }
                }

                if (!found && floor.getWaitPoint() != null) {
                    // Only add if the point has valid coordinates (not all zeros)
                    if (floor.getWaitPoint().getPosX() != 0 || floor.getWaitPoint().getPosY() != 0) {
                        updatedWaitPoints.add(floor.getWaitPoint());
                    }
                }

                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 3, updatedWaitPoints);
            }

            /**
             * Update MapPoints with the updated ride point - PRESERVE other points
             */
            private void updateMapPointsWithRidePoint(ElevatorViewModel.ElevatorFloor floor) {
                String currentMap = mapViewModel.getCurrentMap();
                String buildingId = floor.getBuildingId();
                if (buildingId == null) buildingId = "default";
                int floorNum = floor.getFloor();

                if (currentMap == null || currentMap.isEmpty()) {
                    return;
                }

                // Get existing ride points for this floor
                List<Position> currentRidePoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                List<Position> updatedRidePoints = new ArrayList<>();

                String targetPointName = "elevator_ride_" + floorNum + "_" + floor.getElevatorConfigId();
                boolean found = false;

                for (Position point : currentRidePoints) {
                    if (point.getName() != null && point.getName().equals(targetPointName)) {
                        // Update the existing point with new coordinates, but preserve all other properties
                        point.setPosX(floor.getRidePoint().getPosX());
                        point.setPosY(floor.getRidePoint().getPosY());
                        point.setYaw(floor.getRidePoint().getYaw());
                        point.setMapName(floor.getRidePoint().getMapName());
                        // IMPORTANT: Preserve elevator info
                        if (floor.getRidePoint().getElevatorInfo() != null) {
                            point.setElevatorInfo(floor.getRidePoint().getElevatorInfo());
                        }
                        updatedRidePoints.add(point);
                        found = true;
                    } else {
                        updatedRidePoints.add(point);
                    }
                }

                if (!found && floor.getRidePoint() != null) {
                    // Only add if the point has valid coordinates (not all zeros)
                    if (floor.getRidePoint().getPosX() != 0 || floor.getRidePoint().getPosY() != 0) {
                        updatedRidePoints.add(floor.getRidePoint());
                    }
                }

                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 4, updatedRidePoints);
            }

            /**
             * Update MapPoints with the updated transition point - PRESERVE other points
             */
            private void updateMapPointsWithTransitionPoint(ElevatorViewModel.ElevatorFloor floor) {
                String currentMap = mapViewModel.getCurrentMap();
                String buildingId = floor.getBuildingId();
                if (buildingId == null) buildingId = "default";
                int floorNum = floor.getFloor();

                if (currentMap == null || currentMap.isEmpty()) return;

                // Get existing transition points for this floor
                List<Position> currentTransitionPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                List<Position> updatedTransitionPoints = new ArrayList<>();

                String targetPointName = "elevator_transition_" + floorNum + "_" + floor.getElevatorConfigId();
                boolean found = false;

                for (Position point : currentTransitionPoints) {
                    if (point.getName() != null && point.getName().equals(targetPointName)) {
                        // Update the existing point
                        point.setPosX(floor.getTransitionPoint().getPosX());
                        point.setPosY(floor.getTransitionPoint().getPosY());
                        point.setYaw(floor.getTransitionPoint().getYaw());
                        point.setMapName(floor.getTransitionPoint().getMapName());
                        if (floor.getTransitionPoint().getElevatorInfo() != null) {
                            point.setElevatorInfo(floor.getTransitionPoint().getElevatorInfo());
                        }
                        updatedTransitionPoints.add(point);
                        found = true;
                    } else {
                        updatedTransitionPoints.add(point);
                    }
                }

                if (!found && floor.getTransitionPoint() != null) {
                    if (floor.getTransitionPoint().getPosX() != 0 || floor.getTransitionPoint().getPosY() != 0) {
                        updatedTransitionPoints.add(floor.getTransitionPoint());
                    }
                }

                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 14, updatedTransitionPoints);
            }

            private void showFloorSettingsDialog(ElevatorViewModel.ElevatorFloor floor) {
                AlertDialog.Builder builder = new AlertDialog.Builder(itemView.getContext());
                View dialogView = LayoutInflater.from(itemView.getContext())
                        .inflate(R.layout.dialog_floor_settings, null);
                builder.setView(dialogView);

                EditText etAlias = dialogView.findViewById(R.id.etAlias);
                EditText etFloor = dialogView.findViewById(R.id.etFloor);
                Button btnSave = dialogView.findViewById(R.id.btnSave);
                Button btnDeleteFloor = dialogView.findViewById(R.id.btnDeleteFloor);
                Button btnAddFloor = dialogView.findViewById(R.id.btnAddFloor);
                Button btnGoToFloor = dialogView.findViewById(R.id.btnGoToFloor);

                etAlias.setText(floor.getAlias());
                etFloor.setText(String.valueOf(floor.getFloor()));

                AlertDialog dialog = builder.create();

                // Get the elevator config to use its channel and address
                ElevatorViewModel.ElevatorConfig elevatorConfig = null;
                String configId = floor.getElevatorConfigId();
                if (configId != null) {
                    List<ElevatorViewModel.ElevatorConfig> configs = elevatorViewModel.getElevatorConfigs().getValue();
                    if (configs != null) {
                        for (ElevatorViewModel.ElevatorConfig config : configs) {
                            if (config.getId().equals(configId)) {
                                elevatorConfig = config;
                                break;
                            }
                        }
                    }
                }

                final int channel = elevatorConfig != null ? elevatorConfig.getChannel() : 9;
                final int address = elevatorConfig != null ? elevatorConfig.getAddress() : 1;

                int robotId = basicViewModel.getRobotId().getValue() != null ? basicViewModel.getRobotId().getValue() : 1;
                int loraChannel = basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0;
                int loraAddress = basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0;

                btnGoToFloor.setOnClickListener(v -> {
                    // VERIFICATION: Check if current building matches floor's building
                    String currentBuilding = mapViewModel.getCurrentBuilding();
                    String floorBuilding = floor.getBuildingId();
                    if (floorBuilding == null) floorBuilding = "default";

                    if (currentBuilding == null || !currentBuilding.equals(floorBuilding)) {
                        Toast.makeText(itemView.getContext(),
                                itemView.getContext().getString(R.string.elevator_cannot_control_other_building),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Create progress dialog
                    ProgressDialog progressDialog = new ProgressDialog(itemView.getContext());
                    progressDialog.setMessage(getString(R.string.going_to_floor));
                    progressDialog.setCancelable(false);
                    progressDialog.show();

                    new Thread(() -> {
                        try {
                            // Prepare command parameters with configured channel and address
                            Map<String, Object> hexParams = new HashMap<>();
                            hexParams.put("floor", String.format("%02X", floor.getFloor()));
                            hexParams.put("channel", String.format("%02X", channel));
                            hexParams.put("address", String.format("%02X", address));
                            hexParams.put("robotId", String.format("%02X", robotId));
                            hexParams.put("robotChannel", String.format("%02X", loraChannel));
                            hexParams.put("robotAddress", String.format("%02X", loraAddress));

                            // Build and send command
                            String command = CommandBuilder.buildHexMessage(OP_ELEVATOR_CALL, hexParams);
                            String response = sharedViewModel.loraCommunicator.sendMessage(command, true);

                            itemView.post(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(itemView.getContext(),
                                        String.format(getString(R.string.call_floor_success), floor.getFloor(), response),
                                        Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            itemView.post(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(itemView.getContext(),
                                        String.format(getString(R.string.call_floor_error), e.getMessage()),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    }).start();
                });

                btnSave.setOnClickListener(v -> {
                    String alias = etAlias.getText().toString();
                    if (TextUtils.isEmpty(alias)) {
                        Toast.makeText(itemView.getContext(), getString(R.string.error_alias_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Preserve the elevatorConfigId
                    String savedConfigId = floor.getElevatorConfigId();
                    String saveBuildingId = floor.getBuildingId();

                    floor.setAlias(alias);
                    try {
                        int floorNum = Integer.parseInt(etFloor.getText().toString());
                        floor.setFloor(floorNum);

                        // Restore the config ID if it was lost
                        if (floor.getElevatorConfigId() == null && savedConfigId != null) {
                            floor.setElevatorConfigId(savedConfigId);
                            floor.setBuildingId(saveBuildingId);
                        }

                        // Update the floor number in the position objects
                        floor.getPreridePoint().setFloor(String.valueOf(floorNum));
                        floor.getWaitPoint().setFloor(String.valueOf(floorNum));
                        floor.getRidePoint().setFloor(String.valueOf(floorNum));
                        floor.getTransitionPoint().setFloor(String.valueOf(floorNum));

                        // Update the names to reflect the new floor number
                        floor.getPreridePoint().setName("elevator_preride_" + floorNum + "_" + floor.getElevatorConfigId());
                        floor.getWaitPoint().setName("elevator_wait_" + floorNum + "_" + floor.getElevatorConfigId());
                        floor.getRidePoint().setName("elevator_ride_" + floorNum + "_" + floor.getElevatorConfigId());
                        floor.getTransitionPoint().setName("elevator_transition_" + floorNum + "_" + floor.getElevatorConfigId());

                    } catch (NumberFormatException e) {
                        Toast.makeText(itemView.getContext(), getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    elevatorViewModel.updateElevatorFloor(floor);

                    // Update MapPoints with the updated floor information
                    updateMapPointsWithPreridePoint(floor);
                    updateMapPointsWithWaitPoint(floor);
                    updateMapPointsWithRidePoint(floor);
                    updateMapPointsWithTransitionPoint(floor);

                    dialog.dismiss();
                });

                btnAddFloor.setOnClickListener(v -> {
                    ElevatorViewModel.ElevatorFloor newFloor = new ElevatorViewModel.ElevatorFloor();
                    newFloor.setAlias(getString(R.string.new_floor_default_name));
                    newFloor.setFloor(floor.getFloor() + 1);

                    // We need to get the current elevator config ID
                    // The floor object has a reference to its parent config
                    String savedConfigId = floor.getElevatorConfigId();
                    String saveBuildingId = floor.getBuildingId();
                    if (savedConfigId != null) {
                        newFloor.setElevatorConfigId(savedConfigId);
                        newFloor.setBuildingId(saveBuildingId);
                    }

                    elevatorViewModel.addElevatorFloorAfter(floor.getId(), newFloor);
                    dialog.dismiss();
                });

                btnDeleteFloor.setOnClickListener(v -> {
                    fragment.showConfirmDeleteDialog(itemView.getContext(),
                            getString(R.string.confirm_delete_floor_message), () -> {
                                // Remove the floor points from MapPoints
                                removeFloorPointsFromMapPoints(floor);
                                elevatorViewModel.deleteElevatorFloor(floor.getId());
                                dialog.dismiss();
                            });
                });
                dialog.show();
            }

            /**
             * Remove floor points from MapPoints when a floor is deleted
             */
            /**
             * Remove floor points from MapPoints when a floor is deleted
             */
            private void removeFloorPointsFromMapPoints(ElevatorViewModel.ElevatorFloor floor) {
                String currentMap = mapViewModel.getCurrentMap();
                int floorNum = floor.getFloor();
                String buildingId = floor.getBuildingId();
                if (buildingId == null) buildingId = "default";

                if (currentMap == null || currentMap.isEmpty()) {
                    return;
                }

                String preridePointName = "elevator_preride_" + floorNum + "_" + floor.getElevatorConfigId();
                String waitPointName = "elevator_wait_" + floorNum + "_" + floor.getElevatorConfigId();
                String ridePointName = "elevator_ride_" + floorNum + "_" + floor.getElevatorConfigId();
                String transitionPointName = "elevator_transition_" + floorNum + "_" + floor.getElevatorConfigId();

                // Remove preride point
                List<Position> currentPreridePoints = mapViewModel.getElevatorPreridePoints(buildingId, floorNum);
                List<Position> updatedPreridePoints = new ArrayList<>();
                for (Position point : currentPreridePoints) {
                    if (point.getName() != null && !point.getName().equals(preridePointName)) {
                        updatedPreridePoints.add(point);
                    }
                }
                // Use buildingId parameter
                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 2, updatedPreridePoints);

                // Remove wait point
                List<Position> currentWaitPoints = mapViewModel.getElevatorWaitPoints(buildingId, floorNum);
                List<Position> updatedWaitPoints = new ArrayList<>();
                for (Position point : currentWaitPoints) {
                    if (point.getName() != null && !point.getName().equals(waitPointName)) {
                        updatedWaitPoints.add(point);
                    }
                }
                // Use buildingId parameter
                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 3, updatedWaitPoints);

                // Remove ride point
                List<Position> currentRidePoints = mapViewModel.getElevatorRidePoints(buildingId, floorNum);
                List<Position> updatedRidePoints = new ArrayList<>();
                for (Position point : currentRidePoints) {
                    if (point.getName() != null && !point.getName().equals(ridePointName)) {
                        updatedRidePoints.add(point);
                    }
                }
                // Use buildingId parameter
                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 4, updatedRidePoints);

                // Remove transition point
                List<Position> currentTransitionPoints = mapViewModel.getElevatorTransitionPoints(buildingId, floorNum);
                List<Position> updatedTransitionPoints = new ArrayList<>();
                for (Position point : currentTransitionPoints) {
                    if (point.getName() != null && !point.getName().equals(transitionPointName)) {
                        updatedTransitionPoints.add(point);
                    }
                }
                // Use buildingId parameter
                mapViewModel.updateCurrentMapPoints(buildingId, currentMap, floorNum, 14, updatedTransitionPoints);

                Log.d(TAG, "Removed floor points for floor " + floorNum + " in building " + buildingId);
            }
        }
    }

    // Inner classes: ElevatorAdapter, ElevatorViewHolder, FloorAdapter, FloorViewHolder
    // (These remain largely the same but need updates for transition points and building support)

    private static class ElevatorData {
        String id;
        String buildingId;
        int channel;
        int address;
        String configId;
        Set<Integer> floors = new HashSet<>();
        Map<Integer, Position> waitPoints = new HashMap<>();
        Map<Integer, Position> preridePoints = new HashMap<>();  // NEW
        Map<Integer, Position> ridePoints = new HashMap<>();
        Map<Integer, Position> transitionPoints = new HashMap<>();

        @Override
        public String toString() {
            return "ElevatorData{id='" + id + "', configId='" + configId + "', channel=" + channel +
                    ", address=" + address + ", buildingId=" + buildingId + ", floors=" + floors +
                    ", waitPoints=" + waitPoints.keySet() + ", preridePoints=" + preridePoints.keySet() +
                    ", ridePoints=" + ridePoints.keySet() + ", transitionPoints=" + transitionPoints.keySet() + "}";
        }
    }
}