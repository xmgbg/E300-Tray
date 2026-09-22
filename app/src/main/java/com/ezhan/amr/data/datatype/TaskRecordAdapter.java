// TaskRecordAdapter.java 中的修改
package com.ezhan.amr.data.datatype;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.R;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TaskRecordAdapter extends RecyclerView.Adapter<TaskRecordAdapter.ViewHolder> {

    private List<TaskRecord> records;
    private final Context context;
    private OnTaskActionListener listener; // 新增：任务操作监听器

    // 新增：任务操作监听器接口
    public interface OnTaskActionListener {
        void onTaskCancelled(TaskRecord taskRecord);
    }

    // 修改构造器
    public TaskRecordAdapter(Context context, List<TaskRecord> records) {
        this(context, records, null);
    }

    public TaskRecordAdapter(Context context, List<TaskRecord> records, OnTaskActionListener listener) {
        this.context = context;
        this.records = records;
        this.listener = listener;
    }

    public void updateData(List<TaskRecord> newRecords) {
        this.records = newRecords;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_task_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TaskRecord record = records.get(position);

        // 设置任务名称
        holder.tvTaskName.setText(record.getTaskName());

        // 设置任务类型
        holder.tvType.setText(getTypeString(record.getType()));

        // 设置状态及颜色
        String status = record.getStatus();
        holder.tvStatus.setText(getLocalizedStatus(status));
        setStatusColor(holder.tvStatus, status);

        // 设置时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        holder.tvTime.setText(context.getString(R.string.create_time_format, sdf.format(new Date(record.getCreateTime()))));

        // 设置持续时间
        long seconds = record.getDuration();
        String duration = String.format("%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        holder.tvDuration.setText(context.getString(R.string.duration_format, duration));

        // 新增：设置取消按钮
        // 只有"完成"或"失败"状态的任务不能取消
        boolean canBeCancelled = !TaskRecord.STATUS_COMPLETED.equals(status) &&
                !TaskRecord.STATUS_FAILED.equals(status) &&
                !TaskRecord.STATUS_CANCELLED.equals(status);

        if (holder.btnCancel != null) {
            if (canBeCancelled) {
                holder.btnCancel.setVisibility(View.VISIBLE);
                holder.btnCancel.setOnClickListener(v -> {
                    // 显示确认对话框
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.cancel_task_title)
                            .setMessage(R.string.cancel_task_message)
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                if (listener != null) {
                                    listener.onTaskCancelled(record);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });
            } else {
                holder.btnCancel.setVisibility(View.GONE);
            }
        }
    }

    private String getLocalizedStatus(String status) {
        if (TaskRecord.STATUS_COMPLETED.equals(status)) {
            return context.getString(R.string.task_record_status_completed);
        }
        if (TaskRecord.STATUS_FAILED.equals(status)) {
            return context.getString(R.string.task_record_status_failed);
        }
        if (TaskRecord.STATUS_CANCELLED.equals(status)) {
            return context.getString(R.string.task_record_status_cancelled);
        }
        if (TaskRecord.STATUS_RUNNING.equals(status)) {
            return context.getString(R.string.task_record_status_running);
        }
        return status;
    }

    private void setStatusColor(TextView tvStatus, String status) {
        switch (status) {
            case TaskRecord.STATUS_COMPLETED:
                tvStatus.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                break;
            case TaskRecord.STATUS_FAILED:
                tvStatus.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
                break;
            case TaskRecord.STATUS_CANCELLED:
                tvStatus.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF9800")));
                break;
            default:
                tvStatus.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#9E9E9E")));
        }
    }

    private String getTypeString(int type) {
        switch (type) {
            case TaskRecord.TYPE_CRUISE:
                return context.getString(R.string.cruise_task);
            case TaskRecord.TYPE_JACK:
                return context.getString(R.string.jack_task);
            case TaskRecord.TYPE_DELIVERY:
                return context.getString(R.string.delivery_task);
            case TaskRecord.TYPE_CHARGE:
                return context.getString(R.string.charge_task);
            case TaskRecord.TYPE_PARKING:
                return context.getString(R.string.parking_task);
            case TaskRecord.TYPE_STOP_CHARGE:
                return context.getString(R.string.stop_charge_task);
            case TaskRecord.TYPE_CALL:
                return context.getString(R.string.call_task);
            default:
                return context.getString(R.string.unknown_task);
        }
    }

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvType, tvTaskName, tvStatus, tvTime, tvDuration;
        MaterialButton btnCancel; // 新增：取消按钮

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvType = itemView.findViewById(R.id.tvType);
            tvTaskName = itemView.findViewById(R.id.tvTaskName);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            btnCancel = itemView.findViewById(R.id.btnCancel); // 确保布局中有这个ID
        }
    }
}