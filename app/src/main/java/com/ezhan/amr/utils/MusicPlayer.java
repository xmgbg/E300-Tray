package com.ezhan.amr.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class MusicPlayer implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicPlayer";

    // 单例实例
    private static MusicPlayer instance;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private Context context;

    // 音频焦点相关
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusGranted = false;

    // 播放状态
    private boolean isPrepared = false;
    private boolean isPlaying = false;
    private int currentPosition = 0;
    private Uri currentUri;
    private int audioResourceId = -1;

    // 回调接口
    private MusicPlayerListener listener;

    // 私有构造函数
    private MusicPlayer(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    // 单例模式
    public static synchronized MusicPlayer getInstance(Context context) {
        if (instance == null) {
            instance = new MusicPlayer(context);
        }
        return instance;
    }

    // 设置播放状态监听器
    public void setListener(MusicPlayerListener listener) {
        this.listener = listener;
    }

    // 播放资源ID的音乐
    public void start(int resourceId) {
        Uri uri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resourceId);
        this.audioResourceId = resourceId;
        start(uri);
    }

    // 播放URI的音乐
    public void start(Uri uri) {
        stop(); // 确保停止当前播放

        currentUri = uri;
        initializeMediaPlayer();

        try {
            mediaPlayer.setDataSource(context, uri);
            mediaPlayer.prepareAsync(); // 异步准备
            if (listener != null) {
                listener.onPreparing();
            }
        } catch (Exception e) {
            Log.e(TAG, "设置数据源失败", e);
            if (listener != null) {
                listener.onError("无法加载音乐资源");
            }
        }
    }

    // 停止播放
    public void stop() {
        if (mediaPlayer != null) {
            if (isPlaying) {
                mediaPlayer.stop();
                isPlaying = false;
            }
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
            isPrepared = false;
            currentPosition = 0;
        }
        abandonAudioFocus();
    }

    // 暂停播放
    public void pause() {
        if (mediaPlayer != null && isPrepared && isPlaying) {
            currentPosition = mediaPlayer.getCurrentPosition();
            mediaPlayer.pause();
            isPlaying = false;
            abandonAudioFocus();
            if (listener != null) {
                listener.onPaused();
            }
        }
    }

    // 从当前位置恢复播放
    public void resume() {
        if (mediaPlayer != null && isPrepared) {
            // 请求音频焦点
            requestAudioFocus();
            if (!audioFocusGranted) {
                Log.w(TAG, "无法获取音频焦点，播放未开始");
                return;
            }

            mediaPlayer.seekTo(currentPosition);
            mediaPlayer.start();
            isPlaying = true;
            if (listener != null) {
                listener.onPlaying();
            }
        } else if (currentUri != null) {
            // 如果播放器未准备好，重新开始播放
            start(currentUri);
        } else if (audioResourceId != -1) {
            start(audioResourceId);
        }
    }

    // 获取当前播放状态
    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isCurrentResource(int resourceId) {
        return audioResourceId == resourceId && mediaPlayer != null;
    }

    public boolean isPlayingResource(int resourceId) {
        return isCurrentResource(resourceId) && isPlaying;
    }

    // 获取歌曲总时长（毫秒）
    public int getDuration() {
        if (mediaPlayer != null && isPrepared) {
            return mediaPlayer.getDuration();
        }
        return 0;
    }

    // 获取当前播放位置（毫秒）
    public int getCurrentPosition() {
        if (mediaPlayer != null && isPrepared && isPlaying) {
            return mediaPlayer.getCurrentPosition();
        }
        return currentPosition;
    }

    // 跳转到指定位置
    public void seekTo(int position) {
        if (mediaPlayer != null && isPrepared) {
            if (position < 0) position = 0;
            int duration = mediaPlayer.getDuration();
            if (position > duration) position = duration;

            currentPosition = position;
            mediaPlayer.seekTo(position);
        }
    }

    // 请求音频焦点
    private void requestAudioFocus() {
        if (audioManager == null) {
            return;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();

            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }

        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    // 释放音频焦点
    private void abandonAudioFocus() {
        if (audioManager == null || !audioFocusGranted) {
            return;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            result = audioManager.abandonAudioFocus(this);
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusGranted = false;
        }
    }

    // 初始化媒体播放器
    private void initializeMediaPlayer() {
        mediaPlayer = new MediaPlayer();

        // 设置音频属性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
            );
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }

        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
    }

    // 音频焦点变化回调
    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // 重新获得焦点，恢复播放
                if (mediaPlayer != null && !isPlaying && isPrepared) {
                    mediaPlayer.start();
                    isPlaying = true;
                    if (listener != null) {
                        listener.onPlaying();
                    }
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // 长期失去焦点，停止播放
                pause();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 暂时失去焦点，暂停播放
                if (isPlaying) {
                    pause();
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 可以降低音量继续播放
                if (mediaPlayer != null && isPlaying) {
                    mediaPlayer.setVolume(0.3f, 0.3f); // 降低音量
                }
                break;
        }
    }

    // 准备完成回调
    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;

        // 请求音频焦点
        requestAudioFocus();
        if (!audioFocusGranted) {
            Log.w(TAG, "无法获取音频焦点，播放被阻止");
            if (listener != null) {
                listener.onError("无法获取音频焦点，播放被阻止");
            }
            return;
        }

        // 开始播放
        if (currentPosition > 0) {
            mp.seekTo(currentPosition);
        }
        mp.start();
        isPlaying = true;

        if (listener != null) {
            listener.onPlaying();
        }
    }

    // 播放完成回调
    @Override
    public void onCompletion(MediaPlayer mp) {
        isPlaying = false;
        currentPosition = 0;
        abandonAudioFocus();
        if (listener != null) {
            listener.onCompleted();
        }
    }

    // 错误处理回调
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        isPlaying = false;
        isPrepared = false;

        String errorMsg = "播放错误: ";
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                errorMsg += "未知错误";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                errorMsg += "服务器挂掉";
                break;
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                errorMsg += "文件格式不支持";
                break;
            default:
                errorMsg += what;
        }

        Log.e(TAG, errorMsg + ", extra: " + extra);

        if (listener != null) {
            listener.onError(errorMsg);
        }

        return true; // 表示错误已处理
    }

    // 释放资源（在不再需要播放器时调用）
    public void release() {
        stop();
        context = null;
        instance = null;
    }

    // 播放器状态回调接口
    public interface MusicPlayerListener {
        void onPreparing();   // 正在准备
        void onPlaying();     // 正在播放
        void onPaused();      // 已暂停
        void onCompleted();   // 播放完成
        void onError(String error); // 播放错误
    }
}
