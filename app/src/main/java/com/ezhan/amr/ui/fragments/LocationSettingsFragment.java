package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Building;
import com.ezhan.amr.data.datatype.FloorPoints;
import com.ezhan.amr.data.datatype.MarkerData;
import com.ezhan.amr.data.datatype.MultiBuildingMapPoints;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.utils.PositionDisplayNameHelper;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LocationSettingsFragment extends Fragment {
    private static final String TAG = "LocationFragment";

    // UI Components
    private ImageView ivFixedNoData, ivWorkNoData;
    private TextView tvFixedNoData, tvWorkNoData;
    private View underlinePoint, underlineFixed;
    private MaterialButton tabPointSetting, tabFixedLocation;
    private LinearLayout commonActions;
    private MaterialButton btnAddLocation, btnFetchLocation, btnUploadLocation;
    private MapViewModel mapViewModel;
    private SharedViewModel sharedViewModel;

    // Display for current map, building and floor
    private TextView tvCurrentMap, tvCurrentBuilding, tvCurrentFloor;

    // RecyclerViews
    private RecyclerView rvWorkPoints, rvFixedLocation;
    private WorkPointAdapter workPointAdapter;
    private FixedPointAdapter fixedPointAdapter;

    // Containers
    private LinearLayout workPointsContainer;
    private FrameLayout fixedLocationContainer;

    // Current map points data
    private MultiBuildingMapPoints currentMapPoints;
    private int currentFloor = 0;
    private String currentMap = "";
    private String currentBuilding = "default";

    // Building tabs for work points
    private TabLayout tabBuildingTabs;
    private List<String> availableBuildings = new ArrayList<>();
    private String selectedBuilding = "default";
    private boolean isUpdatingWorkBuildingTabs = false;
    private boolean isUserSelectedWorkBuilding = false;

    // Floor tabs for work points
    private TabLayout tabFloorTabs;
    private List<Integer> availableFloors = new ArrayList<>();
    private int selectedFloorTab = 0;
    private boolean isUpdatingWorkFloorTabs = false;
    private boolean isUserSelectedWorkFloor = false;

    // Building tabs for fixed points
    private TabLayout tabFixedBuildingTabs;
    private List<String> fixedAvailableBuildings = new ArrayList<>();
    private String selectedFixedBuilding = "default";
    private boolean isUpdatingFixedBuildingTabs = false;
    private boolean isUserSelectedFixedBuilding = false;

    // Floor tabs for fixed points
    private TabLayout tabFixedFloorTabs;
    private List<Integer> fixedAvailableFloors = new ArrayList<>();
    private int selectedFixedFloorTab = 0;
    private boolean isUpdatingFixedFloorTabs = false;
    private boolean isUserSelectedFixedFloor = false;
    private CommandWebSocketClient commandClient;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_location_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mapViewModel = MyApplication.getInstance().getMapViewModel();
        sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        commandClient = sharedViewModel.getCommandClient();

        initViews(view);
        setupTabSelection();
        setupButtonListeners();
        observeData();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Refreshing data from currentMapPoints");

        // Refresh current map, building and floor values
        String latestMap = mapViewModel.getCurrentMapLiveData().getValue();
        if (latestMap != null && !latestMap.isEmpty()) {
            this.currentMap = latestMap;
            updateCurrentMapDisplay();
        }

        String latestBuilding = mapViewModel.getCurrentBuildingLiveData().getValue();
        if (latestBuilding != null && !latestBuilding.isEmpty()) {
            this.currentBuilding = latestBuilding;
            updateCurrentBuildingDisplay();
        }

        Integer latestFloor = mapViewModel.getCurrentFloorLiveData().getValue();
        if (latestFloor != null) {
            this.currentFloor = latestFloor;
            updateCurrentFloorDisplay();
        }

        // Refresh currentMapPoints from LiveData (may have been updated from config file)
        MultiBuildingMapPoints latestMapPoints = mapViewModel.getCurrentMapPoints().getValue();
        if (latestMapPoints != null) {
            Log.d(TAG, "onResume: Got latestMapPoints from LiveData, buildings=" +
                    latestMapPoints.getAllBuildings().size() +
                    ", totalPoints=" + latestMapPoints.getTotalPositionCount());
            this.currentMapPoints = latestMapPoints;
        }

        // Force refresh of all displayed data
        refreshAllDisplayData();
    }

    private void resetUserSelectionFlags() {
        Log.d(TAG, "resetUserSelectionFlags: Resetting all user selection flags");
        isUserSelectedWorkBuilding = false;
        isUserSelectedWorkFloor = false;
        isUserSelectedFixedBuilding = false;
        isUserSelectedFixedFloor = false;
    }

    private void initViews(View view) {
        // Tabs
        tabPointSetting = view.findViewById(R.id.tabPointSetting);
        tabFixedLocation = view.findViewById(R.id.tabFixedLocation);
        underlinePoint = view.findViewById(R.id.underlinePoint);
        underlineFixed = view.findViewById(R.id.underlineFixed);

        // Current map, building and floor display
        tvCurrentMap = view.findViewById(R.id.tvCurrentMap);
        tvCurrentBuilding = view.findViewById(R.id.tvCurrentBuilding);
        tvCurrentFloor = view.findViewById(R.id.tvCurrentFloor);

        // Common actions
        commonActions = view.findViewById(R.id.commonActions);
        btnAddLocation = view.findViewById(R.id.btnAddLocation);
        btnFetchLocation = view.findViewById(R.id.btnFetchFromMap);
        btnUploadLocation = view.findViewById(R.id.btnUploadLocation);

        // Work points section
        workPointsContainer = view.findViewById(R.id.workPointsContainer);
        tabBuildingTabs = view.findViewById(R.id.tabBuildingTabs);
        tabFloorTabs = view.findViewById(R.id.tabFloorTabs);
        rvWorkPoints = view.findViewById(R.id.rvWorkPoints);
        ivWorkNoData = view.findViewById(R.id.ivWorkNoData);
        tvWorkNoData = view.findViewById(R.id.tvWorkNoData);

        // Fixed points section
        fixedLocationContainer = view.findViewById(R.id.fixedLocationContainer);
        tabFixedBuildingTabs = view.findViewById(R.id.tabFixedBuildingTabs);
        tabFixedFloorTabs = view.findViewById(R.id.tabFixedFloorTabs);
        rvFixedLocation = view.findViewById(R.id.rvFixedLocation);
        ivFixedNoData = view.findViewById(R.id.ivFixedNoData);
        tvFixedNoData = view.findViewById(R.id.tvFixedNoData);

        // Setup RecyclerViews
        rvWorkPoints.setLayoutManager(new LinearLayoutManager(requireContext()));
        workPointAdapter = new WorkPointAdapter();
        rvWorkPoints.setAdapter(workPointAdapter);

        rvFixedLocation.setLayoutManager(new LinearLayoutManager(requireContext()));
        fixedPointAdapter = new FixedPointAdapter();
        rvFixedLocation.setAdapter(fixedPointAdapter);

        // Initially show work points section
        showPointLocationContent();

        // Initialize display with current values if available
        updateCurrentMapDisplay();
        updateCurrentBuildingDisplay();
        updateCurrentFloorDisplay();
    }

    private void setupTabSelection() {
        updateTabSelection(tabPointSetting, true);

        tabPointSetting.setOnClickListener(v -> {
            Log.d(TAG, "setupTabSelection: Point location tab clicked");
            updateTabSelection(tabPointSetting, true);
            showPointLocationContent();
        });

        tabFixedLocation.setOnClickListener(v -> {
            Log.d(TAG, "setupTabSelection: Fixed location tab clicked");
            updateTabSelection(tabFixedLocation, false);
            showFixedLocationContent();
        });
    }

    private void setupButtonListeners() {
        btnAddLocation.setOnClickListener(v -> showAddLocationDialog());
        btnFetchLocation.setOnClickListener(v -> showDownloadConfirmationDialog());
        btnUploadLocation.setOnClickListener(v -> showUploadConfirmationDialog());
    }

    private void observeData() {
        // Observe current map from robot status
        mapViewModel.getCurrentMapLiveData().observe(getViewLifecycleOwner(), mapName -> {
            Log.d(TAG, "observeData: currentMapLiveData changed, mapName=" + mapName);
            if (mapName != null && !mapName.isEmpty()) {
                this.currentMap = mapName;
                updateCurrentMapDisplay();
            }
        });

        // Observe current building from robot status
        mapViewModel.getCurrentBuildingLiveData().observe(getViewLifecycleOwner(), building -> {
            Log.d(TAG, "observeData: currentBuildingLiveData changed, building=" + building);
            if (building != null && !building.isEmpty()) {
                this.currentBuilding = building;
                updateCurrentBuildingDisplay();

                if (currentMapPoints != null) {
                    // For work points section
                    if (workPointsContainer.getVisibility() == View.VISIBLE) {
                        if (!isUserSelectedWorkBuilding) {
                            Log.d(TAG, "observeData: Auto-selecting current building for work section");
                            updateWorkBuildingTabs();
                        } else {
                            Log.d(TAG, "observeData: User has selected work building, preserving selection");
                        }
                    }
                    // For fixed points section
                    else if (fixedLocationContainer.getVisibility() == View.VISIBLE) {
                        if (!isUserSelectedFixedBuilding) {
                            Log.d(TAG, "observeData: Auto-selecting current building for fixed section");
                            updateFixedBuildingTabs();
                        } else {
                            Log.d(TAG, "observeData: User has selected fixed building, preserving selection");
                        }
                    }
                }
            }
        });

        // Observe current floor from robot status
        mapViewModel.getCurrentFloorLiveData().observe(getViewLifecycleOwner(), floor -> {
            Log.d(TAG, "observeData: currentFloorLiveData changed, floor=" + floor);
            if (floor != null) {
                this.currentFloor = floor;
                updateCurrentFloorDisplay();

                if (currentMapPoints != null) {
                    // For work points section
                    if (workPointsContainer.getVisibility() == View.VISIBLE) {
                        if (!isUserSelectedWorkFloor) {
                            Log.d(TAG, "observeData: Auto-selecting current floor for work section");
                            updateWorkFloorTabs();
                        } else {
                            Log.d(TAG, "observeData: User has selected work floor, preserving selection");
                        }
                    }
                    // For fixed points section
                    else if (fixedLocationContainer.getVisibility() == View.VISIBLE) {
                        if (!isUserSelectedFixedFloor) {
                            Log.d(TAG, "observeData: Auto-selecting current floor for fixed section");
                            updateFixedFloorTabs();
                        } else {
                            Log.d(TAG, "observeData: User has selected fixed floor, preserving selection");
                        }
                    }
                }
            }
        });

        // Observe currentMapPoints - main data source
        mapViewModel.getCurrentMapPoints().observe(getViewLifecycleOwner(), mapPoints -> {
            Log.d(TAG, "observeData: currentMapPoints updated");
            this.currentMapPoints = mapPoints;

            // Only update the currently visible section
            if (workPointsContainer.getVisibility() == View.VISIBLE) {
                updateWorkBuildingTabs();
            } else if (fixedLocationContainer.getVisibility() == View.VISIBLE) {
                updateFixedBuildingTabs();
            }
        });
    }

    private void updateCurrentMapDisplay() {
        if (tvCurrentMap != null && currentMap != null && !currentMap.isEmpty()) {
            tvCurrentMap.setText(currentMap);
        }
    }

    private void updateCurrentBuildingDisplay() {
        if (tvCurrentBuilding != null && currentBuilding != null && !currentBuilding.isEmpty()) {
            String buildingText = getString(R.string.building_prefix_format, currentBuilding);
            tvCurrentBuilding.setText(buildingText);
        }
    }

    private void updateCurrentFloorDisplay() {
        if (tvCurrentFloor != null) {
            String floorText = getFloorDisplayText(currentFloor);
            tvCurrentFloor.setText(floorText);
        }
    }

    private String getFloorDisplayText(int floor) {
        if (floor >= 0) {
            return String.format(getString(R.string.floor_prefix_format), floor);
        } else {
            return String.format(getString(R.string.basement_prefix), -floor);
        }
    }

    private void refreshAllDisplayData() {
        if (currentMapPoints == null) {
            showEmptyStates();
            return;
        }

        Log.d(TAG, "refreshAllDisplayData: currentMapPoints has " + currentMapPoints.getAllBuildings().size() + " buildings");

        // Update work points section
        updateWorkBuildingTabs();

        // Update fixed points section
        updateFixedBuildingTabs();
    }

    // ==================== Work Points Methods ====================

    private void updateWorkBuildingTabs() {
        if (currentMapPoints == null) {
            return;
        }

        // Get all available buildings
        Map<String, Building> allBuildings = currentMapPoints.getAllBuildings();
        List<String> buildings = new ArrayList<>(allBuildings.keySet());
        Log.d(TAG, "updateWorkBuildingTabs: Available buildings from mapPoints: " + buildings);

        if (!buildings.contains(currentBuilding) && currentBuilding != null && !currentBuilding.isEmpty()) {
            Log.d(TAG, "updateWorkBuildingTabs: Adding current building: " + currentBuilding);
            buildings.add(currentBuilding);
        }
        if (buildings.isEmpty()) {
            Log.d(TAG, "updateWorkBuildingTabs: No buildings found, adding default");
            buildings.add("default");
        }
        Collections.sort(buildings);

        String buildingToSelect = isUserSelectedWorkBuilding && buildings.contains(selectedBuilding)
                ? selectedBuilding
                : (buildings.contains(currentBuilding) ? currentBuilding : buildings.get(0));
        if (buildings.equals(availableBuildings)
                && buildingToSelect.equals(selectedBuilding)
                && tabBuildingTabs.getTabCount() == buildings.size()) {
            updateWorkFloorTabs();
            return;
        }

        this.availableBuildings = buildings;
        Log.d(TAG, "updateWorkBuildingTabs: Final building list: " + availableBuildings);
        Log.d(TAG, "updateWorkBuildingTabs: Current building from robot: " + currentBuilding);
        Log.d(TAG, "updateWorkBuildingTabs: User selected building flag: " + isUserSelectedWorkBuilding + ", value: " + selectedBuilding);

        // Show building tabs if more than one building
        if (buildings.size() > 1) {
            tabBuildingTabs.setVisibility(View.VISIBLE);
            setupBuildingTabs();
        } else {
            tabBuildingTabs.setVisibility(View.GONE);
            if (!isUserSelectedWorkBuilding) {
                selectedBuilding = buildings.get(0);
                Log.d(TAG, "updateWorkBuildingTabs: Single building, auto-selected: " + selectedBuilding);
            }

            // Only update floor tabs if building changed or user hasn't selected a floor yet
            if (!isUserSelectedWorkFloor) {
                updateWorkFloorTabs();
            } else {
                // User has selected a floor, just refresh the display without re-selecting
                updateWorkPointsDisplay();
            }
        }
    }

    private void setupBuildingTabs() {
        if (isUpdatingWorkBuildingTabs) {
            Log.d(TAG, "setupBuildingTabs: Already updating, skipping");
            return;
        }
        isUpdatingWorkBuildingTabs = true;
        Log.d(TAG, "setupBuildingTabs: Setting up building tabs with buildings: " + availableBuildings);

        tabBuildingTabs.clearOnTabSelectedListeners();
        tabBuildingTabs.removeAllTabs();
        for (String building : availableBuildings) {
            String buildingText = getString(R.string.building_prefix_format, building);
            TabLayout.Tab tab = tabBuildingTabs.newTab().setText(buildingText);
            tabBuildingTabs.addTab(tab);
            Log.d(TAG, "setupBuildingTabs: Added tab for building: " + building);
        }

        // Determine which building to select BEFORE setting the listener
        String buildingToSelect;
        if (isUserSelectedWorkBuilding && availableBuildings.contains(selectedBuilding)) {
            buildingToSelect = selectedBuilding;
            Log.d(TAG, "setupBuildingTabs: Using user-selected building: " + buildingToSelect);
        } else {
            buildingToSelect = currentBuilding;
            Log.d(TAG, "setupBuildingTabs: Using current building from robot: " + buildingToSelect);
            // Reset user selection flag when auto-selecting
            isUserSelectedWorkBuilding = false;
        }

        int tabPosition = availableBuildings.indexOf(buildingToSelect);
        if (tabPosition == -1 && !availableBuildings.isEmpty()) {
            tabPosition = 0;
            selectedBuilding = availableBuildings.get(0);
            Log.d(TAG, "setupBuildingTabs: Building not found, defaulting to: " + selectedBuilding);
        } else {
            selectedBuilding = availableBuildings.get(tabPosition);
            Log.d(TAG, "setupBuildingTabs: Selected building: " + selectedBuilding + " at position: " + tabPosition);
        }

        // Clear and set listener AFTER determining selection
        tabBuildingTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() < availableBuildings.size()) {
                    String newBuilding = availableBuildings.get(tab.getPosition());
                    Log.d(TAG, "Work building tab selected: " + newBuilding + ", previous: " + selectedBuilding);
                    if (!selectedBuilding.equals(newBuilding)) {
                        selectedBuilding = newBuilding;
                        isUserSelectedWorkBuilding = true;
                        Log.d(TAG, "User selected work building: " + selectedBuilding);
                        updateWorkFloorTabs();
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                Log.d(TAG, "Work building tab unselected");
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                Log.d(TAG, "Work building tab reselected");
                updateWorkFloorTabs();
            }
        });

        // Now select the tab
        TabLayout.Tab tab = tabBuildingTabs.getTabAt(tabPosition);
        if (tab != null && !tab.isSelected()) {
            tab.select();
            Log.d(TAG, "setupBuildingTabs: Programmatically selected tab at position: " + tabPosition);
        }

        isUpdatingWorkBuildingTabs = false;

        // Only update floor tabs if user hasn't selected a floor yet
        if (!isUserSelectedWorkFloor) {
            updateWorkFloorTabs();
        } else {
            // User has selected a floor, just refresh the display without re-selecting
            updateWorkPointsDisplay();
        }
    }

    private void updateWorkFloorTabs() {
        if (currentMapPoints == null || selectedBuilding == null) {
            Log.d(TAG, "updateWorkFloorTabs: currentMapPoints or selectedBuilding is null");
            return;
        }
        if (isUpdatingWorkFloorTabs) {
            Log.d(TAG, "updateWorkFloorTabs: Already updating, skipping");
            return;
        }
        isUpdatingWorkFloorTabs = true;

        Building building = currentMapPoints.getBuilding(selectedBuilding);
        if (building == null) {
            Log.w(TAG, "updateWorkFloorTabs: Building not found: " + selectedBuilding);
            isUpdatingWorkFloorTabs = false;
            updateWorkPointsDisplay();
            return;
        }

        // Get all available floors for this building
        Map<Integer, FloorPoints> allFloorPoints = building.getAllFloorPoints();
        List<Integer> floors = new ArrayList<>(allFloorPoints.keySet());
        Log.d(TAG, "updateWorkFloorTabs: Available floors for building " + selectedBuilding + ": " + floors);

        // CRITICAL FIX: Only add currentFloor if this building actually has that floor
        if (selectedBuilding.equals(currentBuilding) && !floors.contains(currentFloor)) {
            Log.d(TAG, "updateWorkFloorTabs: Adding current floor to current building: " + currentFloor);
            floors.add(currentFloor);
        } else if (!selectedBuilding.equals(currentBuilding)) {
            Log.d(TAG, "updateWorkFloorTabs: Not adding current floor " + currentFloor + " to building " + selectedBuilding);
        }

        if (floors.isEmpty()) {
            Log.d(TAG, "updateWorkFloorTabs: No floors found for building " + selectedBuilding);
            floors.add(0);
        }
        Collections.sort(floors);

        int floorToSelect;
        if (isUserSelectedWorkFloor && floors.contains(selectedFloorTab)) {
            floorToSelect = selectedFloorTab;
        } else if (selectedBuilding.equals(currentBuilding) && floors.contains(currentFloor)) {
            floorToSelect = currentFloor;
        } else {
            floorToSelect = floors.get(0);
        }
        if (floors.equals(availableFloors)
                && selectedFloorTab == floorToSelect
                && tabFloorTabs.getTabCount() == floors.size()) {
            isUpdatingWorkFloorTabs = false;
            updateWorkPointsDisplay();
            return;
        }

        this.availableFloors = floors;
        Log.d(TAG, "updateWorkFloorTabs: Final floor list for building " + selectedBuilding + ": " + availableFloors);

        // Setup floor tabs
        tabFloorTabs.clearOnTabSelectedListeners();
        tabFloorTabs.removeAllTabs();
        for (int floor : availableFloors) {
            String floorText = getFloorDisplayText(floor);
            TabLayout.Tab tab = tabFloorTabs.newTab().setText(floorText);
            tabFloorTabs.addTab(tab);
            Log.d(TAG, "updateWorkFloorTabs: Added tab for floor: " + floor);
        }

        tabFloorTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() < availableFloors.size()) {
                    int newFloor = availableFloors.get(tab.getPosition());
                    Log.d(TAG, "Work floor tab selected: " + newFloor + ", previous: " + selectedFloorTab);
                    if (selectedFloorTab != newFloor) {
                        selectedFloorTab = newFloor;
                        isUserSelectedWorkFloor = true;
                        Log.d(TAG, "User selected work floor: " + selectedFloorTab);
                        updateWorkPointsDisplay();
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                Log.d(TAG, "Work floor tab unselected");
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                Log.d(TAG, "Work floor tab reselected");
                updateWorkPointsDisplay();
            }
        });

        // Select the appropriate floor tab
        if (isUserSelectedWorkFloor && availableFloors.contains(selectedFloorTab)) {
            floorToSelect = selectedFloorTab;
            Log.d(TAG, "updateWorkFloorTabs: Using user-selected floor: " + floorToSelect);
        } else {
            if (selectedBuilding.equals(currentBuilding) && availableFloors.contains(currentFloor)) {
                floorToSelect = currentFloor;
                Log.d(TAG, "updateWorkFloorTabs: Using current floor from robot for current building: " + floorToSelect);
            } else {
                floorToSelect = availableFloors.get(0);
                Log.d(TAG, "updateWorkFloorTabs: Using first available floor for building " + selectedBuilding + ": " + floorToSelect);
            }
        }

        int tabPosition = availableFloors.indexOf(floorToSelect);
        if (tabPosition == -1 && !availableFloors.isEmpty()) {
            tabPosition = 0;
            selectedFloorTab = availableFloors.get(0);
            Log.d(TAG, "updateWorkFloorTabs: Floor not found, defaulting to: " + selectedFloorTab);
        } else {
            selectedFloorTab = availableFloors.get(tabPosition);
            Log.d(TAG, "updateWorkFloorTabs: Selected floor: " + selectedFloorTab + " at position: " + tabPosition);
        }

        if (tabPosition >= 0 && tabPosition < tabFloorTabs.getTabCount()) {
            TabLayout.Tab tab = tabFloorTabs.getTabAt(tabPosition);
            if (tab != null && !tab.isSelected()) {
                tab.select();
                Log.d(TAG, "updateWorkFloorTabs: Programmatically selected floor tab at position: " + tabPosition);
            }
        }

        isUpdatingWorkFloorTabs = false;
        updateWorkPointsDisplay();
    }

    private void updateWorkPointsDisplay() {
        if (currentMap == null || currentMap.isEmpty() || currentMapPoints == null) {
            workPointAdapter.submitList(new ArrayList<>());
            updateWorkNoDataVisibility(true);
            return;
        }

        Building building = currentMapPoints.getBuilding(selectedBuilding);
        if (building == null) {
            workPointAdapter.submitList(new ArrayList<>());
            updateWorkNoDataVisibility(true);
            return;
        }

        FloorPoints floorPoints = building.getFloorPoints(selectedFloorTab);
        if (floorPoints == null) {
            workPointAdapter.submitList(new ArrayList<>());
            updateWorkNoDataVisibility(true);
            return;
        }

        // CRITICAL FIX: Create NEW Position objects when building the list
        // This ensures the adapter sees them as different objects
        List<Position> workPoints = new ArrayList<>();
        List<Position> allPoints = floorPoints.getAllPoints();

        for (Position pos : allPoints) {
            if (pos.getType() == 1) {
                // Create a new Position object with the same data
                Position newPos = new Position();
                newPos.setId(pos.getId());
                newPos.setName(pos.getName());
                newPos.setPosX(pos.getPosX());
                newPos.setPosY(pos.getPosY());
                newPos.setYaw(pos.getYaw());
                newPos.setFloor(pos.getFloor());
                newPos.setMapName(pos.getMapName());
                newPos.setType(pos.getType());
                newPos.setMessage(pos.getMessage());
                newPos.setDefaultPoint(pos.isDefaultPoint());

                workPoints.add(newPos);
            }
        }

        Log.d(TAG, "updateWorkPointsDisplay: Displaying " + workPoints.size() + " work points");

        // Force submit the new list
        workPointAdapter.submitList(workPoints);
        updateWorkNoDataVisibility(workPoints.isEmpty());
    }

    // ==================== Fixed Points Methods ====================

    private void updateFixedBuildingTabs() {
        if (currentMapPoints == null) return;

        List<String> buildings = new ArrayList<>(currentMapPoints.getAllBuildings().keySet());
        Log.d(TAG, "updateFixedBuildingTabs: Available buildings from mapPoints: " + buildings);

        if (!buildings.contains(currentBuilding) && currentBuilding != null && !currentBuilding.isEmpty()) {
            Log.d(TAG, "updateFixedBuildingTabs: Adding current building: " + currentBuilding);
            buildings.add(currentBuilding);
        }
        if (buildings.isEmpty()) {
            Log.d(TAG, "updateFixedBuildingTabs: No buildings found, adding default");
            buildings.add("default");
        }
        Collections.sort(buildings);

        String buildingToSelect = isUserSelectedFixedBuilding && buildings.contains(selectedFixedBuilding)
                ? selectedFixedBuilding
                : (buildings.contains(currentBuilding) ? currentBuilding : buildings.get(0));
        if (buildings.equals(fixedAvailableBuildings)
                && buildingToSelect.equals(selectedFixedBuilding)
                && tabFixedBuildingTabs.getTabCount() == buildings.size()) {
            updateFixedFloorTabs();
            return;
        }

        this.fixedAvailableBuildings = buildings;
        Log.d(TAG, "updateFixedBuildingTabs: Final building list: " + fixedAvailableBuildings);
        Log.d(TAG, "updateFixedBuildingTabs: Current building from robot: " + currentBuilding);
        Log.d(TAG, "updateFixedBuildingTabs: User selected fixed building flag: " + isUserSelectedFixedBuilding + ", value: " + selectedFixedBuilding);

        if (buildings.size() > 1) {
            tabFixedBuildingTabs.setVisibility(View.VISIBLE);
            setupFixedBuildingTabs();
        } else {
            tabFixedBuildingTabs.setVisibility(View.GONE);
            if (!isUserSelectedFixedBuilding) {
                selectedFixedBuilding = buildings.get(0);
                Log.d(TAG, "updateFixedBuildingTabs: Single building, auto-selected: " + selectedFixedBuilding);
            }

            if (!isUserSelectedFixedFloor) {
                updateFixedFloorTabs();
            } else {
                updateFixedPointsDisplay();
            }
        }
    }

    private void setupFixedBuildingTabs() {
        if (isUpdatingFixedBuildingTabs) {
            Log.d(TAG, "setupFixedBuildingTabs: Already updating, skipping");
            return;
        }
        isUpdatingFixedBuildingTabs = true;
        Log.d(TAG, "setupFixedBuildingTabs: Setting up fixed building tabs with buildings: " + fixedAvailableBuildings);

        tabFixedBuildingTabs.clearOnTabSelectedListeners();
        tabFixedBuildingTabs.removeAllTabs();
        for (String building : fixedAvailableBuildings) {
            String buildingText = getString(R.string.building_prefix_format, building);
            TabLayout.Tab tab = tabFixedBuildingTabs.newTab().setText(buildingText);
            tabFixedBuildingTabs.addTab(tab);
            Log.d(TAG, "setupFixedBuildingTabs: Added tab for building: " + building);
        }

        String buildingToSelect;
        if (isUserSelectedFixedBuilding && fixedAvailableBuildings.contains(selectedFixedBuilding)) {
            buildingToSelect = selectedFixedBuilding;
            Log.d(TAG, "setupFixedBuildingTabs: Using user-selected building: " + buildingToSelect);
        } else {
            buildingToSelect = currentBuilding;
            Log.d(TAG, "setupFixedBuildingTabs: Using current building from robot: " + buildingToSelect);
            isUserSelectedFixedBuilding = false;
        }

        int tabPosition = fixedAvailableBuildings.indexOf(buildingToSelect);
        if (tabPosition == -1 && !fixedAvailableBuildings.isEmpty()) {
            tabPosition = 0;
            selectedFixedBuilding = fixedAvailableBuildings.get(0);
            Log.d(TAG, "setupFixedBuildingTabs: Building not found, defaulting to: " + selectedFixedBuilding);
        } else {
            selectedFixedBuilding = fixedAvailableBuildings.get(tabPosition);
            Log.d(TAG, "setupFixedBuildingTabs: Selected fixed building: " + selectedFixedBuilding + " at position: " + tabPosition);
        }

        tabFixedBuildingTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() < fixedAvailableBuildings.size()) {
                    String newBuilding = fixedAvailableBuildings.get(tab.getPosition());
                    Log.d(TAG, "Fixed building tab selected: " + newBuilding + ", previous: " + selectedFixedBuilding);
                    if (!selectedFixedBuilding.equals(newBuilding)) {
                        selectedFixedBuilding = newBuilding;
                        isUserSelectedFixedBuilding = true;
                        Log.d(TAG, "User selected fixed building: " + selectedFixedBuilding);
                        updateFixedFloorTabs();
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                Log.d(TAG, "Fixed building tab unselected");
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                Log.d(TAG, "Fixed building tab reselected");
                updateFixedFloorTabs();
            }
        });

        TabLayout.Tab tab = tabFixedBuildingTabs.getTabAt(tabPosition);
        if (tab != null && !tab.isSelected()) {
            tab.select();
            Log.d(TAG, "setupFixedBuildingTabs: Programmatically selected tab at position: " + tabPosition);
        }

        isUpdatingFixedBuildingTabs = false;

        if (!isUserSelectedFixedFloor) {
            updateFixedFloorTabs();
        } else {
            updateFixedPointsDisplay();
        }
    }

    private void updateFixedFloorTabs() {
        if (currentMapPoints == null || selectedFixedBuilding == null) {
            Log.d(TAG, "updateFixedFloorTabs: currentMapPoints or selectedFixedBuilding is null");
            return;
        }
        if (isUpdatingFixedFloorTabs) {
            Log.d(TAG, "updateFixedFloorTabs: Already updating, skipping");
            return;
        }
        isUpdatingFixedFloorTabs = true;

        Log.d(TAG, "updateFixedFloorTabs: Getting floors for fixed building: " + selectedFixedBuilding);

        Building building = currentMapPoints.getBuilding(selectedFixedBuilding);
        if (building == null) {
            Log.w(TAG, "updateFixedFloorTabs: Building not found: " + selectedFixedBuilding);
            isUpdatingFixedFloorTabs = false;
            updateFixedPointsDisplay();
            return;
        }

        List<Integer> floors = new ArrayList<>(building.getAllFloorPoints().keySet());
        Log.d(TAG, "updateFixedFloorTabs: Available floors from building: " + floors);

        if (selectedFixedBuilding.equals(currentBuilding) && !floors.contains(currentFloor)) {
            Log.d(TAG, "updateFixedFloorTabs: Adding current floor to current building: " + currentFloor);
            floors.add(currentFloor);
        } else if (!selectedFixedBuilding.equals(currentBuilding)) {
            Log.d(TAG, "updateFixedFloorTabs: Not adding current floor " + currentFloor + " to building " + selectedFixedBuilding);
        }

        if (floors.isEmpty()) {
            Log.d(TAG, "updateFixedFloorTabs: No floors found for building " + selectedFixedBuilding);
            floors.add(0);
        }
        Collections.sort(floors);

        int floorToSelect;
        if (isUserSelectedFixedFloor && floors.contains(selectedFixedFloorTab)) {
            floorToSelect = selectedFixedFloorTab;
        } else if (selectedFixedBuilding.equals(currentBuilding) && floors.contains(currentFloor)) {
            floorToSelect = currentFloor;
        } else {
            floorToSelect = floors.get(0);
        }
        if (floors.equals(fixedAvailableFloors)
                && selectedFixedFloorTab == floorToSelect
                && tabFixedFloorTabs.getTabCount() == floors.size()) {
            isUpdatingFixedFloorTabs = false;
            updateFixedPointsDisplay();
            return;
        }

        this.fixedAvailableFloors = floors;
        Log.d(TAG, "updateFixedFloorTabs: Final floor list for building " + selectedFixedBuilding + ": " + fixedAvailableFloors);

        tabFixedFloorTabs.clearOnTabSelectedListeners();
        tabFixedFloorTabs.removeAllTabs();
        for (int floor : fixedAvailableFloors) {
            String floorText = getFloorDisplayText(floor);
            TabLayout.Tab tab = tabFixedFloorTabs.newTab().setText(floorText);
            tabFixedFloorTabs.addTab(tab);
            Log.d(TAG, "updateFixedFloorTabs: Added tab for floor: " + floor);
        }

        tabFixedFloorTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() < fixedAvailableFloors.size()) {
                    int newFloor = fixedAvailableFloors.get(tab.getPosition());
                    Log.d(TAG, "Fixed floor tab selected: " + newFloor + ", previous: " + selectedFixedFloorTab);
                    if (selectedFixedFloorTab != newFloor) {
                        selectedFixedFloorTab = newFloor;
                        isUserSelectedFixedFloor = true;
                        Log.d(TAG, "User selected fixed floor: " + selectedFixedFloorTab);
                        updateFixedPointsDisplay();
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                Log.d(TAG, "Fixed floor tab unselected");
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                Log.d(TAG, "Fixed floor tab reselected");
                updateFixedPointsDisplay();
            }
        });

        if (isUserSelectedFixedFloor && fixedAvailableFloors.contains(selectedFixedFloorTab)) {
            floorToSelect = selectedFixedFloorTab;
            Log.d(TAG, "updateFixedFloorTabs: Using user-selected floor: " + floorToSelect);
        } else {
            if (selectedFixedBuilding.equals(currentBuilding) && fixedAvailableFloors.contains(currentFloor)) {
                floorToSelect = currentFloor;
                Log.d(TAG, "updateFixedFloorTabs: Using current floor from robot for current building: " + floorToSelect);
            } else {
                floorToSelect = fixedAvailableFloors.get(0);
                Log.d(TAG, "updateFixedFloorTabs: Using first available floor for building " + selectedFixedBuilding + ": " + floorToSelect);
            }
        }

        int tabPosition = fixedAvailableFloors.indexOf(floorToSelect);
        if (tabPosition == -1 && !fixedAvailableFloors.isEmpty()) {
            tabPosition = 0;
            selectedFixedFloorTab = fixedAvailableFloors.get(0);
            Log.d(TAG, "updateFixedFloorTabs: Floor not found, defaulting to: " + selectedFixedFloorTab);
        } else {
            selectedFixedFloorTab = fixedAvailableFloors.get(tabPosition);
            Log.d(TAG, "updateFixedFloorTabs: Selected fixed floor: " + selectedFixedFloorTab + " at position: " + tabPosition);
        }

        if (tabPosition >= 0 && tabPosition < tabFixedFloorTabs.getTabCount()) {
            TabLayout.Tab tab = tabFixedFloorTabs.getTabAt(tabPosition);
            if (tab != null && !tab.isSelected()) {
                tab.select();
                Log.d(TAG, "updateFixedFloorTabs: Programmatically selected fixed floor tab at position: " + tabPosition);
            }
        }

        isUpdatingFixedFloorTabs = false;
        updateFixedPointsDisplay();
    }

    private void updateFixedPointsDisplay() {
        Log.d(TAG, "updateFixedPointsDisplay: START - Building=" + selectedFixedBuilding +
                ", Floor=" + selectedFixedFloorTab +
                ", currentMap=" + currentMap +
                ", currentMapPoints exists=" + (currentMapPoints != null));

        if (currentMap == null || currentMap.isEmpty() || currentMapPoints == null) {
            Log.w(TAG, "updateFixedPointsDisplay: currentMap or currentMapPoints is null - " +
                    "currentMap=" + currentMap +
                    ", currentMapPoints=" + (currentMapPoints != null));
            List<Position> defaultFixedPoints = new ArrayList<>();
            addDefaultFixedPoints(defaultFixedPoints);
            fixedPointAdapter.submitList(defaultFixedPoints);
            updateFixedNoDataVisibility(defaultFixedPoints.isEmpty());
            Log.d(TAG, "updateFixedPointsDisplay: Using default fixed points (count=" + defaultFixedPoints.size() + ")");
            return;
        }

        Log.d(TAG, "updateFixedPointsDisplay: Getting building: " + selectedFixedBuilding);
        Building building = currentMapPoints.getBuilding(selectedFixedBuilding);
        if (building == null) {
            Log.w(TAG, "updateFixedPointsDisplay: Building not found: " + selectedFixedBuilding +
                    " - Available buildings: " + currentMapPoints.getAllBuildings().keySet());
            List<Position> defaultFixedPoints = new ArrayList<>();
            addDefaultFixedPoints(defaultFixedPoints);
            fixedPointAdapter.submitList(defaultFixedPoints);
            updateFixedNoDataVisibility(defaultFixedPoints.isEmpty());
            return;
        }

        Log.d(TAG, "updateFixedPointsDisplay: Getting floor points for floor: " + selectedFixedFloorTab);
        FloorPoints floorPoints = building.getFloorPoints(selectedFixedFloorTab);
        List<Position> fixedPoints = new ArrayList<>();

        if (floorPoints == null) {
            Log.w(TAG, "updateFixedPointsDisplay: FloorPoints null for floor " + selectedFixedFloorTab + " - Creating default points");
            fixedPoints.add(createDefaultPosition(selectedFixedFloorTab, 10, getString(R.string.charge_point)));
            fixedPoints.add(createDefaultPosition(selectedFixedFloorTab, 11, getString(R.string.precharge_point)));
            fixedPoints.add(createDefaultPosition(selectedFixedFloorTab, 12, getString(R.string.park_point)));
            fixedPoints.add(createDefaultPosition(selectedFixedFloorTab, 13, getString(R.string.relocalize_point)));
        } else {
            Log.d(TAG, "updateFixedPointsDisplay: FloorPoints found, collecting special points");

            if (floorPoints.getChargePoint() != null) {
                Log.d(TAG, "updateFixedPointsDisplay: Charge point - X=" + floorPoints.getChargePoint().getPosX() +
                        ", Y=" + floorPoints.getChargePoint().getPosY() +
                        ", Yaw=" + floorPoints.getChargePoint().getYaw() +
                        ", isDefault=" + isDefaultPosition(floorPoints.getChargePoint()));
            }
            if (floorPoints.getPreChargePoint() != null) {
                Log.d(TAG, "updateFixedPointsDisplay: Pre-charge point - X=" + floorPoints.getPreChargePoint().getPosX() +
                        ", Y=" + floorPoints.getPreChargePoint().getPosY() +
                        ", Yaw=" + floorPoints.getPreChargePoint().getYaw());
            }
            if (floorPoints.getParkPoint() != null) {
                Log.d(TAG, "updateFixedPointsDisplay: Park point - X=" + floorPoints.getParkPoint().getPosX() +
                        ", Y=" + floorPoints.getParkPoint().getPosY() +
                        ", Yaw=" + floorPoints.getParkPoint().getYaw());
            }
            if (floorPoints.getRelocalizePoint() != null) {
                Log.d(TAG, "updateFixedPointsDisplay: Relocalize point - X=" + floorPoints.getRelocalizePoint().getPosX() +
                        ", Y=" + floorPoints.getRelocalizePoint().getPosY() +
                        ", Yaw=" + floorPoints.getRelocalizePoint().getYaw());
            }

            // CRITICAL FIX: Create NEW Position objects for fixed points too
            addFixedPointIfPresent(fixedPoints, floorPoints.getChargePoint(), 10, getString(R.string.charge_point), selectedFixedFloorTab);
            addFixedPointIfPresent(fixedPoints, floorPoints.getPreChargePoint(), 11, getString(R.string.precharge_point), selectedFixedFloorTab);
            addFixedPointIfPresent(fixedPoints, floorPoints.getParkPoint(), 12, getString(R.string.park_point), selectedFixedFloorTab);
            addFixedPointIfPresent(fixedPoints, floorPoints.getRelocalizePoint(), 13, getString(R.string.relocalize_point), selectedFixedFloorTab);
        }

        Log.d(TAG, "updateFixedPointsDisplay: Final fixed points count=" + fixedPoints.size() + ", submitting to adapter");

        for (int i = 0; i < fixedPoints.size(); i++) {
            Position p = fixedPoints.get(i);
            Log.d(TAG, "updateFixedPointsDisplay: Point[" + i + "] - Type=" + p.getType() +
                    ", Name=" + p.getName() +
                    ", X=" + p.getPosX() +
                    ", Y=" + p.getPosY() +
                    ", Yaw=" + p.getYaw() +
                    ", isDefault=" + p.isDefaultPoint());
        }

        fixedPointAdapter.submitList(fixedPoints);
        updateFixedNoDataVisibility(fixedPoints.isEmpty());

        Log.d(TAG, "updateFixedPointsDisplay: COMPLETED - Points displayed=" + fixedPoints.size());
    }

    private void addFixedPointIfPresent(List<Position> list, Position point, int type, String name, int floor) {
        if (point != null && !isDefaultPosition(point)) {
            // Create a NEW Position object instead of using the original
            Position newPos = new Position();
            newPos.setId(point.getId());
            newPos.setName(point.getName());
            newPos.setPosX(point.getPosX());
            newPos.setPosY(point.getPosY());
            newPos.setYaw(point.getYaw());
            newPos.setFloor(point.getFloor());
            newPos.setMapName(point.getMapName());
            newPos.setType(type);
            newPos.setMessage(point.getMessage());
            newPos.setDefaultPoint(point.isDefaultPoint());

            list.add(newPos);
            Log.d(TAG, "addFixedPointIfPresent: Added existing point: " + name + " at position (" + point.getPosX() + ", " + point.getPosY() + ")");
        } else {
            Position defaultPos = createDefaultPosition(floor, type, name);
            list.add(defaultPos);
            Log.d(TAG, "addFixedPointIfPresent: Added default point: " + name);
        }
    }

    private boolean isDefaultPosition(Position position) {
        return position.getPosX() == 0.0 && position.getPosY() == 0.0 && position.getYaw() == 0.0;
    }

    private Position createDefaultPosition(int floor, int type, String name) {
        Position defaultPos = new Position();
        String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, selectedFixedBuilding, floor);
        defaultPos.setId(-1);
        defaultPos.setName(name);
        defaultPos.setPosX(0.0);
        defaultPos.setPosY(0.0);
        defaultPos.setYaw(0.0);
        defaultPos.setFloor(String.valueOf(floor));
        defaultPos.setMapName(fullMapName);
        defaultPos.setType(type);
        defaultPos.setMessage(getString(R.string.navigating_to_task_point, name));
        defaultPos.setDefaultPoint(true);
        return defaultPos;
    }

    private void addDefaultFixedPoints(List<Position> fixedPoints) {
        fixedPoints.add(createDefaultPosition(0, 10, getString(R.string.charge_point)));
        fixedPoints.add(createDefaultPosition(0, 11, getString(R.string.precharge_point)));
        fixedPoints.add(createDefaultPosition(0, 12, getString(R.string.park_point)));
        fixedPoints.add(createDefaultPosition(0, 13, getString(R.string.relocalize_point)));
    }

    private interface CurrentPositionCallback {
        void onPosition(Position currentPosition);
    }

    private void fetchLatestCurrentPosition(String buildingId, int floor, CurrentPositionCallback callback) {
        if (commandClient == null || !commandClient.isConnected()) {
            Log.w(TAG, "fetchLatestCurrentPosition: Command client not connected");
            showToast(R.string.cannot_get_current_position);
            return;
        }

        Log.d(TAG, "fetchLatestCurrentPosition: Querying latest robot status before saving point");
        commandClient.queryStatus(new CommandWebSocketClient.StatusCallback() {
            @Override
            public void onSuccess(AgvStatusResponse status) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) {
                        return;
                    }

                    Position latestPosition = createCurrentPositionFromStatus(status, buildingId, floor);
                    if (latestPosition == null) {
                        Log.w(TAG, "fetchLatestCurrentPosition: Latest status has no valid position");
                        showToast(R.string.cannot_get_current_position);
                        return;
                    }

                    callback.onPosition(latestPosition);
                });
            }

            @Override
            public void onError(String error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Log.w(TAG, "fetchLatestCurrentPosition: Failed to query latest status: " + error);
                    showToast(R.string.cannot_get_current_position);
                });
            }
        });
    }

    private Position createCurrentPositionFromStatus(AgvStatusResponse status, String buildingId, int floor) {
        if (status == null || status.data == null || status.data.pos == null) {
            return null;
        }

        Position currentPosition = new Position();
        currentPosition.setId(1);
        currentPosition.setName("current");
        currentPosition.setPosX(status.data.pos.x);
        currentPosition.setPosY(status.data.pos.y);
        currentPosition.setYaw(status.data.pos.theta);
        currentPosition.setFloor(String.valueOf(floor));
        currentPosition.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, buildingId, floor));
        return currentPosition;
    }

    // ==================== Dialog Methods ====================

    private void showAddLocationDialog() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_add_location, null);

        TextView title = dialogView.findViewById(R.id.dialog_title);
        title.setText(getString(R.string.add_new_location));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);

        EditText input = dialogView.findViewById(R.id.input_location);
        input.setHint(R.string.input_location_name);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        Button positiveButton = dialogView.findViewById(R.id.positive_button);
        Button negativeButton = dialogView.findViewById(R.id.negative_button);

        positiveButton.setOnClickListener(v -> {
            if (currentMap == null || currentMap.isEmpty()) {
                mapViewModel.showNotCurrentFloorToast(requireContext());
                dialog.dismiss();
                return;
            }

            if (selectedFloorTab != currentFloor) {
                showToast(getString(R.string.cannot_add_point_wrong_floor,
                        getFloorDisplayText(selectedFloorTab),
                        getFloorDisplayText(currentFloor)));
                dialog.dismiss();
                return;
            }

            if (!selectedBuilding.equals(currentBuilding)) {
                showToast(getString(R.string.cannot_add_point_wrong_building,
                        getString(R.string.building_prefix_format, selectedBuilding),
                        getString(R.string.building_prefix_format, currentBuilding)));
                dialog.dismiss();
                return;
            }

            String positionName = input.getText().toString().trim();
            if (positionName.isEmpty()) {
                int newId = mapViewModel.generateNewId();
                positionName = getString(R.string.default_point_name, newId);
            }
            final String finalPositionName = positionName;

            Building building = currentMapPoints != null ? currentMapPoints.getBuilding(selectedBuilding) : null;
            if (building != null) {
                FloorPoints floorPoints = building.getFloorPoints(currentFloor);
                if (floorPoints != null) {
                    for (Position position : floorPoints.getWorkPoints()) {
                        if (finalPositionName.equals(position.getName())) {
                            showToast(getString(R.string.add_failed_duplicate_name, finalPositionName));
                            return;
                        }
                    }
                }
            }

            fetchLatestCurrentPosition(selectedBuilding, currentFloor, currentPosition -> {
                Position newPosition = new Position();
                newPosition.setId(mapViewModel.generateNewId());
                newPosition.setName(finalPositionName);
                newPosition.setPosX(currentPosition.getPosX());
                newPosition.setPosY(currentPosition.getPosY());
                newPosition.setYaw(currentPosition.getYaw());
                newPosition.setType(1);
                newPosition.setFloor(String.valueOf(currentFloor));
                newPosition.setMapName(MultiBuildingMapPoints.generateMapName(currentMap, selectedBuilding, currentFloor));
                newPosition.setTaskType(0);
                newPosition.setSpecialType(-1);

                mapViewModel.updateCurrentMapPoints(selectedBuilding, currentMap, currentFloor,
                        1, Collections.singletonList(newPosition));
                showToast(getString(R.string.add_success, finalPositionName));
                dialog.dismiss();
            });
        });

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.6);
            window.setAttributes(lp);
        }
    }

    private void showDownloadConfirmationDialog() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_confirm_fetch, null);

        TextView title = dialogView.findViewById(R.id.dialog_title);
        title.setText(getString(R.string.fetch_location_confirmation_title));
        title.setGravity(Gravity.CENTER);

        TextView message = dialogView.findViewById(R.id.dialog_message);
        message.setText(getString(R.string.fetch_location_confirmation_message));

        Button positiveButton = dialogView.findViewById(R.id.positive_button);
        Button negativeButton = dialogView.findViewById(R.id.negative_button);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        positiveButton.setOnClickListener(v -> {
            if (currentMap == null || currentMap.isEmpty()) {
                mapViewModel.showNotCurrentFloorToast(requireContext());
                dialog.dismiss();
                return;
            }

            positiveButton.setEnabled(false);

            mapViewModel.downloadCurrentMapPoints(currentMap, true, new CommandWebSocketClient.MarkerDataCallback() {
                @Override
                public void onSuccess(MarkerData markerData) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        dialog.dismiss();
                        resetUserSelectionFlags();
                        refreshAllDisplayData();
                    });
                }

                @Override
                public void onError(String error) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Log.e(TAG, "Failed to fetch markers: " + error);
                        positiveButton.setEnabled(true);
                    });
                }
            });

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (dialog.isShowing()) {
                    positiveButton.setEnabled(true);
                    dialog.dismiss();
                }
            }, 15000);
        });

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.5);
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }
    }

    private void showUploadConfirmationDialog() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_confirm_upload, null);

        TextView title = dialogView.findViewById(R.id.dialog_title);
        title.setText(getString(R.string.upload_location_confirmation_title));
        title.setGravity(Gravity.CENTER);

        TextView message = dialogView.findViewById(R.id.dialog_message);
        message.setText(getString(R.string.upload_location_confirmation_message));

        Button positiveButton = dialogView.findViewById(R.id.positive_button);
        Button negativeButton = dialogView.findViewById(R.id.negative_button);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        positiveButton.setOnClickListener(v -> {
            mapViewModel.uploadCurrentMapPoints(currentMap);
            dialog.dismiss();
            showToast(getString(R.string.position_updated));
        });

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.5);
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }
    }

    private void showReacquireSpecialPointDialog(Position position) {
        Log.d(TAG, "showReacquireSpecialPointDialog: START - Special point: " +
                getDisplayNameForPosition(position) + " (Type: " + position.getType() + ")");

        if (currentMap == null || currentMap.isEmpty()) {
            Log.w(TAG, "showReacquireSpecialPointDialog: currentMap is null or empty");
            mapViewModel.showNotCurrentFloorToast(requireContext());
            return;
        }

        if (selectedFixedFloorTab != currentFloor) {
            String warning = String.format("Cannot update special point - wrong floor. Selected: %s, Current: %s",
                    getFloorDisplayText(selectedFixedFloorTab), getFloorDisplayText(currentFloor));
            Log.w(TAG, "showReacquireSpecialPointDialog: " + warning);
            showToast(getString(R.string.cannot_update_point_wrong_floor,
                    getFloorDisplayText(selectedFixedFloorTab),
                    getFloorDisplayText(currentFloor)));
            return;
        }

        if (!selectedFixedBuilding.equals(currentBuilding)) {
            String warning = String.format("Cannot update special point - wrong building. Selected: %s, Current: %s",
                    selectedFixedBuilding, currentBuilding);
            Log.w(TAG, "showReacquireSpecialPointDialog: " + warning);
            showToast(getString(R.string.cannot_update_point_wrong_building,
                    getString(R.string.building_prefix_format, selectedFixedBuilding),
                    getString(R.string.building_prefix_format, currentBuilding)));
            return;
        }

        fetchLatestCurrentPosition(selectedFixedBuilding, currentFloor, currentPosition -> {
            Log.d(TAG, "showReacquireSpecialPointDialog: Current robot position - X=" +
                    currentPosition.getPosX() + ", Y=" + currentPosition.getPosY() +
                    ", Yaw=" + currentPosition.getYaw());

            String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, selectedFixedBuilding, currentFloor);

            Position updatedPosition = new Position();

            if (position.isDefaultPoint()) {
                Log.d(TAG, "showReacquireSpecialPointDialog: Creating new position ID for default point");
                updatedPosition.setId(mapViewModel.generateNewId());
                updatedPosition.setName(position.getName());
            } else {
                Log.d(TAG, "showReacquireSpecialPointDialog: Updating existing position ID: " + position.getId());
                updatedPosition.setId(position.getId());
                updatedPosition.setName(position.getName());
            }

            updatedPosition.setPosX(currentPosition.getPosX());
            updatedPosition.setPosY(currentPosition.getPosY());
            updatedPosition.setYaw(currentPosition.getYaw());
            updatedPosition.setFloor(String.valueOf(currentFloor));
            updatedPosition.setMapName(fullMapName);
            updatedPosition.setType(position.getType());

            List<Position> updateCollection = Collections.singletonList(updatedPosition);

            Log.d(TAG, "showReacquireSpecialPointDialog: Updating special point - Type: " +
                    position.getType() + ", Name: " + position.getName() +
                    ", New coordinates: (" + currentPosition.getPosX() + ", " +
                    currentPosition.getPosY() + ", " + currentPosition.getYaw() + ")");

            mapViewModel.updateCurrentMapPoints(selectedFixedBuilding, currentMap, currentFloor,
                    position.getType(), updateCollection);

            if (position.getType() == 10) {
                Log.d(TAG, "showReacquireSpecialPointDialog: Saving charge point to command client");
                sharedViewModel.getCommandClient().saveCharge();
            } else if (position.getType() == 12) {
                Log.d(TAG, "showReacquireSpecialPointDialog: Saving start point to command client");
                sharedViewModel.getCommandClient().saveTheStartPoint(currentMap,
                        updatedPosition.getPosX(), updatedPosition.getPosY(), updatedPosition.getYaw());
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "showReacquireSpecialPointDialog: Refreshing fixed points display");
                updateFixedPointsDisplay();
                showToast(getString(R.string.position_updated));
            }, 500);
        });
    }

    private void showDeleteConfirmationDialog(Position position) {
        Log.d(TAG, "showDeleteConfirmationDialog: Attempting to delete work point: " +
                position.getName() + " (ID: " + position.getId() +
                ", Floor: " + position.getFloor() + ")");

        if (selectedFloorTab != currentFloor) {
            String warning = String.format("Cannot delete point - wrong floor. Selected: %s, Current: %s",
                    getFloorDisplayText(selectedFloorTab), getFloorDisplayText(currentFloor));
            Log.w(TAG, "showDeleteConfirmationDialog: " + warning);
            showToast(getString(R.string.cannot_delete_point_wrong_floor,
                    getFloorDisplayText(selectedFloorTab),
                    getFloorDisplayText(currentFloor)));
            return;
        }

        if (!selectedBuilding.equals(currentBuilding)) {
            String warning = String.format("Cannot delete point - wrong building. Selected: %s, Current: %s",
                    selectedBuilding, currentBuilding);
            Log.w(TAG, "showDeleteConfirmationDialog: " + warning);
            showToast(getString(R.string.cannot_delete_point_wrong_building,
                    getString(R.string.building_prefix_format, selectedBuilding),
                    getString(R.string.building_prefix_format, currentBuilding)));
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_confirm_delete, null);

        TextView message = dialogView.findViewById(R.id.dialog_message);
        Button negativeButton = dialogView.findViewById(R.id.negative_button);
        Button positiveButton = dialogView.findViewById(R.id.positive_button);

        String displayName = getDisplayNameForPosition(position);
        message.setText(getString(R.string.delete_location_prompt, displayName));

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        positiveButton.setOnClickListener(v1 -> {
            Log.d(TAG, "showDeleteConfirmationDialog: User confirmed deletion of: " + position.getName());
            mapViewModel.removePosition(position.getName(), selectedBuilding, currentMap, currentFloor);
            showToast(getString(R.string.delete_success));

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "showDeleteConfirmationDialog: Refreshing work points after deletion");
                updateWorkPointsDisplay();
            }, 500);

            dialog.dismiss();
        });

        negativeButton.setOnClickListener(v1 -> {
            Log.d(TAG, "showDeleteConfirmationDialog: User cancelled deletion of: " + position.getName());
            dialog.dismiss();
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.6);
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }
    }

    // ==================== Helper Methods ====================
    private void showEditNameDialog(Position position) {
        Log.d(TAG, "showEditNameDialog: Editing work point name - Current: '" +
                position.getName() + "' (ID: " + position.getId() +
                ", Floor: " + position.getFloor() + ")");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_name, null);
        EditText etName = dialogView.findViewById(R.id.et_name);
        etName.setText(position.getName());
        etName.requestFocus();

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.station_edit_name_title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String newName = etName.getText().toString().trim();
                if (newName.isEmpty()) {
                    Log.w(TAG, "showEditNameDialog: Empty name entered, showing error toast");
                    showToast(R.string.station_name_cannot_be_empty);
                    return;
                }

                Log.d(TAG, "showEditNameDialog: Renaming point from '" + position.getName() +
                        "' to '" + newName + "'");

                Position updatedPosition = new Position();
                updatedPosition.setId(position.getId());
                updatedPosition.setName(newName);
                updatedPosition.setPosX(position.getPosX());
                updatedPosition.setPosY(position.getPosY());
                updatedPosition.setYaw(position.getYaw());
                updatedPosition.setFloor(position.getFloor());
                updatedPosition.setMapName(position.getMapName());
                updatedPosition.setType(position.getType());

                List<Position> updateCollection = Collections.singletonList(updatedPosition);

                Log.d(TAG, "showEditNameDialog: Calling mapViewModel.updateCurrentMapPoints for name change");
                mapViewModel.updateCurrentMapPoints(selectedBuilding, currentMap,
                        Integer.parseInt(position.getFloor()), 1, updateCollection);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "showEditNameDialog: Refreshing work points after name change");
                    updateWorkPointsDisplay();
                    showToast(R.string.station_name_updated);
                }, 500);

                dialog.dismiss();
            });
        });

        dialog.show();
    }

    // --------------------------------------------------------------------------------------------
    // Helper Methods

    private String getDisplayNameForPosition(Position position) {
        return PositionDisplayNameHelper.getDisplayName(requireContext(), position);
    }

    private void updateTabSelection(MaterialButton selectedTab, boolean isPointTab) {
        int selectedColor = ContextCompat.getColor(requireContext(), R.color.black);
        int normalColor = ContextCompat.getColor(requireContext(), R.color.tab_text_normal);

        tabPointSetting.setTextColor(isPointTab ? selectedColor : normalColor);
        tabFixedLocation.setTextColor(!isPointTab ? selectedColor : normalColor);
        underlinePoint.setVisibility(isPointTab ? View.VISIBLE : View.INVISIBLE);
        underlineFixed.setVisibility(!isPointTab ? View.VISIBLE : View.INVISIBLE);
    }

    private void showPointLocationContent() {
        Log.d(TAG, "showPointLocationContent: Switching to work points section");
        workPointsContainer.setVisibility(View.VISIBLE);
        fixedLocationContainer.setVisibility(View.GONE);
        commonActions.setVisibility(View.VISIBLE);

        if (currentMapPoints != null) {
            updateWorkBuildingTabs();
        }
    }

    private void showFixedLocationContent() {
        Log.d(TAG, "showFixedLocationContent: Switching to fixed points section");
        workPointsContainer.setVisibility(View.GONE);
        fixedLocationContainer.setVisibility(View.VISIBLE);
        commonActions.setVisibility(View.GONE);

        if (currentMapPoints != null) {
            updateFixedBuildingTabs();
        }
    }

    private void showEmptyStates() {
        workPointAdapter.submitList(new ArrayList<>());

        List<Position> defaultFixedPoints = new ArrayList<>();
        addDefaultFixedPoints(defaultFixedPoints);
        fixedPointAdapter.submitList(defaultFixedPoints);

        updateWorkNoDataVisibility(true);
        updateFixedNoDataVisibility(false);
    }

    private void updateWorkNoDataVisibility(boolean showNoData) {
        if (ivWorkNoData != null && tvWorkNoData != null) {
            ivWorkNoData.setVisibility(showNoData ? View.VISIBLE : View.GONE);
            tvWorkNoData.setVisibility(showNoData ? View.VISIBLE : View.GONE);
        }
        rvWorkPoints.setVisibility(showNoData ? View.GONE : View.VISIBLE);
    }

    private void updateFixedNoDataVisibility(boolean showNoData) {
        if (ivFixedNoData != null && tvFixedNoData != null) {
            ivFixedNoData.setVisibility(showNoData ? View.VISIBLE : View.GONE);
            tvFixedNoData.setVisibility(showNoData ? View.VISIBLE : View.GONE);
        }
        rvFixedLocation.setVisibility(showNoData ? View.GONE : View.VISIBLE);
    }

    private void showToast(String text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void showToast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    // ==================== Work Point Adapter ====================

    private class WorkPointAdapter extends ListAdapter<Position, WorkPointViewHolder> {
        protected WorkPointAdapter() {
            super(new DiffUtil.ItemCallback<Position>() {
                @Override
                public boolean areItemsTheSame(@NonNull Position oldItem, @NonNull Position newItem) {
                    boolean same = oldItem.getId() == newItem.getId();
                    if (!same) {
                        Log.d(TAG, "WorkPointAdapter areItemsTheSame: false - oldId=" + oldItem.getId() +
                                ", newId=" + newItem.getId());
                    }
                    return same;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Position oldItem, @NonNull Position newItem) {
                    boolean contentsSame = oldItem.getId() == newItem.getId() &&
                            oldItem.getName().equals(newItem.getName()) &&
                            Double.compare(oldItem.getPosX(), newItem.getPosX()) == 0 &&
                            Double.compare(oldItem.getPosY(), newItem.getPosY()) == 0 &&
                            Double.compare(oldItem.getYaw(), newItem.getYaw()) == 0 &&
                            oldItem.getFloor().equals(newItem.getFloor());

                    if (!contentsSame) {
                        Log.d(TAG, "WorkPointAdapter areContentsTheSame: false for ID=" + oldItem.getId() +
                                " - oldX=" + oldItem.getPosX() + ", newX=" + newItem.getPosX() +
                                ", oldY=" + oldItem.getPosY() + ", newY=" + newItem.getPosY());
                    }
                    return contentsSame;
                }
            });
        }

        @NonNull
        @Override
        public WorkPointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_location, parent, false);
            return new WorkPointViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull WorkPointViewHolder holder, int position) {
            Position pos = getItem(position);
            Log.d(TAG, "onBindViewHolder: Binding position " + position +
                    ", ID=" + pos.getId() +
                    ", X=" + pos.getPosX() +
                    ", Y=" + pos.getPosY());
            holder.bind(pos);
        }
    }

    private class WorkPointViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvId, tvName, tvX, tvY, tvYaw;
        private final MaterialButton btnReacquire;
        private final MaterialButton btnDelete;

        public WorkPointViewHolder(@NonNull View itemView) {
            super(itemView);
            tvId = itemView.findViewById(R.id.tvId);
            tvName = itemView.findViewById(R.id.tvName);
            tvX = itemView.findViewById(R.id.tvX);
            tvY = itemView.findViewById(R.id.tvY);
            tvYaw = itemView.findViewById(R.id.tvYaw);
            btnReacquire = itemView.findViewById(R.id.btnReacquire);
            btnDelete = itemView.findViewById(R.id.btnDelete);

            btnDelete.setText("🗑");
            btnDelete.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.red_500));
        }

        public void bind(Position position) {
            tvId.setText(String.valueOf(position.getId()));
            String displayName = String.format("%s (F%s)", position.getName(), position.getFloor());
            tvName.setText(displayName);
            tvX.setText(String.format("X: %.3f", position.getPosX()));
            tvY.setText(String.format("Y: %.3f", position.getPosY()));
            tvYaw.setText(String.format("Yaw: %.3f", position.getYaw()));

            btnReacquire.setOnClickListener(v -> {
                if (currentMap == null || currentMap.isEmpty()) {
                    mapViewModel.showNotCurrentFloorToast(itemView.getContext());
                    return;
                }

                if (selectedFloorTab != currentFloor) {
                    showToast(getString(R.string.cannot_update_point_wrong_floor,
                            getFloorDisplayText(selectedFloorTab),
                            getFloorDisplayText(currentFloor)));
                    return;
                }

                if (!selectedBuilding.equals(currentBuilding)) {
                    showToast(getString(R.string.cannot_update_point_wrong_building,
                            getString(R.string.building_prefix_format, selectedBuilding),
                            getString(R.string.building_prefix_format, currentBuilding)));
                    return;
                }

                int targetFloor = Integer.parseInt(position.getFloor());
                if (targetFloor != currentFloor) {
                    new AlertDialog.Builder(requireContext())
                            .setMessage(String.format("The robot is currently on floor %s, but this point is on floor %s. Reacquiring will set the point to the robot's current floor %s. Continue?",
                                    getFloorDisplayText(currentFloor),
                                    getFloorDisplayText(targetFloor),
                                    getFloorDisplayText(currentFloor)))
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                reacquireWorkPoint(position);
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                } else {
                    reacquireWorkPoint(position);
                }
            });

            btnDelete.setOnClickListener(v -> {
                if (currentMap == null || currentMap.isEmpty()) {
                    mapViewModel.showNotCurrentFloorToast(itemView.getContext());
                    return;
                }

                if (selectedFloorTab != currentFloor) {
                    showToast(getString(R.string.cannot_delete_point_wrong_floor,
                            getFloorDisplayText(selectedFloorTab),
                            getFloorDisplayText(currentFloor)));
                    return;
                }

                if (!selectedBuilding.equals(currentBuilding)) {
                    showToast(getString(R.string.cannot_delete_point_wrong_building,
                            getString(R.string.building_prefix_format, selectedBuilding),
                            getString(R.string.building_prefix_format, currentBuilding)));
                    return;
                }

                showDeleteConfirmationDialog(position);
            });

            tvName.setOnClickListener(v -> {
                Log.d(TAG, "Name clicked for work point: " + position.getName());
                showEditNameDialog(position);
            });

            itemView.setOnClickListener(v -> {
                if (!btnDelete.isPressed() && !btnReacquire.isPressed()) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(position.getName())
                            .setMessage(String.format("ID: %d\nFloor: %s\nX: %.2f\nY: %.2f\nYaw: %.2f\nType: %d",
                                    position.getId(), position.getFloor(), position.getPosX(),
                                    position.getPosY(), position.getYaw(), position.getType()))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }

        private void reacquireWorkPoint(Position position) {
            Log.d(TAG, "reacquireWorkPoint: START - Updating work point: " + position.getName() +
                    " (ID: " + position.getId() + ")");

            fetchLatestCurrentPosition(selectedBuilding, currentFloor, currentPosition -> {
                String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, selectedBuilding, currentFloor);
                Log.d(TAG, "reacquireWorkPoint: Current robot position - X=" + currentPosition.getPosX() +
                        ", Y=" + currentPosition.getPosY() + ", Yaw=" + currentPosition.getYaw());

                Position updatedPosition = new Position();
                updatedPosition.setId(position.getId());
                updatedPosition.setName(position.getName());
                updatedPosition.setPosX(currentPosition.getPosX());
                updatedPosition.setPosY(currentPosition.getPosY());
                updatedPosition.setYaw(currentPosition.getYaw());
                updatedPosition.setFloor(String.valueOf(currentFloor));
                updatedPosition.setMapName(fullMapName);
                updatedPosition.setType(1);

                List<Position> updateCollection = Collections.singletonList(updatedPosition);

                Log.d(TAG, "reacquireWorkPoint: Calling mapViewModel.updateCurrentMapPoints for work point");

                mapViewModel.updateCurrentMapPoints(selectedBuilding, currentMap, currentFloor, 1, updateCollection);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "reacquireWorkPoint: Refreshing work points display");
                    updateWorkPointsDisplay();
                    showToast(getString(R.string.position_updated));
                }, 500);

            });
        }
    }

    // ==================== Fixed Point Adapter ====================

    private class FixedPointAdapter extends ListAdapter<Position, FixedPointViewHolder> {
        protected FixedPointAdapter() {
            super(new DiffUtil.ItemCallback<Position>() {
                @Override
                public boolean areItemsTheSame(@NonNull Position oldItem, @NonNull Position newItem) {
                    if (oldItem.isDefaultPoint() || newItem.isDefaultPoint()) {
                        return oldItem.getType() == newItem.getType() &&
                                oldItem.getFloor().equals(newItem.getFloor());
                    }
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Position oldItem, @NonNull Position newItem) {
                    return oldItem.getType() == newItem.getType() &&
                            Double.compare(oldItem.getPosX(), newItem.getPosX()) == 0 &&
                            Double.compare(oldItem.getPosY(), newItem.getPosY()) == 0 &&
                            Double.compare(oldItem.getYaw(), newItem.getYaw()) == 0 &&
                            oldItem.getFloor().equals(newItem.getFloor());
                }
            });
        }

        @NonNull
        @Override
        public FixedPointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_location, parent, false);
            return new FixedPointViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FixedPointViewHolder holder, int position) {
            Position pos = getItem(position);
            Log.d(TAG, "FixedPointAdapter onBindViewHolder: Binding position " + position +
                    ", Type=" + pos.getType() +
                    ", Name=" + pos.getName() +
                    ", X=" + pos.getPosX() +
                    ", Y=" + pos.getPosY());
            holder.bind(pos);
        }
    }

    private class FixedPointViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvId, tvName, tvX, tvY, tvYaw;
        private final MaterialButton btnReacquire;
        private final MaterialButton btnDelete;
        private Position currentPosition;

        public FixedPointViewHolder(@NonNull View itemView) {
            super(itemView);
            tvId = itemView.findViewById(R.id.tvId);
            tvName = itemView.findViewById(R.id.tvName);
            tvX = itemView.findViewById(R.id.tvX);
            tvY = itemView.findViewById(R.id.tvY);
            tvYaw = itemView.findViewById(R.id.tvYaw);
            btnReacquire = itemView.findViewById(R.id.btnReacquire);
            btnDelete = itemView.findViewById(R.id.btnDelete);

            btnDelete.setText("⊗");
            btnDelete.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_red_dark));
        }

        @SuppressLint("SetTextI18n")
        public void bind(Position position) {
            this.currentPosition = position;

            if (position.getId() >= 0) {
                tvId.setText("ID:" + position.getId());
            } else {
                tvId.setText("ID:--");
            }
            tvName.setText(getDisplayNameForPosition(position));

            tvX.setText(String.format("X:%.3f", position.getPosX()));
            tvY.setText(String.format("Y:%.3f", position.getPosY()));
            tvYaw.setText(String.format("Yaw:%.3f", position.getYaw()));

            boolean isZeroPoint = Math.abs(position.getPosX()) < 0.001 &&
                    Math.abs(position.getPosY()) < 0.001 &&
                    Math.abs(position.getYaw()) < 0.001;
            int textColor = isZeroPoint ?
                    ContextCompat.getColor(itemView.getContext(), R.color.gray) :
                    ContextCompat.getColor(itemView.getContext(), R.color.black);
            tvX.setTextColor(textColor);
            tvY.setTextColor(textColor);
            tvYaw.setTextColor(textColor);
            tvName.setTextColor(textColor);

            btnReacquire.setOnClickListener(v -> {
                if (selectedFixedFloorTab != currentFloor) {
                    showToast(getString(R.string.cannot_update_point_wrong_floor,
                            getFloorDisplayText(selectedFixedFloorTab),
                            getFloorDisplayText(currentFloor)));
                    return;
                }

                if (!selectedFixedBuilding.equals(currentBuilding)) {
                    showToast(getString(R.string.cannot_update_point_wrong_building,
                            getString(R.string.building_prefix_format, selectedFixedBuilding),
                            getString(R.string.building_prefix_format, currentBuilding)));
                    return;
                }

                showReacquireSpecialPointDialog(position);
            });

            btnDelete.setOnClickListener(v -> {
                if (selectedFixedFloorTab != currentFloor) {
                    showToast(getString(R.string.cannot_reset_point_wrong_floor,
                            getFloorDisplayText(selectedFixedFloorTab),
                            getFloorDisplayText(currentFloor)));
                    return;
                }

                if (!selectedFixedBuilding.equals(currentBuilding)) {
                    showToast(getString(R.string.cannot_reset_point_wrong_building,
                            getString(R.string.building_prefix_format, selectedFixedBuilding),
                            getString(R.string.building_prefix_format, currentBuilding)));
                    return;
                }

                showResetCoordinatesDialog(position);
            });

            tvName.setOnClickListener(v -> {
                if (position.getType() == 13) {
                    performRelocalize(position);
                } else {
                    String displayName = getDisplayNameForPosition(position);
                    new AlertDialog.Builder(requireContext())
                            .setTitle(displayName)
                            .setMessage(String.format("ID: %d\nType: %d\nFloor: %s\nX: %.3f\nY: %.3f\nYaw: %.3f",
                                    position.getId(), position.getType(), position.getFloor(),
                                    position.getPosX(), position.getPosY(), position.getYaw()))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });

            itemView.setOnClickListener(v -> {
                if (!btnReacquire.isPressed() && !btnDelete.isPressed()) {
                    if (position.getType() != 13) {
                        String displayName = getDisplayNameForPosition(position);
                        new AlertDialog.Builder(requireContext())
                                .setTitle(displayName)
                                .setMessage(String.format("ID: %d\nType: %d\nFloor: %s\nX: %.3f\nY: %.3f\nYaw: %.3f",
                                        position.getId(), position.getType(), position.getFloor(),
                                        position.getPosX(), position.getPosY(), position.getYaw()))
                                .setPositiveButton("OK", null)
                                .show();
                    }
                }
            });
        }

        @SuppressLint("StringFormatInvalid")
        private void performRelocalize(Position relocalizePoint) {
            if (selectedFixedFloorTab != currentFloor) {
                showToast(getString(R.string.cannot_relocalize_wrong_floor,
                        getFloorDisplayText(selectedFixedFloorTab),
                        getFloorDisplayText(currentFloor)));
                return;
            }

            if (!selectedFixedBuilding.equals(currentBuilding)) {
                showToast(getString(R.string.cannot_relocalize_wrong_building,
                        getString(R.string.building_prefix_format, selectedFixedBuilding),
                        getString(R.string.building_prefix_format, currentBuilding)));
                return;
            }

            if (currentMap == null || currentMap.isEmpty()) {
                mapViewModel.showNotCurrentFloorToast(requireContext());
                return;
            }

            String fullMapName = MultiBuildingMapPoints.generateMapName(currentMap, currentBuilding, currentFloor);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.relocalize_confirmation_title)
                    .setMessage(getString(R.string.relocalize_confirmation_message,
                            relocalizePoint.getName()))
                    .setPositiveButton(R.string.confirm, (dialog, which) -> {
                        try {
                            if (commandClient == null || !commandClient.isConnected()) {
                                showToast(getString(R.string.websocket_not_connected));
                                return;
                            }

                            commandClient.mapSwitchAsync(
                                    fullMapName,
                                    relocalizePoint.getPosX(),
                                    relocalizePoint.getPosY(),
                                    relocalizePoint.getYaw(),
                                    new CommandWebSocketClient.Callback() {
                                        @Override
                                        public void onSuccess(String response) {
                                            new Handler(Looper.getMainLooper()).post(() -> {
                                                showToast(getString(R.string.relocalize_success));
                                                Log.d(TAG, "Relocalize success: " + response);
                                            });
                                        }

                                        @SuppressLint("StringFormatInvalid")
                                        @Override
                                        public void onError(String error) {
                                            new Handler(Looper.getMainLooper()).post(() -> {
                                                showToast(getString(R.string.relocalize_failed, error));
                                                Log.e(TAG, "Relocalize failed: " + error);
                                            });
                                        }
                                    }
                            );
                        } catch (Exception e) {
                            Log.e(TAG, "Error during relocalize", e);
                            showToast(getString(R.string.relocalize_failed, e.getMessage()));
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void showResetCoordinatesDialog(Position position) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.reset_coordinates_title)
                    .setMessage(getString(R.string.reset_coordinates_message, position.getName()))
                    .setPositiveButton(R.string.confirm, (dialog, which) -> {
                        List<Position> updateCollection = new ArrayList<>();
                        mapViewModel.updateCurrentMapPoints(selectedFixedBuilding, currentMap, currentFloor,
                                position.getType(), updateCollection);

                        showToast(getString(R.string.coordinates_reset));
                        updateFixedPointsDisplay();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }
}
