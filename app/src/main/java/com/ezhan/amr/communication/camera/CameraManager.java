package com.ezhan.amr.communication.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CameraManager {
    private static final String TAG = "CameraManager";
    private Context context;
    private Camera camera;
    private MediaRecorder mediaRecorder;
    private String videoFilePath;
    private boolean isRecording = false;
    private boolean isCameraOpen = false;
    private SurfaceTexture surfaceTexture;
    private CameraPreviewCallback previewCallback;
    private int cameraId = -1;
    private Camera.Size previewSize; // 保存预览尺寸

    public interface CameraPreviewCallback {
        void onPreviewFrame(byte[] data, int width, int height);
        void onCameraOpened(int cameraId, int width, int height);
        void onCameraError(String error);
    }

    public CameraManager(Context context) {
        this.context = context;
    }

    public void setPreviewCallback(CameraPreviewCallback callback) {
        this.previewCallback = callback;
    }

    public List<CameraInfo> listAvailableCameras() {
        Log.d(TAG, "正在检测可用摄像头...");
        List<CameraInfo> availableCameras = new ArrayList<>();

        try {
            int numberOfCameras = Camera.getNumberOfCameras();
            Log.d(TAG, "系统摄像头数量: " + numberOfCameras);

            for (int i = 0; i < numberOfCameras; i++) {
                Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                Camera.getCameraInfo(i, cameraInfo);

                try {
                    Camera tempCamera = Camera.open(i);
                    if (tempCamera != null) {
                        Camera.Parameters params = tempCamera.getParameters();
                        List<Camera.Size> sizes = params.getSupportedPreviewSizes();

                        int width = 640;
                        int height = 480;
                        if (!sizes.isEmpty()) {
                            // 获取最大的预览尺寸（通常是最好的）
                            Camera.Size size = sizes.get(0);
                            width = size.width;
                            height = size.height;
                        }

                        CameraInfo info = new CameraInfo();
                        info.id = i;
                        info.width = width;
                        info.height = height;
                        info.facing = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "前置" : "后置";
                        availableCameras.add(info);

                        Log.d(TAG, "✓ 摄像头 " + i + ": " + width + "x" + height + " (" + info.facing + ")");
                        tempCamera.release();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "摄像头 " + i + " 检测失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取摄像头列表失败: " + e.getMessage());
        }

        return availableCameras;
    }

    public boolean openCamera(int cameraId) {
        if (!checkPermissions()) {
            Log.e(TAG, "没有相机权限");
            return false;
        }

        try {
            Log.d(TAG, "目标摄像头ID: " + cameraId);

            // 1. 打开摄像头
            Log.d(TAG, "尝试打开摄像头 " + cameraId + "...");
            camera = Camera.open(cameraId);
            if (camera == null) {
                Log.e(TAG, "无法打开摄像头 " + cameraId);
                return false;
            }
            Log.d(TAG, "✓ 摄像头打开成功");

            // 2. 配置摄像头参数
            Camera.Parameters params = camera.getParameters();

            // 设置预览尺寸
            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
            previewSize = previewSizes.get(0); // 使用第一个支持的尺寸
            params.setPreviewSize(previewSize.width, previewSize.height);
            camera.setParameters(params);
            Log.d(TAG, String.format("设置预览尺寸: %dx%d", previewSize.width, previewSize.height));

            // 3. 创建预览纹理并开始预览
            surfaceTexture = new SurfaceTexture(0);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            Log.d(TAG, "预览已启动");

            // 4. 等待摄像头稳定
            Log.d(TAG, "等待摄像头稳定...");
            Thread.sleep(1000);

            // 5. 测试读取几帧
            Log.d(TAG, "正在初始化摄像头...");
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                int frameCount = 0;
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    frameCount++;
                    if (frameCount <= 10) {
                        Log.d(TAG, String.format("初始化帧 %d: 成功", frameCount));
                    }
                    if (frameCount == 10) {
                        camera.setPreviewCallback(null);
                    }
                }
            });

            // 等待读取10帧
            Thread.sleep(500);

            this.cameraId = cameraId;
            isCameraOpen = true;
            Log.d(TAG, "摄像头已成功启动并保持运行");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "打开摄像头失败: " + e.getMessage());
            e.printStackTrace();
            release();
            return false;
        }
    }

    public boolean startRecording() {
        if (!isCameraOpen || camera == null) {
            Log.e(TAG, "摄像头未打开");
            return false;
        }

        if (isRecording) {
            Log.e(TAG, "已经在录制中");
            return false;
        }

        try {
            // 1. 检查存储目录
            File mediaFile = getOutputMediaFile();
            if (mediaFile == null) {
                Log.e(TAG, "无法创建存储文件");
                return false;
            }
            videoFilePath = mediaFile.getAbsolutePath();
            Log.d(TAG, "视频将保存到: " + videoFilePath);

            // 2. 解锁摄像头
            camera.unlock();

            // 3. 创建MediaRecorder
            mediaRecorder = new MediaRecorder();

            // 4. 设置摄像头
            mediaRecorder.setCamera(camera);

            // 5. 检查是否有音频权限
            boolean hasAudio = hasAudioPermission();

            // 6. 设置音频源（如果有权限）
            if (hasAudio) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                Log.d(TAG, "设置音频源: CAMCORDER");
            }

            // 7. 设置视频源
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            Log.d(TAG, "设置视频源: CAMERA");

            // 8. 设置输出格式
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            Log.d(TAG, "设置输出格式: MPEG_4");

            // 9. 设置音频编码器（如果有权限）
            if (hasAudio) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128000); // 128 kbps
                mediaRecorder.setAudioSamplingRate(44100); // 44.1 kHz
                Log.d(TAG, "设置音频编码器: AAC");
            } else {
                Log.d(TAG, "无音频权限，只录制视频");
            }

            // 10. 设置视频编码器
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            Log.d(TAG, "设置视频编码器: H264");

            // 11. 设置视频参数
            mediaRecorder.setVideoSize(previewSize.width, previewSize.height);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(5000000); // 5 Mbps
            Log.d(TAG, String.format("设置视频参数: %dx%d, 30fps, 5Mbps",
                    previewSize.width, previewSize.height));

            // 12. 设置输出文件
            mediaRecorder.setOutputFile(videoFilePath);
            Log.d(TAG, "输出文件: " + videoFilePath);

            // 13. 设置预览显示
            mediaRecorder.setPreviewDisplay(new Surface(surfaceTexture));
            Log.d(TAG, "设置预览显示");

            // 14. 设置错误监听
            mediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override
                public void onError(MediaRecorder mr, int what, int extra) {
                    Log.e(TAG, String.format("MediaRecorder错误: what=%d, extra=%d", what, extra));
                    isRecording = false;
                }
            });

            // 15. 准备
            Log.d(TAG, "准备MediaRecorder...");
            mediaRecorder.prepare();

            // 16. 开始录制
            Log.d(TAG, "开始录制...");
            mediaRecorder.start();

            isRecording = true;
            Log.d(TAG, "✓ 开始录制视频");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "开始录制失败: " + e.getMessage());
            e.printStackTrace();
            releaseRecorder();

            // 重新锁定摄像头
            if (camera != null) {
                try {
                    camera.lock();
                } catch (Exception ex) {
                    Log.e(TAG, "锁定摄像头失败: " + ex.getMessage());
                }
            }

            return false;
        }
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int targetWidth, int targetHeight) {
        if (sizes == null || sizes.isEmpty()) return null;

        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) targetWidth / targetHeight;
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;

            if (Math.abs(size.width - targetWidth) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.width - targetWidth);
            }
        }

        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.width - targetWidth) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.width - targetWidth);
                }
            }
        }

        return optimalSize;
    }

    public void stopRecording() {
        Log.d(TAG, "停止录制...");

        if (isRecording) {
            try {
                if (mediaRecorder != null) {
                    mediaRecorder.setOnErrorListener(null);
                    mediaRecorder.stop();
                    Log.d(TAG, "✓ 录制已停止");

                    // 验证视频文件
                    validateVideoFile();
                }
            } catch (Exception e) {
                Log.e(TAG, "停止录制失败: " + e.getMessage());
                e.printStackTrace();
                // 删除可能损坏的文件
                if (videoFilePath != null) {
                    File file = new File(videoFilePath);
                    if (file.exists()) {
                        file.delete();
                        Log.d(TAG, "已删除损坏文件");
                    }
                }
            } finally {
                isRecording = false;
                releaseRecorder();
                // 保持摄像头打开状态
                if (camera != null) {
                    try {
                        camera.lock();
                        Log.d(TAG, "摄像头已锁定，保持运行状态");
                    } catch (Exception e) {
                        Log.e(TAG, "锁定摄像头失败: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 验证视频文件是否有效
     */
    public boolean validateVideoFile() {
        if (videoFilePath == null) {
            Log.e(TAG, "视频文件路径为空");
            return false;
        }

        File file = new File(videoFilePath);
        if (!file.exists()) {
            Log.e(TAG, "视频文件不存在: " + videoFilePath);
            return false;
        }

        long fileSize = file.length();
        Log.d(TAG, "=== 视频文件信息 ===");
        Log.d(TAG, "文件路径: " + videoFilePath);
        Log.d(TAG, "文件大小: " + fileSize + " 字节 (" + (fileSize / 1024) + " KB)");

        if (fileSize < 1024) { // 小于1KB
            Log.e(TAG, "文件太小，可能录制失败");
            return false;
        }

        // 尝试使用MediaPlayer验证
        android.media.MediaPlayer mediaPlayer = new android.media.MediaPlayer();
        try {
            mediaPlayer.setDataSource(videoFilePath);
            mediaPlayer.prepare();
            int duration = mediaPlayer.getDuration();
            Log.d(TAG, "视频时长: " + duration + " ms");
            mediaPlayer.release();
            Log.d(TAG, "✓ 视频文件有效");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "视频文件验证失败: " + e.getMessage());
            return false;
        }
    }

    private void releaseRecorder() {
        Log.d(TAG, "释放MediaRecorder资源...");

        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
                Log.d(TAG, "MediaRecorder已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放MediaRecorder失败: " + e.getMessage());
            }
            mediaRecorder = null;
        }
    }

    private File getOutputMediaFile() {
// Android 10+ 使用应用私有目录
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            mediaStorageDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AMRCamera");
//        } else {
//            mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
//                    Environment.DIRECTORY_MOVIES), "AMRCamera");
//        }
        // 使用指定的存储目录
        File mediaStorageDir = new File("/storage/emulated/0/Movies/AMRCamera");

        Log.d(TAG, "存储目录: " + mediaStorageDir.getAbsolutePath());

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.e(TAG, "无法创建存储目录");
                return null;
            }
            Log.d(TAG, "创建存储目录成功");
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "CAM_" + timeStamp + ".mp4";
        File outputFile = new File(mediaStorageDir, fileName);

        Log.d(TAG, "输出文件: " + outputFile.getAbsolutePath());
        return outputFile;
    }

    private boolean checkPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean storage = true;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            storage = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        Log.d(TAG, "权限状态 - 相机: " + camera + ", 音频: " + audio + ", 存储: " + storage);

        return camera && audio && storage;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public void release() {
        Log.d(TAG, "释放资源...");

        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
                Log.d(TAG, "MediaRecorder已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放MediaRecorder失败: " + e.getMessage());
            }
            mediaRecorder = null;
        }

        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.lock();
                camera.release();
                Log.d(TAG, "Camera已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放Camera失败: " + e.getMessage());
            }
            camera = null;
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
    }

    public String getVideoFilePath() {
        return videoFilePath;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public static class CameraInfo {
        public int id;
        public int width;
        public int height;
        public String facing;
    }
}