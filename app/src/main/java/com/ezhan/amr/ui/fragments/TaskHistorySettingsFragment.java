package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.TaskRecord;
import com.ezhan.amr.data.datatype.TaskRecordAdapter;
import com.ezhan.amr.viewmodels.TaskViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TaskHistorySettingsFragment extends Fragment {

    private TaskRecordAdapter adapter;
    private RecyclerView rvHistory;
    //private TextView tvEmpty;
    private ImageView ivNoDataBackground1;
    private TextView tvNoData;
    private MaterialAutoCompleteTextView etStartDate, etEndDate, spType;
    private DataStoreManager dataStoreManager;
    private TaskViewModel taskViewModel;
    private TextInputLayout startDateInputLayout, endDateInputLayout, typeInputLayout;
    // 新增时间格式常量
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private SimpleDateFormat sdf = new SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault());
    private Long startTimestamp = null;
    private Long endTimestamp = null;
    private static final long THIRTY_DAYS_IN_MILLIS = 30L * 24 * 60 * 60 * 1000; // 30天毫秒数

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        taskViewModel = MyApplication.getInstance().getTaskViewModel();
    }

    @SuppressLint("MissingInflatedId")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history_settings, container, false);

        // 初始化组件
        rvHistory = view.findViewById(R.id.rvHistory);
        //tvEmpty = view.findViewById(R.id.tvEmpty);
        ivNoDataBackground1 = view.findViewById(R.id.ivNoDataBackground1);
        tvNoData = view.findViewById(R.id.tvNoData);
        etStartDate = view.findViewById(R.id.etStartDate);
        spType = view.findViewById(R.id.spType);
        etEndDate = view.findViewById(R.id.etEndDate);
        startDateInputLayout = view.findViewById(R.id.startDateInputLayout);
        typeInputLayout = view.findViewById(R.id.typeInputLayout);
        endDateInputLayout = view.findViewById(R.id.endDateInputLayout);

        MaterialButton btnSearch = view.findViewById(R.id.btnSearch);
        //MaterialButton btnClear = view.findViewById(R.id.btnClear);

        // 初始化RecyclerView
        rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TaskRecordAdapter(getContext(), new ArrayList<>());
        rvHistory.setAdapter(adapter);

        // 初始化DataStore
        dataStoreManager = DataStoreManager.getInstance(requireContext());

        // 设置日期选择器
        setupDatePickers();

        // 设置任务类型下拉框
        setupTypeSpinner();

        // 设置结束日期为当前时间（包含时分）
        etEndDate.setText(sdf.format(new Date()));
        try {
            // 初始化结束时间戳
            Date endDate = sdf.parse(etEndDate.getText().toString());
            endTimestamp = endDate.getTime();
        } catch (ParseException e) {
            endTimestamp = System.currentTimeMillis();
        }

        // 修改适配器初始化，添加取消回调
        adapter = new TaskRecordAdapter(getContext(), new ArrayList<>(), new TaskRecordAdapter.OnTaskActionListener() {
            @Override
            public void onTaskCancelled(TaskRecord taskRecord) {
                Log.d("HistoryFragment", "收到取消任务请求，ID: " + taskRecord.getId());

                // 直接修改现有记录的状态
                taskRecord.setStatus(TaskRecord.STATUS_CANCELLED);
                taskRecord.setEndTime(System.currentTimeMillis());

                Log.d("HistoryFragment", "调用 updateTaskRecord");

                // 使用 ViewModel 的 updateTaskRecord 方法更新记录
                taskViewModel.updateTaskStatus(taskRecord.getId(), TaskRecord.STATUS_CANCELLED);

                Toast.makeText(requireContext(), R.string.task_history_task_cancelled, Toast.LENGTH_SHORT).show();
            }
        });
        rvHistory.setAdapter(adapter);


        // 查询按钮点击事件
        btnSearch.setOnClickListener(v -> loadRecords());

        // 添加清空按钮
        //btnClear.setOnClickListener(v -> showClearConfirmationDialog());

        // 加载初始数据
        taskViewModel.reconcileStaleRunningTaskRecords();
        loadRecords();

        // 观察任务记录变化
        taskViewModel.getTaskRecords().observe(getViewLifecycleOwner(), records -> {
            adapter.updateData(records); // 更新RecyclerView
        });

        return view;
    }

//    private void showClearConfirmationDialog() {
//        new AlertDialog.Builder(requireContext())
//                .setTitle(R.string.clear_records_title)
//                .setMessage(R.string.clear_records_message)
//                .setPositiveButton(R.string.confirm, (dialog, which) -> clearAllRecords())
//                .setNegativeButton(R.string.cancel, null)
//                .show();
//    }

    private void clearAllRecords() {
        taskViewModel.clearAllTaskRecords();
    }

    private void setupDatePickers() {
        // 结束日期选择器
        View.OnClickListener showEndDatePickerListener = v ->
                showDateTimePicker(etEndDate);
        etEndDate.setOnClickListener(showEndDatePickerListener);
        endDateInputLayout.setEndIconOnClickListener(showEndDatePickerListener);

        // 起始日期选择器
        View.OnClickListener showStartDatePickerListener = v ->
                showDateTimePicker(etStartDate);
        etStartDate.setOnClickListener(showStartDatePickerListener);
        startDateInputLayout.setEndIconOnClickListener(showStartDatePickerListener);

        // 设置结束日期为当前时间（包含时分）
        etEndDate.setText(sdf.format(new Date()));
    }

    private void showDateTimePicker(MaterialAutoCompleteTextView target) {
        // 1. 构建日期约束
        CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
        long minDate = 0;
        long maxDate = Long.MAX_VALUE;
        long currentTime = System.currentTimeMillis();

        if (target == etStartDate) {
            if (endTimestamp != null) {
                minDate = Math.max(endTimestamp - THIRTY_DAYS_IN_MILLIS, 0);
                maxDate = Math.min(endTimestamp, currentTime);
            } else {
                minDate = Math.max(currentTime - THIRTY_DAYS_IN_MILLIS, 0);
                maxDate = currentTime;
            }
        } else if (target == etEndDate) {
            if (startTimestamp != null) {
                minDate = startTimestamp;
                maxDate = Math.min(startTimestamp + THIRTY_DAYS_IN_MILLIS, currentTime);
            } else {
                minDate = Math.max(currentTime - THIRTY_DAYS_IN_MILLIS, 0);
                maxDate = currentTime;
            }
        }

        // 创建日期验证器
        long finalMinDate = minDate;
        long finalMaxDate = maxDate;
        CalendarConstraints.DateValidator dateValidator = new CalendarConstraints.DateValidator() {
            @Override
            public boolean isValid(long date) {
                return date >= finalMinDate && date <= finalMaxDate;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
            }
        };

        constraintsBuilder
                .setValidator(dateValidator)  // 关键：设置验证器使范围外日期变灰
                .setStart(minDate)
                .setEnd(maxDate);


        // 2. 创建日期选择器
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(getString(R.string.select_date_title))
                .setCalendarConstraints(constraintsBuilder.build())
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build();

        datePicker.addOnPositiveButtonClickListener(selection -> {
            // 自定义时间选择对话框（支持秒）
            View timePickerView = LayoutInflater.from(requireContext()).inflate(R.layout.custom_time_picker, null);
            @SuppressLint({"MissingInflatedId", "LocalSuppress"}) NumberPicker hourPicker = timePickerView.findViewById(R.id.hourPicker);
            @SuppressLint({"MissingInflatedId", "LocalSuppress"}) NumberPicker minutePicker = timePickerView.findViewById(R.id.minutePicker);
            @SuppressLint({"MissingInflatedId", "LocalSuppress"}) NumberPicker secondPicker = timePickerView.findViewById(R.id.secondPicker);

            // 设置时间范围
            hourPicker.setMinValue(0);
            hourPicker.setMaxValue(23);
            minutePicker.setMinValue(0);
            minutePicker.setMaxValue(59);
            secondPicker.setMinValue(0);
            secondPicker.setMaxValue(59);

            // 设置当前时间
            Calendar now = Calendar.getInstance();
            hourPicker.setValue(now.get(Calendar.HOUR_OF_DAY));
            minutePicker.setValue(now.get(Calendar.MINUTE));
            secondPicker.setValue(now.get(Calendar.SECOND));

            // 创建对话框
            new AlertDialog.Builder(requireContext())
                    .setView(timePickerView)
                    .setPositiveButton(R.string.confirm, (dialog, which) -> {
                        Calendar selectedCal = Calendar.getInstance();
                        selectedCal.setTimeInMillis(selection);
                        selectedCal.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
                        selectedCal.set(Calendar.MINUTE, minutePicker.getValue());
                        selectedCal.set(Calendar.SECOND, secondPicker.getValue());

                        // 验证时间范围
                        long selectedTimestamp = selectedCal.getTimeInMillis();
                        if (selectedTimestamp > currentTime) {
                            Toast.makeText(requireContext(), R.string.error_end_time_exceeds_current, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 交叉验证起始/结束时间
                        if (target == etStartDate && endTimestamp != null) {
                            if (selectedTimestamp > endTimestamp) {
                                Toast.makeText(requireContext(), R.string.error_start_after_end, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (endTimestamp - selectedTimestamp > THIRTY_DAYS_IN_MILLIS) {
                                Toast.makeText(requireContext(), R.string.error_date_range_exceeds, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        if (target == etEndDate && startTimestamp != null) {
                            if (selectedTimestamp < startTimestamp) {
                                Toast.makeText(requireContext(), R.string.error_start_after_end, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (selectedTimestamp - startTimestamp > THIRTY_DAYS_IN_MILLIS) {
                                Toast.makeText(requireContext(), R.string.error_date_range_exceeds, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        // 更新UI
                        target.setText(sdf.format(new Date(selectedTimestamp)));
                        if (target == etStartDate) {
                            startTimestamp = selectedTimestamp;
                        } else if (target == etEndDate) {
                            endTimestamp = selectedTimestamp;
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        datePicker.show(getParentFragmentManager(), "DATE_PICKER");
    }


    private void setupTypeSpinner() {
        String[] types = getResources().getStringArray(R.array.task_types);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, types);
        spType.setAdapter(typeAdapter);
        spType.setText(getString(R.string.all_tasks), false);

        // 设置下拉菜单的锚点
        spType.setDropDownAnchor(R.id.typeInputLayout);

        // 设置点击事件
        spType.setOnClickListener(v -> spType.showDropDown());
        typeInputLayout.setEndIconOnClickListener(v -> spType.showDropDown());
        typeInputLayout.setOnClickListener(v -> spType.showDropDown());
    }



    private void loadRecords() {
        String startDate = etStartDate.getText().toString();
        String endDate = etEndDate.getText().toString();
        String type = spType.getText().toString();

        // 使用新的日期时间格式验证
        if (!TextUtils.isEmpty(startDate) && !TextUtils.isEmpty(endDate)) {
            try {
                // 空值处理
                Date now = new Date();
                Date start = TextUtils.isEmpty(startDate) ? null : sdf.parse(startDate);
                Date end = TextUtils.isEmpty(endDate) ? null : sdf.parse(endDate);

                // === 新增验证逻辑 ===
                if (start != null && end != null) {
                    // 1. 检查起始时间不能大于结束时间
                    if (start.after(end)) {
                        Toast.makeText(getContext(), R.string.error_start_after_end, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 2. 检查时间间隔不超过30天
                    long diff = end.getTime() - start.getTime();
                    if (diff > THIRTY_DAYS_IN_MILLIS) {
                        Toast.makeText(getContext(), R.string.error_date_range_exceeds, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                // 3. 检查结束时间不超过当前时间
                if (end != null && end.after(now)) {
                    Toast.makeText(getContext(), R.string.error_end_time_exceeds_current, Toast.LENGTH_SHORT).show();
                    return;
                }


                if (start.after(end)) {
                    Toast.makeText(getContext(), R.string.error_start_after_end, Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
        taskViewModel.getTaskRecords().observe(getViewLifecycleOwner(), records -> {
            List<TaskRecord> filtered = filterRecords(records, startDate, endDate, type);
            adapter.updateData(filtered);
            //tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            ivNoDataBackground1.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            tvNoData.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            //tvEmpty.setText(filtered.isEmpty() ? getString(R.string.no_matching_records) : "");
        });
    }

    private List<TaskRecord> filterRecords(List<TaskRecord> records, String startDate, String endDate, String type) {
        List<TaskRecord> filtered = new ArrayList<>();

        try {
            long startTime = !TextUtils.isEmpty(startDate) ? sdf.parse(startDate).getTime() : 0;
            long endTime = !TextUtils.isEmpty(endDate) ? sdf.parse(endDate).getTime() : Long.MAX_VALUE;

            for (TaskRecord record : records) {
                // 日期过滤
                if ((startTime > 0 && record.getCreateTime() < startTime) ||
                        record.getCreateTime() > endTime) {
                    continue;
                }

                // 类型过滤
                if (!TextUtils.isEmpty(type) && !type.equals(getString(R.string.all_tasks))) {
                    if (!type.equals(getTypeString(record.getType()))) continue;
                }

                filtered.add(record);
            }
        } catch (ParseException e) {
            Log.e("FILTER", getString(R.string.error_date_parsing), e);
        }

        return filtered;
    }

    private String getTypeString(int type) {
        switch (type) {
            case TaskRecord.TYPE_CRUISE: return getString(R.string.cruise_task);
            case TaskRecord.TYPE_JACK: return getString(R.string.jack_task);
            case TaskRecord.TYPE_DELIVERY: return getString(R.string.delivery_task);
            case TaskRecord.TYPE_CHARGE: return getString(R.string.charge_task);
            case TaskRecord.TYPE_PARKING: return getString(R.string.parking_task);
            case TaskRecord.TYPE_STOP_CHARGE: return getString(R.string.stop_charge_task);
            case TaskRecord.TYPE_CALL: return getString(R.string.call_task);
            default: return getString(R.string.unknown_task);
        }
    }
}
