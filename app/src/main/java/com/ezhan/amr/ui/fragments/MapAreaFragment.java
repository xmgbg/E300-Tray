package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.viewmodels.SharedViewModel;
import com.ezhan.amr.viewmodels.BasicViewModel;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.MapArea;
import com.ezhan.amr.data.datatype.OtherRobot;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

public class MapAreaFragment extends Fragment {
    private static final String TAG = "MapAreaFragment";
    private static final String PREFS_NAME = "map_area_settings";
    private static final String KEY_MAP_AREAS = "map_areas_json";
    private static final Type MAP_AREAS_TYPE = new TypeToken<List<MapArea>>(){}.getType();
    private static final int MIN_LORA_BYTE = 0;
    private static final int MAX_LORA_BYTE = 255;
    private static final String DEFAULT_AREA_CHANNEL = "01";
    private static final String DEFAULT_AREA_ADDRESS = "01";
    private final Gson gson = new Gson();

    // Area type data values (Chinese identifiers stored in SharedPreferences, used for comparisons)
    private static final String[] AREA_TYPE_VALUES = {"门控区域", "信息区域", "交管区域"};
    
    private WebView webView;
    private Button btnAddMap;
    private LinearLayout mapListContainer;
    private List<String> mapList = new ArrayList<>();
    private List<MapArea> mapAreas = new ArrayList<>();
    private Map<String, Long> lastSendTimeMap = new HashMap<>(); // 存储每个区域的最后发送时间
    private CommandWebSocketClient commandClient;
    private DataStoreManager dataStoreManager;
    private SharedPreferences sharedPreferences;

    private OnBackPressedListener onBackPressedListener;
    public interface OnBackPressedListener {
        boolean onBackPressed();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() != null && getActivity().getWindow() != null) {
            getActivity().getWindow().setBackgroundDrawableResource(android.R.color.white);
        }

        btnAddMap = view.findViewById(R.id.btnAddMap);
        Button btnCheckRobotArea = view.findViewById(R.id.btnCheckRobotArea);
        mapListContainer = view.findViewById(R.id.mapListContainer);

        // Get CommandWebSocketClient from SharedViewModel
        SharedViewModel sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        // 初始化WebSocket连接
        Log.d(TAG, "Initializing WebSocket...");
        sharedViewModel.initializeWebSocket();
        commandClient = sharedViewModel.getCommandClient();
        Log.d(TAG, "CommandClient initialized: " + (commandClient != null));

        btnAddMap.setOnClickListener(v -> {
            Log.d(TAG, "Add Map button clicked");
            showMapSelectionDialog();
        });

        btnCheckRobotArea.setOnClickListener(v -> {
            Log.d(TAG, "Check Robot Area button clicked");
            checkRobotCurrentArea();
        });

        webView = view.findViewById(R.id.webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });
        webView.getSettings().setJavaScriptEnabled(true);

        // 初始化DataStoreManager
        dataStoreManager = DataStoreManager.getInstance(getContext());
        sharedPreferences = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 加载保存的地图区域数据
        loadSavedMapAreas();
    }

    private void showMapSelectionDialog() {
        Log.d(TAG, "showMapSelectionDialog called");
        if (commandClient != null) {
            Log.d(TAG, "CommandClient is not null, connected: " + commandClient.isConnected());
            if (commandClient.isConnected()) {
                fetchMapList();
            } else {
                // 在主线程中显示Toast
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), getString(R.string.maparea_ws_not_connected_connecting), Toast.LENGTH_SHORT).show();
                });
                // 尝试重新连接
                SharedViewModel sharedViewModel = MyApplication.getInstance().getSharedViewModel();
                Log.d(TAG, "Reconnecting WebSocket...");
                sharedViewModel.reconnectClients();
                // 延迟一秒后再次尝试获取地图列表
                new android.os.Handler().postDelayed(() -> {
                    Log.d(TAG, "Checking connection after reconnection attempt: " + commandClient.isConnected());
                    if (commandClient.isConnected()) {
                        fetchMapList();
                    } else {
                        // 在主线程中显示Toast
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), getString(R.string.maparea_ws_connect_failed), Toast.LENGTH_SHORT).show();
                        });
                    }
                }, 1000);
            }
        } else {
            // 在主线程中显示Toast
            getActivity().runOnUiThread(() -> {
                Toast.makeText(getContext(), getString(R.string.maparea_ws_client_not_init), Toast.LENGTH_SHORT).show();
            });
            // 初始化WebSocket连接
            SharedViewModel sharedViewModel = MyApplication.getInstance().getSharedViewModel();
            Log.d(TAG, "Initializing WebSocket...");
            sharedViewModel.initializeWebSocket();
            commandClient = sharedViewModel.getCommandClient();
            Log.d(TAG, "CommandClient initialized after null check: " + (commandClient != null));
            // 延迟一秒后再次尝试获取地图列表
            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "Checking connection after initialization: " + (commandClient != null && commandClient.isConnected()));
                if (commandClient != null && commandClient.isConnected()) {
                    fetchMapList();
                } else {
                    // 在主线程中显示Toast
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), getString(R.string.maparea_ws_init_failed), Toast.LENGTH_SHORT).show();
                    });
                }
            }, 1000);
        }
    }

    private void loadSavedMapAreas() {
        // 使用 SharedPreferences 加载，避免 DataStore 断电丢失问题
        String mapAreaJson = sharedPreferences.getString(KEY_MAP_AREAS, "");
        if (!mapAreaJson.isEmpty()) {
            try {
                List<MapArea> loadedAreas = gson.fromJson(mapAreaJson, MAP_AREAS_TYPE);
                if (loadedAreas != null && !loadedAreas.isEmpty()) {
                    // 在主线程中更新UI
                    getActivity().runOnUiThread(() -> {
                        mapAreas.clear();
                        mapAreas.addAll(loadedAreas);
                        // 显示已保存的地图区域统计
                        displaySavedMapAreas();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "加载地图区域失败", e);
            }
        }
    }

    /** 显示已保存的地图区域统计信息 */
    private void displaySavedMapAreas() {
        if (mapAreas == null || mapAreas.isEmpty()) {
            Log.d(TAG, "No saved map areas to display");
            return;
        }

        // 按地图名称分组
        Map<String, List<MapArea>> areasByMap = new HashMap<>();
        for (MapArea area : mapAreas) {
            String mapName = area.getMapName();
            if (mapName != null && !mapName.isEmpty()) {
                if (!areasByMap.containsKey(mapName)) {
                    areasByMap.put(mapName, new ArrayList<>());
                }
                areasByMap.get(mapName).add(area);
            }
        }

        // 清空并显示分组后的地图
        mapListContainer.removeAllViews();
        for (Map.Entry<String, List<MapArea>> entry : areasByMap.entrySet()) {
            String mapName = entry.getKey();
            List<MapArea> areas = entry.getValue();

            // 统计各类型区域数量
            int doorControlCount = 0;
            int infoCount = 0;
            int trafficCount = 0;
            for (MapArea area : areas) {
                String type = area.getType();
                if ("门控区域".equals(type)) {
                    doorControlCount++;
                } else if ("信息区域".equals(type)) {
                    infoCount++;
                } else if ("交管区域".equals(type)) {
                    trafficCount++;
                }
            }

            // 创建地图项视图
            View mapItemView = LayoutInflater.from(getContext()).inflate(R.layout.item_map_area, mapListContainer, false);
            TextView tvMapName = mapItemView.findViewById(R.id.tvMapName);
            Button btnEditArea = mapItemView.findViewById(R.id.btnEditArea);
            Button btnDeleteMap = mapItemView.findViewById(R.id.btnDeleteMap);

            // 显示地图名称和区域统计
            StringBuilder sb = new StringBuilder();
            sb.append(mapName).append("\n");
            sb.append(getString(R.string.total_areas_format, areas.size()));
            if (doorControlCount > 0) sb.append(" | ").append(getString(R.string.door_control_count, doorControlCount));
            if (infoCount > 0) sb.append(" | ").append(getString(R.string.info_count, infoCount));
            if (trafficCount > 0) sb.append(" | ").append(getString(R.string.traffic_count, trafficCount));
            tvMapName.setText(sb.toString());

            // 存储第一个区域的地图参数用于编辑
            MapArea firstArea = areas.get(0);

            btnEditArea.setOnClickListener(v -> {
                // 需要先获取地图数据才能编辑
                showMapEditWithExistingAreas(mapName, firstArea);
            });

            btnDeleteMap.setOnClickListener(v -> {
                // 显示确认对话框
                new AlertDialog.Builder(getContext())
                        .setTitle(R.string.confirm_delete_title)
                        .setMessage(getString(R.string.confirm_delete_map_areas_message, mapName, areas.size()))
                        .setPositiveButton(R.string.delete, (dialog, which) -> {
                            // 删除该地图的所有区域
                            mapAreas.removeAll(areas);
                            saveMapAreas();
                            mapListContainer.removeView(mapItemView);
                            Toast.makeText(getContext(), getString(R.string.map_areas_deleted, mapName), Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });

            mapListContainer.addView(mapItemView);
        }
        Log.d(TAG, "Displayed " + areasByMap.size() + " maps with areas");
    }

    /** 编辑已有区域的地图 (需要重新获取地图图像数据) */
    private void showMapEditWithExistingAreas(String mapName, MapArea sampleArea) {
        if (commandClient == null || !commandClient.isConnected()) {
            Toast.makeText(getContext(), getString(R.string.maparea_ws_not_connected_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), getString(R.string.maparea_loading_map_data), Toast.LENGTH_SHORT).show();
        commandClient.getMapDataAsync(mapName, new CommandWebSocketClient.MapDataCallback() {
            @Override
            public void onSuccess(JSONObject mapData) {
                getActivity().runOnUiThread(() -> {
                    float resolution = (float) mapData.optDouble("resolution", sampleArea.getResolution());
                    float offx = (float) mapData.optDouble("offx", sampleArea.getMapOriginX());
                    float offy = (float) mapData.optDouble("offy", sampleArea.getMapOriginY());
                    float offz = (float) mapData.optDouble("offz", sampleArea.getMapOriginZ());
                    int width = mapData.optInt("width", 0);
                    int height = mapData.optInt("height", 0);
                    String base64 = mapData.optString("base64", "");

                    showAreaEditDialog(mapName, base64, resolution, offx, offy, offz, width, height);
                });
            }

            @Override
            public void onError(String error) {
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), getString(R.string.maparea_get_map_data_failed, error), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveMapAreas() {
        try {
            String json = gson.toJson(new ArrayList<>(mapAreas), MAP_AREAS_TYPE);
            // 使用 SharedPreferences.commit() 同步写入，防止断电数据丢失
            sharedPreferences.edit()
                    .putString(KEY_MAP_AREAS, json)
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "保存地图区域失败", e);
        }
    }

    /**
     * 显示全局交管机器人列表管理对话框。
     * 列表为所有交管区域共用, 配置一次即可, 无需在每个交管区域重复添加。
     */
    private void showTrafficRobotsDialog() {
        if (dataStoreManager == null) {
            Toast.makeText(getContext(), getString(R.string.maparea_data_store_not_init), Toast.LENGTH_SHORT).show();
            return;
        }
        dataStoreManager.getTrafficRobots().first(Collections.emptyList())
                .subscribe(robots -> {
                    requireActivity().runOnUiThread(() -> {
                        final List<OtherRobot> dialogRobots = new ArrayList<>();
                        if (robots != null) {
                            dialogRobots.addAll(robots);
                        }
                        buildTrafficRobotsDialog(dialogRobots);
                    });
                }, e -> {
                    Log.e(TAG, "加载交管机器人列表失败", e);
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), getString(R.string.maparea_load_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                });
    }

    private void buildTrafficRobotsDialog(final List<OtherRobot> dialogRobots) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_traffic_robots, null);
        builder.setView(dialogView);

        ScrollView scrollView = dialogView.findViewById(R.id.svTrafficRobots);
        LinearLayout llRobotList = dialogView.findViewById(R.id.llTrafficRobotList);
        Button btnAdd = dialogView.findViewById(R.id.btnAddTrafficRobot);
        Button btnSave = dialogView.findViewById(R.id.btnSaveTrafficRobots);

        btnAdd.setOnClickListener(v -> {
            dialogRobots.add(new OtherRobot(0, "", ""));
            refreshRobotList(scrollView, llRobotList, dialogRobots);
        });

        refreshRobotList(scrollView, llRobotList, dialogRobots);

        builder.setTitle(getString(R.string.maparea_traffic_robot_list_title));
        builder.setNegativeButton(getString(R.string.cancel), null);
        final AlertDialog dialog = builder.create();

        btnSave.setOnClickListener(v -> {
            collectRobotsFromList(llRobotList, dialogRobots);
            if (!validateTrafficRobots(dialogRobots)) {
                return;
            }
            dataStoreManager.saveTrafficRobots(new ArrayList<>(dialogRobots));
            // 通知 TrafficAreaManager 重新加载白名单
            com.ezhan.amr.navigation.area.TrafficAreaManager mgr =
                    MyApplication.getInstance().getTrafficAreaManager();
            if (mgr != null) {
                mgr.reloadTrustedRobots();
            }
            Toast.makeText(getContext(), getString(R.string.maparea_robots_saved, dialogRobots.size()), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        if (dialog.getWindow() != null) {
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            int dialogWidth = (int) (displayMetrics.widthPixels * 0.92f);
            int dialogMaxHeight = (int) (displayMetrics.heightPixels * 0.55f);
            dialog.getWindow().setLayout(dialogWidth, dialogMaxHeight);
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        dialog.show();
    }

    /**
     * 从底盘控制器同步命名区域 (region id=6 中符合命名规范的条目)。
     * 合并策略: 同名区域用控制器版本覆盖几何/类型/mapName, 保留本地 channel/address;
     *          控制器新增的区域加入本地 (channel/address 留空, 待用户补充)。
     */
    private void syncControllerAreas() {
        if (commandClient == null || !commandClient.isConnected()) {
            Toast.makeText(getContext(), getString(R.string.maparea_ws_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentMapName == null || currentMapName.trim().isEmpty()) {
            Toast.makeText(getContext(), getString(R.string.maparea_map_name_empty), Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "syncControllerAreas: mapName=" + currentMapName);
        Toast.makeText(getContext(), getString(R.string.maparea_syncing_controller_area), Toast.LENGTH_SHORT).show();

        commandClient.getNamedAreasAsync(currentMapName, new CommandWebSocketClient.NamedAreasCallback() {
            @Override
            public void onSuccess(List<MapArea> namedAreas) {
                getActivity().runOnUiThread(() -> {
                    mergeControllerAreas(namedAreas);
                    saveMapAreas();
                    if (currentAreaListView != null) {
                        updateAreaList(currentAreaListView);
                    }
                    drawSavedAreas();
                    Toast.makeText(getContext(),
                            getString(R.string.maparea_sync_completed, namedAreas == null ? 0 : namedAreas.size()),
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), getString(R.string.maparea_sync_failed, error), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 将控制器拉取的区域合并到本地 mapAreas (同名覆盖几何, 保留本地参数; 新增的直接加入) */
    private void mergeControllerAreas(List<MapArea> controllerAreas) {
        if (controllerAreas == null || controllerAreas.isEmpty()) {
            Log.d(TAG, "mergeControllerAreas: 控制器区域为空, 无需合并");
            return;
        }

        java.util.Map<String, MapArea> controllerByName = new HashMap<>();
        for (MapArea ca : controllerAreas) {
            if (ca != null && ca.getName() != null) {
                controllerByName.put(ca.getName(), ca);
            }
        }

        java.util.Set<String> handledNames = new java.util.HashSet<>();
        // 1. 现有区域: 同名用控制器版本覆盖几何/类型/mapName, 保留本地 channel/address
        for (MapArea local : mapAreas) {
            if (local.getName() != null && controllerByName.containsKey(local.getName())) {
                MapArea ctrl = controllerByName.get(local.getName());
                local.setPolygonPoints(ctrl.getPolygonPoints());
                local.setType(ctrl.getType());
                local.setMapName(ctrl.getMapName());
                handledNames.add(local.getName());
            }
        }
        // 2. 控制器新增的区域 (本地没有同名的)
        for (MapArea ctrl : controllerAreas) {
            if (ctrl.getName() != null && !handledNames.contains(ctrl.getName())) {
                mapAreas.add(ctrl);
                handledNames.add(ctrl.getName());
            }
        }
        Log.d(TAG, "mergeControllerAreas: 合并后共 " + mapAreas.size()
                + " 个区域, 控制器返回 " + controllerAreas.size() + " 个");
    }

    private void fetchMapList() {
        Log.d(TAG, "fetchMapList called");
        commandClient.getAllMapsAsync(new CommandWebSocketClient.MapListCallback() {
            @Override
            public void onSuccess(List<String> maps) {
                Log.d(TAG, "getAllMapsAsync onSuccess: " + maps);
                // 在主线程中更新UI
                getActivity().runOnUiThread(() -> {
                    mapList.clear();
                    mapList.addAll(maps);
                    if (!mapList.isEmpty()) {
                        showMapListDialog();
                    } else {
                        Toast.makeText(getContext(), getString(R.string.maparea_no_available_maps), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "getAllMapsAsync onError: " + error);
                // 在主线程中显示Toast
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), getString(R.string.maparea_get_map_list_failed, error), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showMapListDialog() {
        Log.d(TAG, "showMapListDialog called with mapList: " + mapList);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(getString(R.string.maparea_select_map));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, mapList);
        builder.setAdapter(adapter, (dialog, which) -> {
            String selectedMap = mapList.get(which);
            Log.d(TAG, "Map selected: " + selectedMap);
            loadMapData(selectedMap);
        });

        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void loadMapData(String mapName) {
        Log.d(TAG, "loadMapData called for map: " + mapName);

        commandClient.getMapDataAsync(mapName, new CommandWebSocketClient.MapDataCallback() {
            @Override
            public void onSuccess(JSONObject mapData) {
                Log.d(TAG, "getMapDataAsync onSuccess: " + mapData);
                getActivity().runOnUiThread(() -> {
                    try {
                        addMapItem(mapData);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error adding map item", e);
                        Toast.makeText(getContext(), getString(R.string.maparea_add_map_item_failed), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "getMapDataAsync onError: " + error);
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), getString(R.string.maparea_get_map_data_failed, error), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void addMapItem(JSONObject mapData) throws JSONException {
        String mapName = mapData.getString("mapName");
        String base64 = mapData.getString("base64");
        float resolution = (float) mapData.optDouble  ("resolution", 0.05f);
        float offx = (float) mapData.optDouble("offx", -10f);
        float offy = (float) mapData.optDouble("offy", -10f);
        float offz = (float) mapData.optDouble("offz", 0f);
        int width = mapData.optInt("width", 0);
        int height = mapData.optInt("height", 0);

        View mapItemView = LayoutInflater.from(getContext()).inflate(R.layout.item_map_area, mapListContainer, false);
        TextView tvMapName = mapItemView.findViewById(R.id.tvMapName);
        Button btnEditArea = mapItemView.findViewById(R.id.btnEditArea);
        Button btnDeleteMap = mapItemView.findViewById(R.id.btnDeleteMap);

        tvMapName.setText(mapName);

        btnEditArea.setOnClickListener(v -> showAreaEditDialog(mapName, base64, resolution, offx, offy, offz, width, height));
        btnDeleteMap.setOnClickListener(v -> mapListContainer.removeView(mapItemView));

        mapListContainer.addView(mapItemView);
    }

    private ImageView mapImageView;
    private List<Float> polygonPoints = new ArrayList<>();
    private boolean isDrawing = false;
    private String currentMapName;
    private String currentMapBase64;
    private Bitmap currentBitmap; // 用于存储当前绘制的Bitmap
    private ListView currentAreaListView; // 当前区域编辑对话框的列表引用, 用于异步刷新
    private List<List<Float>> historyPoints = new ArrayList<>(); // 用于撤销操作的历史记录
    private List<List<Float>> redoPoints = new ArrayList<>(); // 用于恢复操作的历史记录
    private Button btnSaveArea;
    private Button btnUndo;
    private Button btnRedo;
    private AlertDialog currentDialog;
    private float currentResolution = 0.05f; // 默认分辨率
    private float currentMapOriginX = -10f; // 默认原点X
    private float currentMapOriginY = -10f; // 默认原点Y
    private float currentMapOriginZ = 0f; // 默认原点Z
    private int currentMapWidth; // 当前地图宽度
    private int currentMapHeight; // 当前地图高度

    private void showAreaEditDialog(String mapName, String base64, float resolution, float mapOriginX, float mapOriginY, float mapOriginZ, int width, int height) {
        currentMapName = mapName;
        currentMapBase64 = base64;
        currentResolution = resolution;
        currentMapOriginX = mapOriginX;
        currentMapOriginY = mapOriginY;
        currentMapOriginZ = mapOriginZ;
        currentMapWidth = width;
        currentMapHeight = height;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_map_area_edit, null);
        builder.setView(dialogView);

        mapImageView = dialogView.findViewById(R.id.mapImageView);
        Button btnAddArea = dialogView.findViewById(R.id.btnAddArea);
        btnSaveArea = dialogView.findViewById(R.id.btnSaveArea);
        btnUndo = dialogView.findViewById(R.id.btnUndo);
        btnRedo = dialogView.findViewById(R.id.btnRedo);
        ListView areaListView = dialogView.findViewById(R.id.areaListView);
        Button btnSyncController = dialogView.findViewById(R.id.btnSyncController);
        Button btnTrafficRobots = dialogView.findViewById(R.id.btnTrafficRobots);
        currentAreaListView = areaListView;

        btnSyncController.setOnClickListener(v -> syncControllerAreas());
        btnTrafficRobots.setOnClickListener(v -> showTrafficRobotsDialog());

        // 加载Base64图片
        try {
            // 移除data:image/jpeg;base64,前缀
            String base64Image = base64;
            if (base64.contains(",")) {
                base64Image = base64.split(",")[1];
            }
            byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
            currentBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            // 设置图片，等待对话框显示后再绘制已保存的区域
            mapImageView.setImageBitmap(currentBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error loading base64 image", e);
            Toast.makeText(getContext(), getString(R.string.maparea_load_map_image_failed), Toast.LENGTH_SHORT).show();
        }

        btnAddArea.setOnClickListener(v -> {
            // 进入绘制模式
            if (!isDrawing) {
                startDrawingMode();
            } else {
                finishDrawingMode();
            }
        });

        // 保存按钮点击事件
        btnSaveArea.setOnClickListener(v -> {
            if (polygonPoints.size() >= 6) { // 至少3个点（每个点有x和y坐标）
                // 创建新区域，保存多边形点、地图名称、分辨率和原点坐标
                MapArea newArea = new MapArea(getString(R.string.area_default_name, mapAreas.size() + 1), AREA_TYPE_VALUES[0], DEFAULT_AREA_CHANNEL, DEFAULT_AREA_ADDRESS, currentMapName, new ArrayList<>(polygonPoints), currentResolution, currentMapOriginX, currentMapOriginY, currentMapOriginZ);
                mapAreas.add(newArea);
                
                // 显示区域详情对话框，让用户输入区域信息
                showAreaDetailDialog(newArea, mapAreas.size() - 1);
                
                // 退出绘制模式
                isDrawing = false;
                polygonPoints.clear();
                historyPoints.clear();
                redoPoints.clear();
                
                // 重新绘制地图，显示已保存的区域
                drawSavedAreas();
            } else {
                Toast.makeText(getContext(), getString(R.string.maparea_polygon_needs_3_vertices), Toast.LENGTH_SHORT).show();
            }
        });

        // 撤销按钮点击事件
        btnUndo.setOnClickListener(v -> {
            if (!historyPoints.isEmpty()) {
                // 保存当前状态到恢复栈
                redoPoints.add(new ArrayList<>(polygonPoints));
                // 恢复上一个状态
                polygonPoints = historyPoints.remove(historyPoints.size() - 1);
                // 重新绘制
                drawPolygon();
            } else {
                Toast.makeText(getContext(), getString(R.string.maparea_no_undo_operations), Toast.LENGTH_SHORT).show();
            }
        });

        // 恢复按钮点击事件
        btnRedo.setOnClickListener(v -> {
            if (!redoPoints.isEmpty()) {
                // 保存当前状态到历史栈
                historyPoints.add(new ArrayList<>(polygonPoints));
                // 恢复下一个状态
                polygonPoints = redoPoints.remove(redoPoints.size() - 1);
                // 重新绘制
                drawPolygon();
            } else {
                Toast.makeText(getContext(), getString(R.string.maparea_no_redo_operations), Toast.LENGTH_SHORT).show();
            }
        });

        // 为了获取准确的点击坐标，使用OnTouchListener
        mapImageView.setOnTouchListener((v, event) -> {
            if (isDrawing && event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                // 保存当前状态到历史栈
                historyPoints.add(new ArrayList<>(polygonPoints));
                // 清空恢复栈
                redoPoints.clear();
                
                // 获取相对于ImageView的坐标
                float x = event.getX();
                float y = event.getY();
                
                // 确保坐标在ImageView范围内
                x = Math.max(0, Math.min(x, v.getWidth()));
                y = Math.max(0, Math.min(y, v.getHeight()));
                
                // 使用ImageView的Matrix来获取触摸点对应的图像像素坐标
                android.graphics.Matrix inverseMatrix = new android.graphics.Matrix();
                if (mapImageView.getImageMatrix().invert(inverseMatrix)) {
                    // 创建一个点数组，包含触摸坐标
                    float[] touchPoint = new float[]{x, y};
                    // 使用逆矩阵将屏幕坐标转换为图像像素坐标
                    inverseMatrix.mapPoints(touchPoint);
                    
                    float imageX = touchPoint[0];
                    float imageY = touchPoint[1];
                    
                    // 确保图像坐标在Bitmap范围内
                    imageX = Math.max(0, Math.min(imageX, currentBitmap.getWidth()));
                    imageY = Math.max(0, Math.min(imageY, currentBitmap.getHeight()));
                    
                    // 将图像坐标转换为世界坐标
                    float[] worldCoords = imageToWorld(imageX, imageY);
                    
                    // 记录世界坐标
                    polygonPoints.add(worldCoords[0]);
                    polygonPoints.add(worldCoords[1]);
                    
                    // 重新绘制地图，显示已点击的点
                    drawPolygon();
                }
            }
            return false; // 让其他触摸事件继续处理
        });

        updateAreaList(areaListView);
        builder.setTitle(getString(R.string.maparea_area_edit));
        builder.setNegativeButton(getString(R.string.maparea_close), (dialog, which) -> {
            // 退出绘制模式
            isDrawing = false;
            polygonPoints.clear();
        });
        
        currentDialog = builder.create();
        // 设置对话框大小为屏幕的90%
        currentDialog.setOnShowListener(dialogInterface -> {
            if (getContext() != null) {
                android.view.WindowManager.LayoutParams params = currentDialog.getWindow().getAttributes();
                params.width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.9);
                params.height = (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.9);
                currentDialog.getWindow().setAttributes(params);

                // 等待 mapImageView 完成布局后再绘制已保存的区域
                mapImageView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        // 移除监听器，避免重复调用
                        mapImageView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        // 绘制已保存的区域
                        drawSavedAreas();
                    }
                });
            }
        });
        currentDialog.show();
    }

    private void startDrawingMode() {
        isDrawing = true;
        polygonPoints.clear();
        Toast.makeText(getContext(), getString(R.string.maparea_draw_mode_instruction), Toast.LENGTH_SHORT).show();
    }

    private void finishDrawingMode() {
        if (polygonPoints.size() >= 6) { // 至少3个点（每个点有x和y坐标）
            // 创建新区域，保存多边形点、地图名称、分辨率和原点坐标
            MapArea newArea = new MapArea(getString(R.string.area_default_name, mapAreas.size() + 1), AREA_TYPE_VALUES[0], DEFAULT_AREA_CHANNEL, DEFAULT_AREA_ADDRESS, currentMapName, new ArrayList<>(polygonPoints), currentResolution, currentMapOriginX, currentMapOriginY, currentMapOriginZ);
            mapAreas.add(newArea);
            
            // 显示区域详情对话框，让用户输入区域信息
            showAreaDetailDialog(newArea, mapAreas.size() - 1);
            
            // 退出绘制模式
            isDrawing = false;
            polygonPoints.clear();
            
            // 重新绘制地图，显示已保存的区域
            drawSavedAreas();
        } else {
            Toast.makeText(getContext(), getString(R.string.maparea_polygon_needs_3_vertices), Toast.LENGTH_SHORT).show();
        }
    }

    private void drawSavedAreas() {
        try {
            if (currentBitmap == null) {
                return;
            }
            
            // 创建可绘制的Bitmap
            Bitmap mutableBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(android.graphics.Color.BLUE);
            paint.setStrokeWidth(3);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            
            // 绘制地图原点位置 (mappos)
            android.graphics.Paint originPaint = new android.graphics.Paint();
            originPaint.setColor(android.graphics.Color.GREEN);
            originPaint.setStrokeWidth(5);
            originPaint.setStyle(android.graphics.Paint.Style.FILL);
            // 原点坐标是 (0, 0) 在世界坐标系中
            float[] originImageCoords = worldToImage(0, 0);
            if (originImageCoords[0] >= 0 && originImageCoords[0] <= mutableBitmap.getWidth() && 
                originImageCoords[1] >= 0 && originImageCoords[1] <= mutableBitmap.getHeight()) {
                canvas.drawCircle(originImageCoords[0], originImageCoords[1], 10, originPaint);
                // 绘制原点标签
                android.graphics.Paint textPaint = new android.graphics.Paint();
                textPaint.setColor(android.graphics.Color.BLACK);
                textPaint.setTextSize(20);
                canvas.drawText(getString(R.string.area_origin_label), originImageCoords[0] + 15, originImageCoords[1] - 15, textPaint);
            }

            // 绘制当前地图的所有已保存区域
            for (MapArea area : mapAreas) {
                // 只绘制当前地图的区域
                if (area.getMapName() != null && area.getMapName().equals(currentMapName)) {
                    List<Float> points = area.getPolygonPoints();
                    if (points != null && points.size() >= 6) {
                        android.graphics.Path path = new android.graphics.Path();
                        // 转换第一个点的坐标
                        float[] imageCoords = worldToImage(points.get(0), points.get(1));
                        float firstX = imageCoords[0];
                        float firstY = imageCoords[1];
                        path.moveTo(firstX, firstY);

                        // 转换其他点的坐标
                        for (int i = 2; i < points.size(); i += 2) {
                            float[] coords = worldToImage(points.get(i), points.get(i + 1));
                            float x = coords[0];
                            float y = coords[1];
                            path.lineTo(x, y);
                        }
                        path.close();
                        canvas.drawPath(path, paint);

                        // 绘制点
                        for (int i = 0; i < points.size(); i += 2) {
                            float[] coords = worldToImage(points.get(i), points.get(i + 1));
                            float x = coords[0];
                            float y = coords[1];
                            canvas.drawCircle(x, y, 5, paint);
                        }

                        // 绘制膨胀后的区域边界 (虚线)
                        float expand = area.getExpand();
                        if (expand != 0f) {
                            List<Float> expandedPoints = com.ezhan.amr.navigation.area.PolygonUtils.expandPolygon(points, expand);
                            if (expandedPoints != null && expandedPoints.size() >= 6) {
                                android.graphics.Paint expandPaint = new android.graphics.Paint();
                                expandPaint.setColor(android.graphics.Color.parseColor("#FF9800")); // 橙色
                                expandPaint.setStrokeWidth(2);
                                expandPaint.setStyle(android.graphics.Paint.Style.STROKE);
                                expandPaint.setAntiAlias(true);
                                // 设置虚线效果: 10像素实线, 5像素空白
                                expandPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10, 5}, 0));

                                android.graphics.Path expandPath = new android.graphics.Path();
                                float[] expandImageCoords = worldToImage(expandedPoints.get(0), expandedPoints.get(1));
                                expandPath.moveTo(expandImageCoords[0], expandImageCoords[1]);
                                for (int i = 2; i < expandedPoints.size(); i += 2) {
                                    float[] coords = worldToImage(expandedPoints.get(i), expandedPoints.get(i + 1));
                                    expandPath.lineTo(coords[0], coords[1]);
                                }
                                expandPath.close();
                                canvas.drawPath(expandPath, expandPaint);
                            }
                        }
                    }
                }
            }
            
            // 更新currentBitmap和ImageView
            currentBitmap = mutableBitmap;
            mapImageView.setImageBitmap(currentBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error drawing saved areas", e);
        }
    }

    private void drawPolygon() {
        try {
            if (currentBitmap == null) {
                return;
            }
            
            // 创建可绘制的Bitmap
            Bitmap mutableBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(android.graphics.Color.RED);
            paint.setStrokeWidth(3);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            
            // 直接在Bitmap的Canvas上绘制，不需要考虑ImageView的缩放和偏移
            
            // 绘制地图原点位置 (mappos)
            android.graphics.Paint originPaint = new android.graphics.Paint();
            originPaint.setColor(android.graphics.Color.GREEN);
            originPaint.setStrokeWidth(5);
            originPaint.setStyle(android.graphics.Paint.Style.FILL);
            // 原点坐标是 (0, 0) 在世界坐标系中
            float[] originImageCoords = worldToImage(0, 0);
            if (originImageCoords[0] >= 0 && originImageCoords[0] <= mutableBitmap.getWidth() && 
                originImageCoords[1] >= 0 && originImageCoords[1] <= mutableBitmap.getHeight()) {
                canvas.drawCircle(originImageCoords[0], originImageCoords[1], 10, originPaint);
                // 绘制原点标签
                android.graphics.Paint textPaint = new android.graphics.Paint();
                textPaint.setColor(android.graphics.Color.BLACK);
                textPaint.setTextSize(20);
                canvas.drawText(getString(R.string.area_origin_label), originImageCoords[0] + 15, originImageCoords[1] - 15, textPaint);
            }

            // 绘制多边形
            if (polygonPoints.size() >= 4) {
                android.graphics.Path path = new android.graphics.Path();
                // 转换第一个点的坐标
                float[] imageCoords = worldToImage(polygonPoints.get(0), polygonPoints.get(1));
                float firstX = imageCoords[0];
                float firstY = imageCoords[1];
                path.moveTo(firstX, firstY);
                
                // 转换其他点的坐标
                for (int i = 2; i < polygonPoints.size(); i += 2) {
                    float[] coords = worldToImage(polygonPoints.get(i), polygonPoints.get(i + 1));
                    float x = coords[0];
                    float y = coords[1];
                    path.lineTo(x, y);
                }
                // 不使用 path.close()，保持多边形开放
                canvas.drawPath(path, paint);
            }
            
            // 绘制点
            android.graphics.Paint textPaint = new android.graphics.Paint();
            textPaint.setColor(android.graphics.Color.BLACK);
            textPaint.setTextSize(16);
            textPaint.setAntiAlias(true);
            
            for (int i = 0; i < polygonPoints.size(); i += 2) {
                float[] coords = worldToImage(polygonPoints.get(i), polygonPoints.get(i + 1));
                float x = coords[0];
                float y = coords[1];
                canvas.drawCircle(x, y, 5, paint);
                
                // 显示坐标值
                String coordinateText = String.format("(%.2f, %.2f)", polygonPoints.get(i), polygonPoints.get(i + 1));
                canvas.drawText(coordinateText, x + 10, y - 10, textPaint);
            }
            
            // 绘制已保存的区域
            android.graphics.Paint savedAreaPaint = new android.graphics.Paint();
            savedAreaPaint.setColor(android.graphics.Color.BLUE);
            savedAreaPaint.setStrokeWidth(3);
            savedAreaPaint.setStyle(android.graphics.Paint.Style.STROKE);
            
            for (MapArea area : mapAreas) {
                // 只绘制当前地图的区域
                if (area.getMapName() != null && area.getMapName().equals(currentMapName)) {
                    List<Float> points = area.getPolygonPoints();
                    if (points != null && points.size() >= 6) {
                        android.graphics.Path path = new android.graphics.Path();
                        // 转换第一个点的坐标
                        float[] imageCoords = worldToImage(points.get(0), points.get(1));
                        float firstX = imageCoords[0];
                        float firstY = imageCoords[1];
                        path.moveTo(firstX, firstY);
                        
                        // 转换其他点的坐标
                        for (int i = 2; i < points.size(); i += 2) {
                            float[] coords = worldToImage(points.get(i), points.get(i + 1));
                            float x = coords[0];
                            float y = coords[1];
                            path.lineTo(x, y);
                        }
                        path.close();
                        canvas.drawPath(path, savedAreaPaint);
                        
                        // 绘制点
                        android.graphics.Paint savedAreaTextPaint = new android.graphics.Paint();
                        savedAreaTextPaint.setColor(android.graphics.Color.BLACK);
                        savedAreaTextPaint.setTextSize(16);
                        savedAreaTextPaint.setAntiAlias(true);
                        
                        for (int i = 0; i < points.size(); i += 2) {
                            float[] coords = worldToImage(points.get(i), points.get(i + 1));
                            float x = coords[0];
                            float y = coords[1];
                            canvas.drawCircle(x, y, 5, savedAreaPaint);
                            
                            // 显示坐标值
                            String coordinateText = String.format("(%.2f, %.2f)", points.get(i), points.get(i + 1));
                            canvas.drawText(coordinateText, x + 10, y - 10, savedAreaTextPaint);
                        }
                    }
                }
            }
            
            // 更新ImageView和currentBitmap
            currentBitmap = mutableBitmap;
            mapImageView.setImageBitmap(currentBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error drawing polygon", e);
        }
    }

    private void updateAreaList(ListView listView) {
        // 过滤出当前地图的区域
        List<MapArea> currentMapAreas = new ArrayList<>();
        for (MapArea area : mapAreas) {
            if (area.getMapName() != null && area.getMapName().equals(currentMapName)) {
                currentMapAreas.add(area);
            }
        }
        AreaAdapter adapter = new AreaAdapter(getContext(), currentMapAreas);
        listView.setAdapter(adapter);
    }

    private class AreaAdapter extends ArrayAdapter<MapArea> {
        public AreaAdapter(Context context, List<MapArea> areas) {
            super(context, 0, areas);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_map_area_detail, parent, false);
            }

            MapArea area = getItem(position);
            if (area != null) {
                TextView tvAreaName = convertView.findViewById(R.id.tvAreaName);
                TextView tvAreaInfo = convertView.findViewById(R.id.tvAreaInfo);
                Button btnEdit = convertView.findViewById(R.id.btnEdit);
                Button btnDelete = convertView.findViewById(R.id.btnDelete);

                tvAreaName.setText(area.getName());
                tvAreaInfo.setText(getString(R.string.area_list_info_format, getLocalizedAreaType(area.getType()), formatLoraByteForDisplay(area.getChannel()), formatLoraByteForDisplay(area.getAddress())));

                btnEdit.setOnClickListener(v -> showAreaDetailDialog(area, position));
                btnDelete.setOnClickListener(v -> {
                    // 显示确认对话框
                    new AlertDialog.Builder(getContext())
                            .setTitle(R.string.confirm_delete_title)
                            .setMessage(getString(R.string.confirm_delete_area_message, area.getName()))
                            .setPositiveButton(R.string.delete, (dialog, which) -> {
                                // 从原始列表中删除
                                mapAreas.remove(area);
                                // 从适配器中删除
                                remove(area);
                                notifyDataSetChanged();
                                // 保存地图区域数据
                                saveMapAreas();
                                // 重新绘制地图
                                drawSavedAreas();
                                Toast.makeText(getContext(), getString(R.string.area_deleted, area.getName()), Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });
            }

            return convertView;
        }
    }

    private void showAreaDetailDialog(MapArea area, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_area_detail, null);
        builder.setView(dialogView);

        EditText etAreaName = dialogView.findViewById(R.id.etAreaName);
        Spinner spAreaType = dialogView.findViewById(R.id.spAreaType);
        EditText etChannel = dialogView.findViewById(R.id.etChannel);
        EditText etAddress = dialogView.findViewById(R.id.etAddress);
        EditText etExpand = dialogView.findViewById(R.id.etExpand);
        Button btnSave = dialogView.findViewById(R.id.btnSave);

        // 交管区域: 显示提示 (其他机器人在全局列表配置)
        View llTrafficConfig = dialogView.findViewById(R.id.llTrafficConfig);

        // 设置类型选项 (使用本地化显示标签, 映射到数据值)
        String[] displayLabels = getResources().getStringArray(R.array.area_type_display_values);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, displayLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAreaType.setAdapter(adapter);

        etAreaName.setText(area.getName());
        // 设置当前类型选中 (通过数据值匹配索引)
        String currentType = area.getType();
        for (int i = 0; i < AREA_TYPE_VALUES.length; i++) {
            if (AREA_TYPE_VALUES[i].equals(currentType)) {
                spAreaType.setSelection(i);
                break;
            }
        }
        etChannel.setText(formatLoraByteForDisplay(area.getChannel()));
        etAddress.setText(formatLoraByteForDisplay(area.getAddress()));
        setupLoraByteInput(etChannel);
        setupLoraByteInput(etAddress);
        // 膨胀参数: 保留两位小数, 0 显示为 "0.00" 便于用户编辑
        etExpand.setText(String.format(java.util.Locale.US, "%.2f", area.getExpand()));

        // Spinner 选择变化时控制交管提示区显示
        spAreaType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                boolean isTraffic = pos < AREA_TYPE_VALUES.length && AREA_TYPE_VALUES[2].equals(AREA_TYPE_VALUES[pos]);
                llTrafficConfig.setVisibility(isTraffic ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        builder.setTitle(getString(R.string.maparea_area_detail));
        builder.setNegativeButton(getString(R.string.cancel), null);
        final AlertDialog dialog = builder.create();

        btnSave.setOnClickListener(v -> {
            area.setName(etAreaName.getText().toString());
            // Store the data value (Chinese identifier), not the display label
            int selectedTypeIndex = spAreaType.getSelectedItemPosition();
            area.setType(selectedTypeIndex < AREA_TYPE_VALUES.length ? AREA_TYPE_VALUES[selectedTypeIndex] : AREA_TYPE_VALUES[0]);

            Integer channelValue = parseLoraByteValue(etChannel.getText().toString());
            if (channelValue == null) {
                Toast.makeText(getContext(), getString(R.string.area_lora_byte_range_error), Toast.LENGTH_SHORT).show();
                etChannel.requestFocus();
                return;
            }
            Integer addressValue = parseLoraByteValue(etAddress.getText().toString());
            if (addressValue == null) {
                Toast.makeText(getContext(), getString(R.string.error_address_range), Toast.LENGTH_SHORT).show();
                etAddress.requestFocus();
                return;
            }
            area.setChannel(formatLoraByteForStorage(channelValue));
            area.setAddress(formatLoraByteForStorage(addressValue));
            // 解析膨胀参数 (支持负数和小数, 非法输入视为 0)
            float expandValue = 0f;
            String expandStr = etExpand.getText().toString().trim();
            if (!expandStr.isEmpty() && !expandStr.equals("-") && !expandStr.equals(".")) {
                try {
                    expandValue = Float.parseFloat(expandStr);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "膨胀参数格式错误, 使用 0: " + expandStr);
                }
            }
            area.setExpand(expandValue);

            Toast.makeText(getContext(), getString(R.string.maparea_save_success), Toast.LENGTH_SHORT).show();
            saveMapAreas();
            drawSavedAreas();
            if (currentAreaListView != null) {
                updateAreaList(currentAreaListView);
            }
            // 通知触发管理器重新加载区域, 使膨胀参数立即生效
            com.ezhan.amr.navigation.area.MapAreaTriggerManager trigger =
                    MyApplication.getInstance().getMapAreaTriggerManager();
            if (trigger != null) {
                trigger.reloadAreas();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    /** 刷新其他机器人列表 UI */
    private void refreshRobotList(ScrollView scrollView, LinearLayout llRobotList, List<OtherRobot> robots) {
        llRobotList.removeAllViews();
        for (int i = 0; i < robots.size(); i++) {
            final int index = i;
            OtherRobot r = robots.get(i);
            View row = LayoutInflater.from(getContext()).inflate(R.layout.item_other_robot, llRobotList, false);

            EditText etRobotId = row.findViewById(R.id.etRobotId);
            EditText etRobotChannel = row.findViewById(R.id.etRobotChannel);
            EditText etRobotAddress = row.findViewById(R.id.etRobotAddress);
            Button btnRemoveRobot = row.findViewById(R.id.btnRemoveRobot);

            etRobotId.setText(r.getRobotId() > 0 ? String.valueOf(r.getRobotId()) : "");
            etRobotChannel.setText(formatLoraByteForDisplay(r.getChannel()));
            etRobotAddress.setText(formatLoraHexAddressForDisplay(r.getAddress()));
            setupLoraByteInput(etRobotChannel);
            setupLoraHexAddressInput(etRobotAddress);

            View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
                if (hasFocus) {
                    v.postDelayed(() -> {
                        scrollFocusedFieldIntoView(scrollView, v);
                        InputMethodManager imm = (InputMethodManager) getContext()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }, 100);
                }
            };
            etRobotId.setOnFocusChangeListener(focusListener);
            etRobotChannel.setOnFocusChangeListener(focusListener);
            etRobotAddress.setOnFocusChangeListener(focusListener);

            btnRemoveRobot.setOnClickListener(v -> {
                collectRobotsFromList(llRobotList, robots);
                if (index < robots.size()) {
                    robots.remove(index);
                }
                refreshRobotList(scrollView, llRobotList, robots);
            });

            llRobotList.addView(row);
        }
    }

    /** 将输入框滚动到可见区域，并预留底部按钮空间 */
    private void scrollFocusedFieldIntoView(@Nullable ScrollView scrollView, View focusedView) {
        if (scrollView == null || focusedView == null || scrollView.getChildCount() == 0) {
            return;
        }
        View content = scrollView.getChildAt(0);
        int offset = 0;
        View current = focusedView;
        while (current != null && current != content) {
            offset += current.getTop();
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }
        int extraSpaceForButtons = (int) (120 * getResources().getDisplayMetrics().density);
        int scrollTarget = offset + focusedView.getHeight() + extraSpaceForButtons - scrollView.getHeight();
        scrollView.smoothScrollTo(0, Math.max(0, scrollTarget));
    }

    /** 从 UI 的 EditText 收集最新值到 robots 列表 */
    private void collectRobotsFromList(LinearLayout llRobotList, List<OtherRobot> robots) {
        int childCount = llRobotList.getChildCount();
        for (int i = 0; i < childCount && i < robots.size(); i++) {
            View row = llRobotList.getChildAt(i);
            EditText etRobotId = row.findViewById(R.id.etRobotId);
            EditText etRobotChannel = row.findViewById(R.id.etRobotChannel);
            EditText etRobotAddress = row.findViewById(R.id.etRobotAddress);

            OtherRobot r = robots.get(i);
            String idStr = etRobotId.getText().toString().trim();
            try {
                r.setRobotId(idStr.isEmpty() ? 0 : Integer.parseInt(idStr));
            } catch (NumberFormatException e) {
                r.setRobotId(0);
            }
            r.setChannel(etRobotChannel.getText().toString().trim());
            r.setAddress(etRobotAddress.getText().toString().trim());
        }
    }

    private void setupLoraByteInput(EditText editText) {
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            String result = dest.subSequence(0, dstart).toString()
                    + source.subSequence(start, end)
                    + dest.subSequence(dend, dest.length());
            if (result.isEmpty()) {
                return null;
            }
            if (!result.matches("\\d{1,3}")) {
                return "";
            }
            try {
                if (Integer.parseInt(result) > MAX_LORA_BYTE) {
                    return "";
                }
            } catch (NumberFormatException e) {
                return "";
            }
            return null;
        }});
    }

    private void setupLoraHexAddressInput(EditText editText) {
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return "";
                }
            }
            String result = dest.subSequence(0, dstart).toString()
                    + source.subSequence(start, end)
                    + dest.subSequence(dend, dest.length());
            if (result.length() > 2) {
                return "";
            }
            return null;
        }});
    }

    @Nullable
    private Integer parseLoraByteValue(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.matches("[0-9A-Fa-f]{1,2}")) {
            try {
                int hexValue = Integer.parseInt(trimmed, 16);
                if (hexValue >= MIN_LORA_BYTE && hexValue <= MAX_LORA_BYTE) {
                    return hexValue;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            int decimalValue = Integer.parseInt(trimmed);
            if (decimalValue >= MIN_LORA_BYTE && decimalValue <= MAX_LORA_BYTE) {
                return decimalValue;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    @Nullable
    private Integer parseLoraHexAddressValue(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(trimmed, 16);
            if (value >= MIN_LORA_BYTE && value <= MAX_LORA_BYTE) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private String formatLoraByteForDisplay(@Nullable String stored) {
        Integer value = parseLoraByteValue(stored);
        return value != null ? String.valueOf(value) : (stored != null ? stored : "");
    }

    private String formatLoraHexAddressForDisplay(@Nullable String stored) {
        Integer value = parseLoraHexAddressValue(stored);
        return value != null ? String.format(Locale.US, "%02X", value) : (stored != null ? stored : "");
    }

    private String formatLoraByteForStorage(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private boolean validateTrafficRobots(List<OtherRobot> robots) {
        for (OtherRobot robot : robots) {
            String channelText = robot.getChannel();
            String addressText = robot.getAddress();
            boolean hasAnyValue = robot.getRobotId() != 0
                    || (channelText != null && !channelText.trim().isEmpty())
                    || (addressText != null && !addressText.trim().isEmpty());
            if (!hasAnyValue) {
                continue;
            }
            if (robot.getRobotId() <= 0) {
                Toast.makeText(getContext(), getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
                return false;
            }
            Integer channelValue = parseLoraByteValue(channelText);
            if (channelValue == null) {
                Toast.makeText(getContext(), getString(R.string.area_lora_byte_range_error), Toast.LENGTH_SHORT).show();
                return false;
            }
            Integer addressValue = parseLoraHexAddressValue(addressText);
            if (addressValue == null) {
                Toast.makeText(getContext(), getString(R.string.error_address_range), Toast.LENGTH_SHORT).show();
                return false;
            }
            robot.setChannel(String.valueOf(channelValue));
            robot.setAddress(String.format(Locale.US, "%02X", addressValue));
        }
        return true;
    }



    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map_area, container, false);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnBackPressedListener) {
            onBackPressedListener = (OnBackPressedListener) context;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        onBackPressedListener = null;
    }

    public boolean handleBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    // 判断点是否在多边形内（点-in-多边形算法）
    private boolean pointInPolygon(double x, double y, List<Float> polygonPoints) {
        if (polygonPoints == null || polygonPoints.size() < 6) {
            return false;
        }

        boolean inside = false;
        int n = polygonPoints.size() / 2;
        double x1, y1, x2, y2;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            x1 = polygonPoints.get(i * 2);
            y1 = polygonPoints.get(i * 2 + 1);
            x2 = polygonPoints.get(j * 2);
            y2 = polygonPoints.get(j * 2 + 1);

            if (((y1 > y) != (y2 > y)) && (x < (x2 - x1) * (y - y1) / (y2 - y1) + x1)) {
                inside = !inside;
            }
        }

        return inside;
    }

    // 检查机器人当前所在区域
    private void checkRobotCurrentArea() {
        // 获取机器人当前位置
        SharedViewModel sharedViewModel = MyApplication.getInstance().getSharedViewModel();
        Position currentPosition = sharedViewModel.getCurrentPosition();

        if (currentPosition == null) {
            Toast.makeText(getContext(), getString(R.string.maparea_cannot_get_robot_position), Toast.LENGTH_SHORT).show();
            return;
        }

        double robotX = currentPosition.getPosX();
        double robotY = currentPosition.getPosY();
        String mapName = currentPosition.getMapName();
        String floor = currentPosition.getFloor();
        
        Log.d(TAG, "机器人当前位置: X=" + robotX + ", Y=" + robotY + ", 地图=" + mapName + ", 楼层=" + floor);

        // 遍历所有区域，检查机器人是否在其中
        boolean found = false;
        Log.d(TAG, "开始检查区域，共有 " + mapAreas.size() + " 个区域");
        for (MapArea area : mapAreas) {
            String areaMapName = area.getMapName();
            Log.d(TAG, "检查区域: " + area.getName() + "，地图: " + areaMapName);
            Log.d(TAG, "比较地图名称: 机器人地图='" + mapName + "'，区域地图='" + areaMapName + "'，是否匹配: " + (areaMapName != null && areaMapName.equals(mapName)));
            
            if (areaMapName != null && areaMapName.equals(mapName)) {
                List<Float> polygonPoints = area.getPolygonPoints();
                boolean inArea = pointInPolygon(robotX, robotY, polygonPoints);
                Log.d(TAG, "机器人是否在区域内: " + inArea);
                
                if (inArea) {
                    String message = getString(R.string.area_robot_in_area, mapName, area.getName(), getLocalizedAreaType(area.getType()));
                    Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                    Log.d(TAG, message);
                    
                    // 如果是信息区域，发送Lora信号
                    if (area.getType() != null && area.getType().equals("信息区域")) {
                        Log.d(TAG, "检测到信息区域，准备发送Lora信号");
                        sendLoraSignalForInfoArea(area);
                        try {
                            Thread.sleep(100); // 间隔100ms
                        } catch (InterruptedException e) {
                            Log.e(TAG, "睡眠中断", e);
                        }
                    }
                    
                    // 如果是自动门区域，发送开门信号
                    if (area.getType() != null && area.getType().equals("门控区域")) {
                        Log.d(TAG, "检测到自动门区域，准备发送开门信号");
                        sendDoorOpenSignal(area);
                        try {
                            Thread.sleep(100); // 间隔100ms
                        } catch (InterruptedException e) {
                            Log.e(TAG, "睡眠中断", e);
                        }
                    }
                    
                    found = true;
                }
            }
        }

        if (!found) {
            String message = getString(R.string.area_robot_not_in_any, mapName);
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            Log.d(TAG, message);
        }
    }

    // 发送开门信号到自动门区域
    private void sendDoorOpenSignal(MapArea area) {
        try {
            // 检查发送间隔，确保同一个区域的发送间隔至少为500ms
            String areaKey = area.getName() + "_" + area.getMapName();
            Long lastSendTime = lastSendTimeMap.get(areaKey);
            long currentTime = System.currentTimeMillis();
            
            if (lastSendTime != null && (currentTime - lastSendTime) < 500) {
                Log.d(TAG, "发送间隔不足500ms，跳过发送");
                return;
            }
            
            // 获取区域的地址和信道
            String addressStr = area.getAddress();
            String channelStr = area.getChannel();
            
            if (addressStr == null || addressStr.isEmpty() || channelStr == null || channelStr.isEmpty()) {
                Log.w(TAG, "区域地址或信道未配置，无法发送开门信号");
                return;
            }
            
            // 转换地址和信道为十六进制字符串
            String hexAddress = addressStr;
            String hexChannel = channelStr;
            
            // 确保地址和信道都是两位十六进制
            if (hexAddress.length() == 1) {
                hexAddress = "0" + hexAddress;
            }
            if (hexChannel.length() == 1) {
                hexChannel = "0" + hexChannel;
            }
            
            // 构建开门信号：00 地址 信道 05 50 机器人地址 机器人信道 21 01 00 00 校验和
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            int robotAddress = basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0;
            int robotChannel = basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0;
            String hexRobotAddress = String.format("%02X", robotAddress);
            String hexRobotChannel = String.format("%02X", robotChannel);
            
            String mainDoorMessage = "0550" + hexRobotAddress + hexRobotChannel + "21010000";
            String doorOpenMessage = "00" + hexAddress + hexChannel + mainDoorMessage;
            
            // 计算校验和
            doorOpenMessage += calculateChecksum(mainDoorMessage);
            
            Log.d(TAG, "发送开门信号: " + doorOpenMessage);
            
            // 获取LoraCommunicator实例，使用ttys8串口
            LoraCommunicator loraCommunicator = MyApplication.getInstance().getLoraCommunicator();
            
            // 发送开门信号
            loraCommunicator.sendMessage(doorOpenMessage, false);
            
            // 更新最后发送时间
            lastSendTimeMap.put(areaKey, currentTime);
            
            Log.d(TAG, "开门信号发送成功");
            
        } catch (Exception e) {
            Log.e(TAG, "发送开门信号失败: " + e.getMessage(), e);
        }
    }

    // 计算校验和
    private String calculateChecksum(String hexMessage) {
        int checksum = 0;
        for (int i = 0; i < hexMessage.length(); i += 2) {
            String byteStr = hexMessage.substring(i, i + 2);
            checksum ^= Integer.parseInt(byteStr, 16);
        }
        return String.format("%02X", checksum);
    }

    // 发送Lora信号到信息区域
    private void sendLoraSignalForInfoArea(MapArea area) {
        try {
            // 获取LoraCommunicator实例
            LoraCommunicator loraCommunicator = MyApplication.getInstance().getLoraCommunicator();

            // 获取区域的真实地址和信道
            String addressStr = area.getAddress();
            String channelStr = area.getChannel();

            if (addressStr == null || addressStr.isEmpty() || channelStr == null || channelStr.isEmpty()) {
                Log.w(TAG, "区域地址或信道未配置，无法发送Lora信号");
                return;
            }

            // 确保地址和信道都是两位十六进制
            String address = addressStr;
            String channel = channelStr;
            if (address.length() == 1) {
                address = "0" + address;
            }
            if (channel.length() == 1) {
                channel = "0" + channel;
            }

            // 获取机器人LoRa地址和信道
            BasicViewModel basicViewModel = MyApplication.getInstance().getBasicViewModel();
            int robotAddress = basicViewModel.getLoraAddress().getValue() != null ? basicViewModel.getLoraAddress().getValue() : 0;
            int robotChannel = basicViewModel.getLoraChannel().getValue() != null ? basicViewModel.getLoraChannel().getValue() : 0;
            String hexRobotAddress = String.format("%02X", robotAddress);
            String hexRobotChannel = String.format("%02X", robotChannel);

            // 功能码0x31=控制播放音频, 参数0x01=开始播放
            String mainInfoMessage = "0550" + hexRobotAddress + hexRobotChannel + "31010000";
            String loraMessage = "00" + address + channel + mainInfoMessage + calculateChecksum(mainInfoMessage);

            Log.d(TAG, "发送Lora信号: " + loraMessage);

            // 发送Lora信号
            loraCommunicator.sendMessage(loraMessage, false);

            Log.d(TAG, "Lora信号发送成功");

        } catch (Exception e) {
            Log.e(TAG, "发送Lora信号失败: " + e.getMessage(), e);
        }
    }

    // 图像坐标 → 世界坐标
    private float[] imageToWorld(float pixelX, float pixelY) {
        if (currentBitmap == null || currentBitmap.getHeight() <= 0) {
            return new float[]{0, 0};
        }
        float worldX = currentMapOriginX + (pixelX * currentResolution);
        float worldY = currentMapOriginY + ((currentBitmap.getHeight() - pixelY) * currentResolution);
        return new float[]{worldX, worldY};
    }

    // 世界坐标 → 图像坐标
    private float[] worldToImage(float worldX, float worldY) {
        if (currentBitmap == null || currentBitmap.getHeight() <= 0 || currentResolution <= 0) {
            return new float[]{0, 0};
        }
        float pixelX = (worldX - currentMapOriginX) / currentResolution;
        float pixelY = currentBitmap.getHeight() - ((worldY - currentMapOriginY) / currentResolution);
        return new float[]{pixelX, pixelY};
    }

    /**
     * 将区域类型数据值(中文标识符)映射为本地化显示文本。
     * 数据值 "门控区域"/"信息区域"/"交管区域" 存储在 SharedPreferences 中，
     * 此方法仅用于 UI 显示，不改变存储值。
     */
    private String getLocalizedAreaType(String dataValue) {
        if (dataValue == null) return "";
        for (int i = 0; i < AREA_TYPE_VALUES.length; i++) {
            if (AREA_TYPE_VALUES[i].equals(dataValue)) {
                String[] displayLabels = getResources().getStringArray(R.array.area_type_display_values);
                if (i < displayLabels.length) return displayLabels[i];
            }
        }
        return dataValue; // fallback
    }
}
