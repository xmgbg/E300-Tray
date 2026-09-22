package com.ezhan.amr.ui.fragments;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.chassis.RobotWebSocketClient;
import com.ezhan.amr.viewmodels.AreaViewModel;
import com.ezhan.amr.viewmodels.RobotViewModel;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.graphics.BitmapFactory;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import java.util.Locale;

public class AvoidanceSettingsFragment extends Fragment
        implements RobotWebSocketClient.WebSocketListener {

    private static final String TAG = "AvoidanceSettings";
    private static final float MIN_SCALE_DELTA = 0.01f;

    // UI components
    private TabLayout tabLayout;
    private FrameLayout areaImageContainer;
    private PhotoView areaImageView;
    private MaterialButton btnAddArea;
    private MaterialButton btnAddRobot;
    private RecyclerView rvAreas;
    private RecyclerView rvRobots;
    private LinearLayout robotConfigLayout;
    private LinearLayout drawingControlsLayout;
    private MaterialButton btnSaveDrawing;
    private MaterialButton btnCancelDrawing;

    // Data related
    private RobotViewModel robotViewModel;
    private AreaViewModel areaViewModel;
    private AreaAdapter areaAdapter;
    private RobotAdapter robotAdapter;
    private RobotWebSocketClient webSocketClient;
    private final String WS_URL = "ws://192.0.2.10:6060";
    private List<String> mapList = new ArrayList<>();
    private String currentMapName = "";
    private String lastDisplayedMapName = "";

    // Map drawing related
    private Bitmap originalMapBitmap;
    private Bitmap drawingBitmap;
    private Canvas drawingCanvas;
    private List<PointF> currentPoints = new ArrayList<>();
    private Paint regionPaint;
    private Paint coordinatePaint;
    private float mapWidth = 0;
    private float mapHeight = 0;
    private float mapResolution = 0.05f;
    private float[] mapOrigin = new float[]{-30.8f, -10f};
    private boolean isDrawingMode = false;
    private String currentAreaType = "";
    private String currentAreaId = "";

    // Map to store all drawn areas by map name
    private Map<String, List<DrawnArea>> mapAreas = new HashMap<>();
    // Current temporary areas being drawn
    private List<DrawnArea> tempDrawnAreas = new ArrayList<>();
    private final Matrix currentMatrix = new Matrix();

    private float currentScale = 1.0f;
    private float currentCenterX = 0f;
    private float currentCenterY = 0f;
    private boolean isScaleRestored = false;
    private boolean isDrawingInProgress = false;
    private boolean isRedrawing = false;
    private boolean isFirstMapLoad = true;
    private boolean isRestoringState = false;

    // 视图状态缓存
    private float lastScale = 1.0f;
    private float lastCenterX = 0f;
    private float lastCenterY = 0f;

    private boolean isViewInitialized = false;
    private boolean isFirstDraw = true;

    private List<AreaViewModel.AreaConfig> lastDrawnAreas = new ArrayList<>();

    private Bitmap tempDrawingBitmap;
    private Canvas tempDrawingCanvas;

    private float preDrawScale = 1.0f;
    private float preDrawCenterX = 0f;
    private float preDrawCenterY = 0f;

    private Bitmap drawingOverlayBitmap; // 绘制覆盖层
    private Canvas drawingOverlayCanvas;
    private ImageView drawingOverlayView; // 覆盖层视图
    private Matrix overlayMatrix = new Matrix();
    private RectF currentViewRect = new RectF();


    // Drawn area data class
    private static class DrawnArea {
        String id;
        String type;
        List<PointF> points;
        String worldCoordinates;

        DrawnArea(String id, String type, List<PointF> points, String worldCoordinates) {
            this.id = id;
            this.type = type;
            this.points = new ArrayList<>(points);
            this.worldCoordinates = worldCoordinates;
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_avoidance_settings, container, false);

        // Initialize drawing control buttons
        drawingControlsLayout = view.findViewById(R.id.drawingControlsLayout);
        btnSaveDrawing = view.findViewById(R.id.btnSaveDrawing);
        btnCancelDrawing = view.findViewById(R.id.btnCancelDrawing);

        // Hide drawing controls by default
        drawingControlsLayout.setVisibility(View.GONE);

        return view;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize drawing tools
        regionPaint = new Paint();
        regionPaint.setColor(Color.RED);
        regionPaint.setStyle(Paint.Style.STROKE);
        regionPaint.setStrokeWidth(5f);
        regionPaint.setAntiAlias(true);

        coordinatePaint = new Paint();
        coordinatePaint.setColor(Color.BLUE);
        coordinatePaint.setTextSize(30f);
        coordinatePaint.setAntiAlias(true);

        robotViewModel = new ViewModelProvider(requireActivity()).get(RobotViewModel.class);
        areaViewModel = new ViewModelProvider(requireActivity()).get(AreaViewModel.class);
        initViews(view);
        setupTabs();
        setupRecyclerViews();
        setupListeners();
        observeData();

        areaViewModel.getAreas().observe(getViewLifecycleOwner(), areas -> {
            areaAdapter.updateData(areas);
            // 检查区域数据是否实际变化
            if (!areAreasEqual(lastDrawnAreas, areas)) {
                redrawAllAreas();
                lastDrawnAreas = new ArrayList<>(areas);
            }
        });

        // 修改缩放监听器逻辑
        areaImageView.setOnMatrixChangeListener(rect -> {
            if (isRestoringState) {
                isRestoringState = false;
                return;
            }

            if (rect != null) {
                float newScale = areaImageView.getScale();

                // 忽略微小变化
                if (Math.abs(newScale - currentScale) > MIN_SCALE_DELTA) {
                    currentScale = newScale;
                    currentCenterX = rect.centerX();
                    currentCenterY = rect.centerY();

                    // 保存最后有效的视图状态（仅当不是重绘操作时）
                    if (!isRedrawing && !isDrawingInProgress) {
                        lastScale = currentScale;
                        lastCenterX = currentCenterX;
                        lastCenterY = currentCenterY;
                    }
                }
            }
        });
        drawingOverlayView = new ImageView(getContext());
        drawingOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        drawingOverlayView.setScaleType(ImageView.ScaleType.MATRIX);
        areaImageContainer.addView(drawingOverlayView);

        // 同步缩放和位置
        areaImageView.setOnMatrixChangeListener(rect -> {
            if (rect != null) {
                overlayMatrix.set(areaImageView.getImageMatrix());
                drawingOverlayView.setImageMatrix(overlayMatrix);
                currentViewRect.set(rect);

                // 保存缩放状态
                currentScale = areaImageView.getScale();
                currentCenterX = rect.centerX();
                currentCenterY = rect.centerY();
            }
        });

    }

    private boolean areAreasEqual(List<AreaViewModel.AreaConfig> list1,
                                  List<AreaViewModel.AreaConfig> list2) {
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;

        for (int i = 0; i < list1.size(); i++) {
            AreaViewModel.AreaConfig a1 = list1.get(i);
            AreaViewModel.AreaConfig a2 = list2.get(i);

            if (!a1.getId().equals(a2.getId()) ||
                    !a1.getType().equals(a2.getType()) ||
                    !a1.getRange().equals(a2.getRange())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        // 保存当前缩放状态
        currentScale = areaImageView.getScale();
        RectF rect = areaImageView.getDisplayRect();
        if (rect != null) {
            currentCenterX = rect.centerX();
            currentCenterY = rect.centerY();
        }
        disconnectWebSocket();
    }

    @Override
    public void onResume() {
        super.onResume();
        connectWebSocket();

        // 延迟恢复缩放状态，确保视图已布局完成
        areaImageView.postDelayed(() -> {
            if (lastScale > 1.0f) {
                // 使用平滑动画恢复
                areaImageView.setScale(lastScale, lastCenterX, lastCenterY, true);
            }
            isScaleRestored = true;
        }, 300);
    }

    @Override
    public void onDestroyView() {
        disconnectWebSocket();
        super.onDestroyView();
        // 释放Bitmap资源
        if (drawingBitmap != null && !drawingBitmap.isRecycled()) {
            drawingBitmap.recycle();
            drawingBitmap = null;
        }
        if (originalMapBitmap != null && !originalMapBitmap.isRecycled()) {
            originalMapBitmap.recycle();
            originalMapBitmap = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectWebSocket();
    }

    private void connectWebSocket() {
        try {
            disconnectWebSocket();

            URI uri = new URI(WS_URL);
            webSocketClient = new RobotWebSocketClient(uri, this);
            webSocketClient.connect();
            Log.d(TAG, "Attempting to connect to WebSocket at: " + WS_URL);

        } catch (Exception e) {
            e.printStackTrace();
            showToast(getString(R.string.avoidance_ws_connect_failed, e.getMessage()));
            Log.e(TAG, "WebSocket connection failed", e);
        }
    }

    private void disconnectWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
            Log.d(TAG, "WebSocket disconnected");
        }
    }

    private void requestMapData(String mapName) {
        try {
            currentMapName = mapName;
            JSONObject request = new JSONObject();
            request.put("cmd", "getMapData");
            request.put("mapName", mapName);

            if (webSocketClient != null && webSocketClient.isOpen()) {
                webSocketClient.send(request.toString());
                Log.d(TAG, "Sending getMapData request for map: " + mapName);
            } else {
                showToast(getString(R.string.avoidance_ws_not_connected_retrying));
                connectWebSocket();
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to create getMapData request", e);
        }
    }

    @Override
    public void onMessageReceived(String message) {
        Log.d(TAG, "Raw message received: " + message);
        try {
            JSONObject jsonData = new JSONObject(message);

            if (jsonData.has("cmd")) {
                String cmd = jsonData.getString("cmd");

                if (cmd.equals("getMapDataRedis_result")) {
                    if (jsonData.getInt("code") == 0 && jsonData.has("data")) {
                        JSONObject dataObj = jsonData.getJSONObject("data");

                        if (dataObj.has("map") && !dataObj.isNull("map")) {
                            String base64Image = dataObj.getString("map");
                            String pureBase64 = extractPureBase64(base64Image);
                            displayMapImage(pureBase64);

                            // Store map metadata
                            if (dataObj.has("width")) mapWidth = (float) dataObj.getDouble("width");
                            if (dataObj.has("height")) mapHeight = (float) dataObj.getDouble("height");
                            if (dataObj.has("resolution")) mapResolution = (float) dataObj.getDouble("resolution");
                            if (dataObj.has("mappos")) {
                                JSONArray pos = dataObj.getJSONArray("mappos");
                                mapOrigin[0] = (float) pos.getDouble(0);
                                mapOrigin[1] = (float) pos.getDouble(1);
                            }
                        }
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            showToast(getString(R.string.avoidance_data_parse_error));
        }
    }

    private void displayMapImage(String pureBase64) {
        new Thread(() -> {
            try {
                if (pureBase64 == null || pureBase64.isEmpty()) {
                    throw new IllegalArgumentException("Base64数据为空");
                }

                byte[] imageBytes = Base64.decode(pureBase64, Base64.DEFAULT);
                final Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (bitmap == null) {
                    throw new RuntimeException("Bitmap解码失败");
                }

                originalMapBitmap = bitmap;
                mapWidth = bitmap.getWidth();
                mapHeight = bitmap.getHeight();

                // Create drawable bitmap
                drawingBitmap = originalMapBitmap.copy(Bitmap.Config.ARGB_8888, true);
                drawingCanvas = new Canvas(drawingBitmap);

                runOnUiThreadIfAttached(() -> {
                    if (areaImageView == null || drawingBitmap == null) {
                        return;
                    }
                    if (isFirstMapLoad) {
                        areaImageView.setImageBitmap(drawingBitmap);
                        isFirstMapLoad = false;
                    } else {
                        // 非首次加载时恢复上次视图状态
                        areaImageView.setScale(lastScale, lastCenterX, lastCenterY, false);
                        areaImageView.setImageBitmap(drawingBitmap);
                    }

                    setupMapTouchListener();

                    // Check if map has changed
                    if (!currentMapName.equals(lastDisplayedMapName)) {
                        tempDrawnAreas.clear();
                        lastDisplayedMapName = currentMapName;
                    }

                    // Initialize areas list for this map if not exists
                    if (!mapAreas.containsKey(currentMapName)) {
                        mapAreas.put(currentMapName, new ArrayList<>());
                    }

                    // Redraw all areas for current map
                    redrawAllAreas();
                });

            } catch (Exception e) {
                Log.e(TAG, "Image processing error", e);
                showToast(getString(R.string.avoidance_image_process_error, e.getMessage()));
            }
        }).start();
    }

    private void setupMapTouchListener() {
        areaImageView.setOnViewTapListener((view, x, y) -> {
            if (isDrawingMode && currentViewRect != null) {
                // 精确坐标转换
                float[] point = new float[]{x, y};
                Matrix inverse = new Matrix();
                areaImageView.getImageMatrix().invert(inverse);
                inverse.mapPoints(point);

                float imgX = point[0];
                float imgY = point[1];

                // 确保坐标在图像范围内
                imgX = Math.max(0, Math.min(imgX, originalMapBitmap.getWidth()));
                imgY = Math.max(0, Math.min(imgY, originalMapBitmap.getHeight()));

                PointF newPoint = new PointF(imgX, imgY);
                currentPoints.add(newPoint);

                // 初始化覆盖层
                if (drawingOverlayBitmap == null && originalMapBitmap != null) {
                    initDrawingOverlay();
                }

                // 增量绘制
                if (currentPoints.size() > 1) {
                    PointF prevPoint = currentPoints.get(currentPoints.size() - 2);
                    drawingOverlayCanvas.drawLine(
                            prevPoint.x, prevPoint.y,
                            newPoint.x, newPoint.y,
                            regionPaint);
                }
                drawingOverlayCanvas.drawCircle(newPoint.x, newPoint.y, 10, regionPaint);

                drawingOverlayView.invalidate();
            }
        });
    }

    private void initDrawingOverlay() {
        drawingOverlayBitmap = Bitmap.createBitmap(
                originalMapBitmap.getWidth(),
                originalMapBitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
        drawingOverlayCanvas = new Canvas(drawingOverlayBitmap);
        drawingOverlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        drawingOverlayView.setImageBitmap(drawingOverlayBitmap);

        // 同步初始矩阵
        drawingOverlayView.setImageMatrix(areaImageView.getImageMatrix());
    }


    private void redrawSavedAreasOnCanvas(Canvas canvas) {
        List<AreaViewModel.AreaConfig> areas = areaViewModel.getAreas().getValue();
        if (areas != null) {
            for (AreaViewModel.AreaConfig area : areas) {
                List<PointF> pixelPoints = convertWorldToPixel(area.getRange());
                drawSingleAreaOnCanvas(canvas, area.getId(), pixelPoints);
            }
        }
    }

    private void updateImageViewWithoutReset(Bitmap bitmap) {
        // 保存当前缩放状态
        float currentScale = areaImageView.getScale();
        RectF displayRect = areaImageView.getDisplayRect();
        float centerX = displayRect != null ? displayRect.centerX() : 0;
        float centerY = displayRect != null ? displayRect.centerY() : 0;

        // 设置新图像
        areaImageView.setImageBitmap(bitmap);

        // 恢复缩放状态
        if (currentScale > 1.0f) {
            areaImageView.post(() -> {
                areaImageView.setScale(currentScale, centerX, centerY, true);
            });
        }
    }

    // 新增方法：在指定画布上绘制单个区域
    private void drawSingleAreaOnCanvas(Canvas canvas, String areaId, List<PointF> points) {
        if (points.size() < 3) return;

        // 绘制区域边界
        Path path = new Path();
        path.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).x, points.get(i).y);
        }
        path.close();
        canvas.drawPath(path, regionPaint);

        // 计算区域中心
        PointF center = calculateCenter(points);

        // 绘制区域ID
        drawTextWithBackgroundOnCanvas(canvas, areaId, center.x, center.y);
    }

    // 新增方法：在指定画布上绘制带背景的文字
    private void drawTextWithBackgroundOnCanvas(Canvas canvas, String text, float x, float y) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Text background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#80000000"));
        bgPaint.setStyle(Paint.Style.FILL);

        // Calculate text dimensions
        float textWidth = textPaint.measureText(text);
        float textHeight = textPaint.descent() - textPaint.ascent();
        float padding = 10f;

        // Draw background rectangle
        canvas.drawRect(
                x - textWidth/2 - padding,
                y - textHeight/2 - padding,
                x + textWidth/2 + padding,
                y + textHeight/2 + padding,
                bgPaint);

        // Draw text
        canvas.drawText(text, x, y, textPaint);
    }

    // 刷新视图但不重置缩放 - 优化后的版本
    private void refreshViewWithoutReset() {
        // 添加状态恢复标志
        isRestoringState = true;

        // 保存当前缩放状态
        final float tempScale = areaImageView.getScale();
        final RectF tempRect = areaImageView.getDisplayRect();
        final float[] center = new float[2]; // 使用数组存储中心点
        if (tempRect != null) {
            center[0] = tempRect.centerX();
            center[1] = tempRect.centerY();
        }

        // 使用新的位图更新视图
        areaImageView.setImageBitmap(drawingBitmap);

        // 恢复缩放状态（使用动画避免跳动）
        if (tempScale > 1.0f) {
            areaImageView.post(() -> {
                areaImageView.setScale(tempScale, center[0], center[1], true);
            });
        }

        // 确保视图已初始化
        if (!isViewInitialized) {
            areaImageView.post(() -> {
                areaImageView.setScale(tempScale, center[0], center[1], true);
                isViewInitialized = true;
            });
        }
    }


    private void updateImageViewWithoutReset() {
        // 添加状态恢复标志
        isRestoringState = true;

        // 保存当前缩放状态
        final float tempScale = areaImageView.getScale();
        final RectF tempRect = areaImageView.getDisplayRect();
        final float[] center = new float[2]; // 使用数组存储中心点
        if (tempRect != null) {
            center[0] = tempRect.centerX();
            center[1] = tempRect.centerY();
        }

        // 更新图像
        areaImageView.setImageBitmap(drawingBitmap);

        // 恢复缩放状态（使用平滑动画）
        if (tempScale > 1.0f) {
            areaImageView.post(() -> {
                areaImageView.setScale(tempScale, center[0], center[1], true);
            });
        }
    }

    private void startDrawingRegion(String areaType, String areaId) {
        // 保存当前视图状态（原有逻辑）
        preDrawScale = areaImageView.getScale();
        RectF preDrawRect = areaImageView.getDisplayRect();
        if (preDrawRect != null) {
            preDrawCenterX = preDrawRect.centerX();
            preDrawCenterY = preDrawRect.centerY();
        }

        // 初始化绘制状态（原有逻辑）
        currentAreaType = areaType;
        currentAreaId = areaId;
        currentPoints.clear();
        isDrawingMode = true;

        // 清空覆盖层
        if (drawingOverlayBitmap != null) {
            drawingOverlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            drawingOverlayView.setImageBitmap(drawingOverlayBitmap);
        }

        drawingControlsLayout.setVisibility(View.VISIBLE);
    }


    // 仅重绘已保存的区域（不包括当前绘制的点）
    private void redrawSavedAreasOnly() {
        if (originalMapBitmap == null) return;

        // 重置绘图画布
        drawingBitmap = originalMapBitmap.copy(Bitmap.Config.ARGB_8888, true);
        drawingCanvas = new Canvas(drawingBitmap);

        // 绘制所有已保存区域
        List<AreaViewModel.AreaConfig> areas = areaViewModel.getAreas().getValue();
        if (areas != null) {
            for (AreaViewModel.AreaConfig area : areas) {
                List<PointF> pixelPoints = convertWorldToPixel(area.getRange());
                drawSingleArea(area.getId(), pixelPoints);
            }
        }
    }

    private void saveDrawnRegion() {
        if (currentPoints.size() < 3) {
            showToast(getString(R.string.avoidance_need_3_points));
            return;
        }

        // 确保有有效的区域数据
        if (currentAreaId == null || currentAreaType == null) {
            showToast(getString(R.string.avoidance_area_info_incomplete));
            return;
        }

        try {
            // 闭合区域
            PointF first = currentPoints.get(0);
            PointF last = currentPoints.get(currentPoints.size() - 1);

            // 先在覆盖层上闭合
            if (drawingOverlayCanvas != null) {
                drawingOverlayCanvas.drawLine(last.x, last.y, first.x, first.y, regionPaint);
                drawingOverlayView.invalidate();
            }

            // 创建世界坐标字符串
            StringBuilder rangeBuilder = new StringBuilder();
            for (PointF point : currentPoints) {
                float worldX = mapOrigin[0] + point.x * mapResolution;
                float worldY = mapOrigin[1] + (mapHeight - point.y) * mapResolution;
                rangeBuilder.append(String.format(Locale.US, "%.2f,%.2f;", worldX, worldY));
            }
            String worldCoordinates = rangeBuilder.toString();

            // 保存到ViewModel
            AreaViewModel.AreaConfig area = new AreaViewModel.AreaConfig(
                    currentAreaId,
                    currentAreaType,
                    worldCoordinates
            );

            // 先添加到ViewModel
            areaViewModel.addArea(area);

            // 然后合并到主位图
            if (originalMapBitmap != null && !originalMapBitmap.isRecycled()
                    && drawingOverlayBitmap != null && !drawingOverlayBitmap.isRecycled()) {
                drawingBitmap = originalMapBitmap.copy(Bitmap.Config.ARGB_8888, true);
                drawingCanvas = new Canvas(drawingBitmap);
                drawingCanvas.drawBitmap(drawingOverlayBitmap, 0, 0, null);

                // 更新显示
                areaImageView.setImageBitmap(drawingBitmap);
            }

            // 保存到本地缓存
            DrawnArea drawnArea = new DrawnArea(
                    currentAreaId,
                    currentAreaType,
                    new ArrayList<>(currentPoints),
                    worldCoordinates
            );

            if (!mapAreas.containsKey(currentMapName)) {
                mapAreas.put(currentMapName, new ArrayList<>());
            }
            mapAreas.get(currentMapName).add(drawnArea);

            // 重置状态但不退出界面
            resetDrawingState();

            showToast(getString(R.string.avoidance_area_saved));

            // 延迟执行重绘，确保UI更新完成
            areaImageView.postDelayed(this::redrawAllAreasAndResetZoom, 300);

        } catch (Exception e) {
            Log.e(TAG, "保存区域失败", e);
            showToast(getString(R.string.avoidance_save_failed_retry));
        }
    }

    private void resetDrawingState() {
        isDrawingMode = false;
        currentPoints.clear();
        drawingControlsLayout.setVisibility(View.GONE);

        // 清空覆盖层但不释放资源
        if (drawingOverlayCanvas != null) {
            drawingOverlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            drawingOverlayView.invalidate();
        }

        // 重置当前区域信息
        currentAreaId = null;
        currentAreaType = null;
    }


    private void endDrawingSession() {
        isDrawingMode = false;
        currentPoints.clear();
        drawingControlsLayout.setVisibility(View.GONE);

        // 清空覆盖层但不释放资源
        if (drawingOverlayCanvas != null) {
            drawingOverlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            drawingOverlayView.invalidate();
        }
    }

    private void redrawAllAreasAndResetZoom() {
        if (getActivity() == null || isDetached()) {
            return; // 防止在Fragment已分离时执行
        }

        try {
            // 1. 重绘所有区域
            drawingBitmap = originalMapBitmap.copy(Bitmap.Config.ARGB_8888, true);
            drawingCanvas = new Canvas(drawingBitmap);

            List<AreaViewModel.AreaConfig> areas = areaViewModel.getAreas().getValue();
            if (areas != null) {
                for (AreaViewModel.AreaConfig area : areas) {
                    List<PointF> pixelPoints = convertWorldToPixel(area.getRange());
                    drawSingleArea(area.getId(), pixelPoints);
                }
            }

            // 2. 更新视图
            areaImageView.setImageBitmap(drawingBitmap);

            // 3. 延迟执行缩放重置，确保视图已更新
            areaImageView.post(() -> {
                if (areaImageView != null) {
                    areaImageView.setScale(1.0f, true);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "重绘区域失败", e);
        }
    }


    private void redrawAllAreas() {
        try {
            if (originalMapBitmap == null || isDetached()) {
                return;
            }

            // 重置绘图画布
            drawingBitmap = originalMapBitmap.copy(Bitmap.Config.ARGB_8888, true);
            drawingCanvas = new Canvas(drawingBitmap);

            // 绘制所有区域
            List<AreaViewModel.AreaConfig> areas = areaViewModel.getAreas().getValue();
            if (areas != null) {
                Log.d("AreaRedraw", "Redrawing " + areas.size() + " areas");
                for (AreaViewModel.AreaConfig area : areas) {
                    List<PointF> pixelPoints = convertWorldToPixel(area.getRange());
                    drawSingleArea(area.getId(), pixelPoints);
                }
            }

            // 更新图像视图
            areaImageView.setImageBitmap(drawingBitmap);

        } catch (Exception e) {
            Log.e("AreaRedraw", "Error redrawing areas", e);
        }
    }

    private List<PointF> convertWorldToPixel(String worldCoordinates) {
        List<PointF> points = new ArrayList<>();
        if (worldCoordinates == null || worldCoordinates.isEmpty()) {
            return points;
        }

        String[] coords = worldCoordinates.split(";");
        for (String coord : coords) {
            if (!coord.isEmpty()) {
                String[] xy = coord.split(",");
                if (xy.length == 2) {
                    try {
                        float worldX = Float.parseFloat(xy[0]);
                        float worldY = Float.parseFloat(xy[1]);

                        // 转换公式
                        float pixelX = (worldX - mapOrigin[0]) / mapResolution;
                        float pixelY = mapHeight - ((worldY - mapOrigin[1]) / mapResolution);

                        points.add(new PointF(pixelX, pixelY));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "坐标转换错误", e);
                    }
                }
            }
        }
        return points;
    }

    private void drawSingleArea(String areaId, List<PointF> points) {
        if (points.size() < 3) return;

        // 绘制区域边界
        Path path = new Path();
        path.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).x, points.get(i).y);
        }
        path.close();
        drawingCanvas.drawPath(path, regionPaint);

        // 计算区域中心
        PointF center = calculateCenter(points);

        // 绘制区域ID
        drawTextWithBackground(areaId, center.x, center.y);
    }

    private PointF calculateCenter(List<PointF> points) {
        float sumX = 0f, sumY = 0f;
        for (PointF point : points) {
            sumX += point.x;
            sumY += point.y;
        }
        return new PointF(sumX / points.size(), sumY / points.size());
    }

    private void drawTextWithBackground(String text, float x, float y) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Text background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#80000000"));
        bgPaint.setStyle(Paint.Style.FILL);

        // Calculate text dimensions
        float textWidth = textPaint.measureText(text);
        float textHeight = textPaint.descent() - textPaint.ascent();
        float padding = 10f;

        // Draw background rectangle
        drawingCanvas.drawRect(
                x - textWidth/2 - padding,
                y - textHeight/2 - padding,
                x + textWidth/2 + padding,
                y + textHeight/2 + padding,
                bgPaint);

        // Draw text
        drawingCanvas.drawText(text, x, y, textPaint);
    }

    private void cancelDrawingRegion() {
        endDrawingSession();

        // 恢复绘制前的视图状态
        if (preDrawScale > 1.0f) {
            areaImageView.post(() -> {
                areaImageView.setScale(preDrawScale, preDrawCenterX, preDrawCenterY, true);
            });
        }
        showToast(getString(R.string.avoidance_draw_cancelled));
    }

    private String extractPureBase64(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return "";
        }

        String pureBase64 = base64Data;
        if (base64Data.contains(",")) {
            pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
        }

        if (pureBase64.contains("\"")) {
            pureBase64 = pureBase64.substring(0, pureBase64.indexOf("\""));
        }

        return pureBase64;
    }

    @Override
    public void onConnectionEstablished() {
        Log.d(TAG, "WebSocket connection established");
        runOnUiThreadIfAttached(() -> {
            showToast(getString(R.string.avoidance_ws_connected));
            requestMapDataFromRedis("default_map_name");
        });
    }

    private void requestMapDataFromRedis(String mapName) {
        try {
            currentMapName = mapName;
            JSONObject request = new JSONObject();
            request.put("cmd", "getMapDataRedis");
            request.put("mapName", mapName);

            if (webSocketClient != null && webSocketClient.isOpen()) {
                webSocketClient.send(request.toString());
            } else {
                showToast(getString(R.string.avoidance_ws_not_connected_retrying));
                connectWebSocket();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionClosed() {
        Log.d(TAG, "WebSocket connection closed");
        showToast(getString(R.string.avoidance_ws_connection_closed));
    }

    @Override
    public void onError(Exception ex) {
        Log.e(TAG, "WebSocket error", ex);
        showToast(getString(R.string.avoidance_ws_error, ex.getMessage()));
    }

    private void initViews(View view) {
        tabLayout = view.findViewById(R.id.tabLayout);
        areaImageContainer = view.findViewById(R.id.areaImageContainer);
        areaImageView = view.findViewById(R.id.areaImageView);
        btnAddArea = view.findViewById(R.id.btnAddArea);
        btnAddRobot = view.findViewById(R.id.btnAddRobot);
        rvAreas = view.findViewById(R.id.rvAreas);
        rvRobots = view.findViewById(R.id.rvRobots);
        robotConfigLayout = view.findViewById(R.id.robotConfigLayout);

        areaImageView.setMaximumScale(10f);
        areaImageView.setMediumScale(5f);
        areaImageView.setMinimumScale(1f);
        areaImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        areaImageView.setZoomable(true);

        // Set drawing control button click events
        btnSaveDrawing.setOnClickListener(v -> saveDrawnRegion());
        btnCancelDrawing.setOnClickListener(v -> cancelDrawingRegion());

        drawingOverlayView = new ImageView(getContext());
        drawingOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        drawingOverlayView.setScaleType(ImageView.ScaleType.MATRIX);
        areaImageContainer.addView(drawingOverlayView);

        // 设置覆盖层与主视图同步缩放
        areaImageView.setOnMatrixChangeListener(rect -> {
            drawingOverlayView.setImageMatrix(areaImageView.getImageMatrix());
            if (rect != null) {
                currentScale = areaImageView.getScale();
                currentCenterX = rect.centerX();
                currentCenterY = rect.centerY();
            }
        });
    }

    private void showToast(String message) {
        runOnUiThreadIfAttached(() -> {
            Context context = getContext();
            if (context != null) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void runOnUiThreadIfAttached(Runnable action) {
        FragmentActivity activity = getActivity();
        if (activity == null || !isAdded() || getView() == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (isAdded() && getContext() != null && getView() != null) {
                action.run();
            }
        });
    }

    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.area_settings_title));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.robot_config_title));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                updateTabContent(tab.getPosition());
                if (originalMapBitmap != null) {
                    redrawAllAreas();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        updateTabContent(0);
    }

    private void updateTabContent(int position) {
        boolean isAreaTab = position == 0;
        areaImageContainer.setVisibility(isAreaTab ? View.VISIBLE : View.GONE);
        btnAddArea.setVisibility(isAreaTab ? View.VISIBLE : View.GONE);
        rvAreas.setVisibility(isAreaTab ? View.VISIBLE : View.GONE);
        robotConfigLayout.setVisibility(isAreaTab ? View.GONE : View.VISIBLE);
        rvRobots.setVisibility(isAreaTab ? View.GONE : View.VISIBLE);
    }

    private void setupRecyclerViews() {
        areaAdapter = new AreaAdapter(new ArrayList<>());
        rvAreas.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvAreas.setAdapter(areaAdapter);

        robotAdapter = new RobotAdapter(new ArrayList<>());
        rvRobots.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvRobots.setAdapter(robotAdapter);
    }

    private void observeData() {
        areaViewModel.getAreas().observe(getViewLifecycleOwner(), areas ->
                areaAdapter.updateData(areas));

        robotViewModel.getRobots().observe(getViewLifecycleOwner(), robots ->
                robotAdapter.updateData(robots));
    }

    private void setupListeners() {
        btnAddArea.setOnClickListener(v -> showAddAreaDialog());
        btnAddRobot.setOnClickListener(v -> showAddRobotDialog());
    }

    private void showAddAreaDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_area, null);

        EditText etAreaType = dialogView.findViewById(R.id.etAreaType);
        EditText etAreaId = dialogView.findViewById(R.id.etAreaId);
        MaterialButton btnConfirm = dialogView.findViewById(R.id.btnConfirm);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancel);

        // 创建对话框并设置主题
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        // 设置对话框属性
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.gravity = Gravity.CENTER;
            window.setAttributes(layoutParams);

            // 键盘弹出时压缩窗口，配合 ScrollView 确保按钮可见
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        btnConfirm.setOnClickListener(v -> {
            String type = etAreaType.getText().toString().trim();
            String id = etAreaId.getText().toString().trim();

            if (type.isEmpty() || id.isEmpty()) {
                Toast.makeText(requireContext(), R.string.avoidance_fill_area_type_and_id, Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();
            startDrawingRegion(type, id);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        // 显示对话框
        dialog.show();

        // 确保输入框获得焦点并弹出键盘
        etAreaType.requestFocus();
        etAreaType.postDelayed(() -> {
            // 使用完整的类名避免导入问题
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etAreaType, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }



    private void showAddRobotDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_robot, null);

        EditText etName = dialogView.findViewById(R.id.et_robot_name_input);
        EditText etAddress = dialogView.findViewById(R.id.et_robot_address_input);
        Button btnConfirm = dialogView.findViewById(R.id.btnConfirm);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        // 创建对话框并设置主题
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        // 设置对话框属性
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.gravity = Gravity.CENTER;
            window.setAttributes(layoutParams);

            // 键盘弹出时压缩窗口，配合 ScrollView 确保按钮可见
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String address = etAddress.getText().toString().trim();

            if (name.isEmpty() || address.isEmpty()) {
                Toast.makeText(requireContext(), R.string.avoidance_fill_complete_info, Toast.LENGTH_SHORT).show();
                return;
            }

            // 地址范围校验 (0-255)
            try {
                int addr = Integer.parseInt(address);
                if (addr < 0 || addr > 255) {
                    Toast.makeText(requireContext(), R.string.avoidance_address_range_0_255, Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), R.string.avoidance_address_must_be_number, Toast.LENGTH_SHORT).show();
                return;
            }

            String id = String.valueOf(System.currentTimeMillis());
            RobotViewModel.RobotConfig robot = new RobotViewModel.RobotConfig(id, name, address);
            robotViewModel.addRobot(robot);

            Toast.makeText(requireContext(), R.string.avoidance_robot_added, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        // 显示对话框
        dialog.show();

        // 确保输入框获得焦点并弹出键盘
        etName.requestFocus();
        etName.postDelayed(() -> {
            // 使用完整的类名避免导入问题
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etName, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }

    private static class AreaAdapter extends RecyclerView.Adapter<AreaAdapter.AreaViewHolder> {
        private List<AreaViewModel.AreaConfig> areas;

        public AreaAdapter(List<AreaViewModel.AreaConfig> areas) {
            this.areas = areas;
        }

        public void updateData(List<AreaViewModel.AreaConfig> newAreas) {
            this.areas = newAreas;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AreaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_area, parent, false);
            return new AreaViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull AreaViewHolder holder, int position) {
            holder.bind(areas.get(position));
        }

        @Override
        public int getItemCount() {
            return areas.size();
        }

        static class AreaViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvAreaInfo;
            private final ImageView ivDelete;

            public AreaViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAreaInfo = itemView.findViewById(R.id.tvAreaInfo);
                ivDelete = itemView.findViewById(R.id.ivDelete);

                ivDelete.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        if (itemView.getContext() instanceof FragmentActivity) {
                            AreaViewModel areaViewModel = new ViewModelProvider((FragmentActivity) itemView.getContext())
                                    .get(AreaViewModel.class);
                            RobotViewModel robotViewModel = new ViewModelProvider((FragmentActivity) itemView.getContext())
                                    .get(RobotViewModel.class);
                            showDeleteConfirmationDialog(itemView.getContext(), position, true,
                                    areaViewModel, robotViewModel);
                        }
                    }
                });
            }

            public void bind(AreaViewModel.AreaConfig area) {
                tvAreaInfo.setText(area.toString());
            }
        }
    }

    private static class RobotAdapter extends RecyclerView.Adapter<RobotAdapter.RobotViewHolder> {
        private List<RobotViewModel.RobotConfig> robots;

        public RobotAdapter(List<RobotViewModel.RobotConfig> robots) {
            this.robots = robots;
        }

        public void updateData(List<RobotViewModel.RobotConfig> newRobots) {
            this.robots = newRobots;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RobotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_robot, parent, false);
            return new RobotViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RobotViewHolder holder, int position) {
            holder.bind(robots.get(position));
        }

        @Override
        public int getItemCount() {
            return robots.size();
        }

        static class RobotViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvRobotInfo;
            private final ImageView ivDelete;

            public RobotViewHolder(@NonNull View itemView) {
                super(itemView);
                tvRobotInfo = itemView.findViewById(R.id.tvRobotInfo);
                ivDelete = itemView.findViewById(R.id.ivDelete);

                ivDelete.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        if (itemView.getContext() instanceof FragmentActivity) {
                            AreaViewModel areaViewModel = new ViewModelProvider((FragmentActivity) itemView.getContext())
                                    .get(AreaViewModel.class);
                            RobotViewModel robotViewModel = new ViewModelProvider((FragmentActivity) itemView.getContext())
                                    .get(RobotViewModel.class);
                            showDeleteConfirmationDialog(itemView.getContext(), position, false,
                                    areaViewModel, robotViewModel);
                        }
                    }
                });
            }

            public void bind(RobotViewModel.RobotConfig robot) {
                tvRobotInfo.setText(robot.toString());
            }
        }
    }
    private static void showDeleteConfirmationDialog(Context context, int position, boolean isArea,
                                                     AreaViewModel areaViewModel, RobotViewModel robotViewModel) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_delete, null);
        TextView message = dialogView.findViewById(R.id.dialog_message);
        Button negativeButton = dialogView.findViewById(R.id.negative_button);
        Button positiveButton = dialogView.findViewById(R.id.positive_button);

        message.setText(isArea ? context.getString(R.string.avoidance_delete_area_confirm) : context.getString(R.string.avoidance_delete_robot_confirm));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        positiveButton.setOnClickListener(v -> {
            if (isArea) {
                List<AreaViewModel.AreaConfig> areas = areaViewModel.getAreas().getValue();
                if (areas != null && position < areas.size()) {
                    // 1. 从ViewModel中删除
                    String areaId = areas.get(position).getId();
                    areaViewModel.deleteArea(areaId);

                    // 2. 显示反馈
                    Toast.makeText(context, R.string.avoidance_area_deleted, Toast.LENGTH_SHORT).show();

                    // 3. 确保数据已更新
                    Log.d("AreaDelete", "Deleted area with ID: " + areaId);
                }
            } else {
                // 机器人删除逻辑
                List<RobotViewModel.RobotConfig> robots = robotViewModel.getRobots().getValue();
                if (robots != null && position < robots.size()) {
                    String robotId = robots.get(position).getId();
                    robotViewModel.deleteRobot(robotId);
                    Toast.makeText(context, R.string.avoidance_robot_deleted, Toast.LENGTH_SHORT).show();
                }
            }
            dialog.dismiss();
        });

        dialog.show();
    }
}
