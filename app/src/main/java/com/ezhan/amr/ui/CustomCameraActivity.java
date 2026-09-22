package com.ezhan.amr.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.ezhan.amr.R;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CustomCameraActivity extends AppCompatActivity {
    private static final String TAG = "HighQualityCamera";
    private TextureView textureView;
    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Surface previewSurface;

    // 添加服务器配置
    private static final String SERVER_URL = "http://192.0.2.10:5000/upload";
    private static final String PROMPT_TEXT = "请详细分析图片中的工厂环境安全状况，包括人员安全装备、设备运行状态、潜在安全隐患等";
    private OkHttpClient httpClient = new OkHttpClient();

    private Size previewSize;
    private Size captureSize;
    private int sensorOrientation;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int MAX_PREVIEW_WIDTH = 1080;
    private static final int MAX_PREVIEW_HEIGHT = 960;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_view);

        textureView = findViewById(R.id.texture_view);
        Button btnCapture = findViewById(R.id.btn_capture);

        btnCapture.setOnClickListener(v -> {
            if (cameraDevice != null) {
                takePicture();
            } else {
                Toast.makeText(this, R.string.camera_not_ready, Toast.LENGTH_SHORT).show();
            }
        });

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // 延迟初始化以确保视图已完成布局
        textureView.post(() -> initializeCamera());
    }

    private void initializeCamera() {
        try {
            cameraId = getCameraId();
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // 获取TextureView的实际尺寸，如果无效则使用默认值
            int width = textureView.getWidth() > 0 ? textureView.getWidth() : MAX_PREVIEW_WIDTH;
            int height = textureView.getHeight() > 0 ? textureView.getHeight() : MAX_PREVIEW_HEIGHT;

            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height);
            captureSize = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            // 设置SurfaceTexture监听
            if (textureView.isAvailable()) {
                openCamera();
            } else {
                textureView.setSurfaceTextureListener(surfaceTextureListener);
            }
        } catch (CameraAccessException e) {
            Toast.makeText(this, R.string.camera_cannot_access, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private String getCameraId() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return cameraManager.getCameraIdList()[0];
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() -
                            (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private Size chooseOptimalSize(Size[] choices, int width, int height) {
        if (width <= 0 || height <= 0) {
            return choices.length > 0 ? choices[0] : new Size(1280, 720);
        }

        List<Size> bigEnough = new ArrayList<>();
        int w = width;
        int h = height;

        // 交换宽高处理竖屏情况
        if (sensorOrientation == 90 || sensorOrientation == 270) {
            int temp = w;
            w = h;
            h = temp;
        }

        float targetRatio = (float) h / w;
        float aspectTolerance = 0.1f;

        for (Size option : choices) {
            if (option.getWidth() <= MAX_PREVIEW_WIDTH &&
                    option.getHeight() <= MAX_PREVIEW_HEIGHT) {
                float ratio = (float) option.getHeight() / option.getWidth();
                if (Math.abs(ratio - targetRatio) <= aspectTolerance) {
                    bigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return choices.length > 0 ? choices[0] : new Size(1280, 720);
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            };

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            Toast.makeText(CustomCameraActivity.this,
                    getString(R.string.camera_error, error), Toast.LENGTH_SHORT).show();
            finish();
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || cameraDevice == null) return;

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            // 设置高质量预览参数
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            previewRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;

                            captureSession = session;
                            try {
                                // 应用预览旋转
                                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                                int deviceOrientation = ORIENTATIONS.get(rotation);
                                int totalRotation = (sensorOrientation + deviceOrientation + 360) % 360;
                                previewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, totalRotation);

                                session.setRepeatingRequest(
                                        previewRequestBuilder.build(),
                                        null,
                                        backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(CustomCameraActivity.this,
                                    getString(R.string.camera_config_failed), Toast.LENGTH_SHORT).show();
                        }
                    },
                    backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        if (cameraDevice == null) return;

        try {
            // 创建高质量ImageReader
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    ImageFormat.JPEG,
                    2); // 双缓冲

            List<Surface> outputSurfaces = new ArrayList<>(2);
            outputSurfaces.add(previewSurface);
            outputSurfaces.add(imageReader.getSurface());

            // 创建拍照会话
            cameraDevice.createCaptureSession(
                    outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                // 创建高质量拍照请求
                                CaptureRequest.Builder captureBuilder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                captureBuilder.addTarget(previewSurface);
                                captureBuilder.addTarget(imageReader.getSurface());

                                // 设置高质量拍照参数
                                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                                captureBuilder.set(CaptureRequest.EDGE_MODE,
                                        CaptureRequest.EDGE_MODE_HIGH_QUALITY);

                                // 设置方向
                                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                                        (sensorOrientation + ORIENTATIONS.get(rotation) + 360) % 360);

                                // 设置图片质量
                                captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);

                                // 执行拍照
                                session.capture(captureBuilder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(
                                                    @NonNull CameraCaptureSession session,
                                                    @NonNull CaptureRequest request,
                                                    @NonNull TotalCaptureResult result) {
                                                // 拍照完成后恢复预览
                                                createCameraPreviewSession();
                                            }
                                        },
                                        backgroundHandler);

                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(CustomCameraActivity.this,
                                    getString(R.string.camera_capture_config_failed), Toast.LENGTH_SHORT).show();
                        }
                    },
                    backgroundHandler);

            // 设置图片可用监听
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireNextImage();

                // 新增上传功能
                if (image != null) {
                    // 1. 获取图片字节数据
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] imageBytes = new byte[buffer.remaining()];
                    buffer.get(imageBytes);

                    // 2. 启动异步任务发送图片
                    new SendImageTask().execute(imageBytes);
                }

//                if (image != null) {
//                    saveImage(image);
//                    image.close();
//                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 新增异步任务类用于发送图片
    private class SendImageTask extends AsyncTask<byte[], Void, String> {
        @Override
        protected String doInBackground(byte[]... params) {
            byte[] imageBytes = params[0];

            try {
                // 1. 准备请求数据
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 2. 创建JSON请求体
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("file", base64Image);
                jsonBody.put("filename", "camera_capture.jpg");
                jsonBody.put("prompt", PROMPT_TEXT);

                // 3. 创建请求
                RequestBody body = RequestBody.create(
                        jsonBody.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(SERVER_URL)
                        .post(body)
                        .build();

                // 4. 发送请求并获取响应
                Response response = httpClient.newCall(request).execute();

                if (response.isSuccessful()) {
                    return getString(R.string.camera_upload_success);
                } else {
                    return getString(R.string.camera_upload_failed_http, response.code(), response.message());
                }
            } catch (Exception e) {
                return getString(R.string.camera_upload_exception, e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(String result) {
            // 显示上传结果
            Toast.makeText(CustomCameraActivity.this, result, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveImage(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        // 处理图片旋转
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Matrix matrix = new Matrix();
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int orientation = ORIENTATIONS.get(rotation);
        int totalRotation = (sensorOrientation + orientation + 360) % 360;
        matrix.postRotate(totalRotation);

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // 保存图片
        File file = new File(getExternalFilesDir(null), "HQ_Photo_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(file)) {
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
            Toast.makeText(this, getString(R.string.camera_photo_saved, file.getAbsolutePath()), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            bitmap.recycle();
            rotatedBitmap.recycle();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || viewWidth <= 0 || viewHeight <= 0 || previewSize == null) {
            return;
        }

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());

        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        // 计算旋转角度
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int deviceOrientation = ORIENTATIONS.get(rotation);
        int totalRotation = (sensorOrientation + deviceOrientation + 360) % 360;

        matrix.postRotate(-totalRotation, centerX, centerY);
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

        textureView.setTransform(matrix);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
