package com.ezhan.amr.ui.fragments;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.CruiseTask;
import com.ezhan.amr.data.datatype.JackTask;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.viewmodels.CallboxViewModel;
import com.ezhan.amr.viewmodels.MapViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.ezhan.amr.viewmodels.CallboxViewModel.CallBox;
import com.ezhan.amr.viewmodels.CallboxViewModel.CallButton;

public class CallSettingsFragment extends Fragment {
    private CallboxViewModel callboxViewModel;
    private MapViewModel mapViewModel;
    private CallBoxAdapter adapter;
    private MaterialButton btnAddCallBox;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_call_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        callboxViewModel = new ViewModelProvider(requireActivity()).get(CallboxViewModel.class);
        mapViewModel = MyApplication.getInstance().getMapViewModel();
        btnAddCallBox = view.findViewById(R.id.btnAddCallBox);
        RecyclerView rvCallBoxes = view.findViewById(R.id.rvCallBoxes);

        adapter = new CallBoxAdapter();
        rvCallBoxes.setAdapter(adapter);

        btnAddCallBox.setOnClickListener(v -> showAddCallBoxDialog());

        callboxViewModel.getCallBoxes().observe(getViewLifecycleOwner(), callBoxes -> {
            adapter.submitList(new ArrayList<>(callBoxes));
        });
    }

    private void showAddCallBoxDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_call_box, null);

        EditText etName = dialogView.findViewById(R.id.etName);
        EditText etButtonCount = dialogView.findViewById(R.id.etButtonCount);
        EditText etChannel = dialogView.findViewById(R.id.etChannel);
        EditText etAddress = dialogView.findViewById(R.id.etAddress);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancel);
        MaterialButton btnConfirm = dialogView.findViewById(R.id.btnConfirm);

        AlertDialog dialog = builder.setView(dialogView).create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String buttonCountStr = etButtonCount.getText().toString().trim();
            String channelStr = etChannel.getText().toString().trim();
            String addressStr = etAddress.getText().toString().trim();

            if (name.isEmpty() || buttonCountStr.isEmpty() || channelStr.isEmpty() || addressStr.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.error_fill_complete_info), Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                int buttonCount = Integer.parseInt(buttonCountStr);
                int channel = Integer.parseInt(channelStr);
                int address = Integer.parseInt(addressStr);

                if (buttonCount <= 0) {
                    Toast.makeText(requireContext(), getString(R.string.error_button_count_zero), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (buttonCount > 20) {
                    Toast.makeText(requireContext(), getString(R.string.error_button_count_max), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (address < 0 || address > 255) {
                    Toast.makeText(requireContext(), getString(R.string.error_address_range), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (channel < 1 || channel > 125) {
                    Toast.makeText(requireContext(), getString(R.string.error_channel_range), Toast.LENGTH_SHORT).show();
                    return;
                }

                // 检查是否存在同名呼叫盒
                List<CallBox> existingCallBoxes = callboxViewModel.getCallBoxes().getValue();
                if (existingCallBoxes != null) {
                    for (CallBox existing : existingCallBoxes) {
                        if (existing.getName().equals(name)) {
                            Toast.makeText(requireContext(), getString(R.string.add_failed_duplicate_callbox_name, name), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                }

                CallBox callBox = new CallBox();
                callBox.setName(name);
                callBox.setChannel(channel);
                callBox.setAddress(address);

                for (int i = 1; i <= buttonCount; i++) {
                    CallButton button = new CallButton();
                    button.setName(getString(R.string.button_default_name_format, i));
                    callBox.getButtons().add(button);
                }

                callboxViewModel.addCallBox(callBox);
                dialog.dismiss();
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }

    class CallBoxAdapter extends ListAdapter<CallBox, CallBoxViewHolder> {
        protected CallBoxAdapter() {
            super(new DiffUtil.ItemCallback<CallBox>() {
                @Override
                public boolean areItemsTheSame(@NonNull CallBox oldItem, @NonNull CallBox newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull CallBox oldItem, @NonNull CallBox newItem) {
                    if (!oldItem.getName().equals(newItem.getName()) ||
                            oldItem.getChannel() != newItem.getChannel() ||
                            oldItem.getAddress() != newItem.getAddress() ||
                            oldItem.getButtons().size() != newItem.getButtons().size()) {
                        return false;
                    }

                    for (int i = 0; i < oldItem.getButtons().size(); i++) {
                        CallButton oldBtn = oldItem.getButtons().get(i);
                        CallButton newBtn = newItem.getButtons().get(i);
                        if (!Objects.equals(oldBtn.getName(), newBtn.getName()) ||
                                !Objects.equals(oldBtn.getPosition(), newBtn.getPosition()) ||
                                !Objects.equals(oldBtn.getTask(), newBtn.getTask())) {
                            return false;
                        }
                    }
                    return true;
                }
            });
        }

        @NonNull
        @Override
        public CallBoxViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_call_box, parent, false);
            return new CallBoxViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CallBoxViewHolder holder, int position) {
            if (position >= 0 && position < getItemCount()) {
                holder.bind(getItem(position));
            }
        }
    }

    class CallBoxViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvName, tvInfo;
        private final MaterialButton btnSettings;
        private final RecyclerView rvButtons;
        private CallButtonAdapter buttonAdapter;

        public CallBoxViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvInfo = itemView.findViewById(R.id.tvInfo);
            btnSettings = itemView.findViewById(R.id.btnSettings);
            rvButtons = itemView.findViewById(R.id.rvButtons);

            buttonAdapter = new CallButtonAdapter();
            rvButtons.setAdapter(buttonAdapter);
        }

        public void bind(CallBox callBox) {
            tvName.setText(callBox.getName());
            tvInfo.setText(getString(R.string.call_box_info_format,
                    callBox.getChannel(), callBox.getAddress(), callBox.getButtons().size()));

            buttonAdapter.submitList(new ArrayList<>(callBox.getButtons()));

            btnSettings.setOnClickListener(v -> showCallBoxSettingsDialog(callBox));
        }

        private void showCallBoxSettingsDialog(CallBox callBox) {
            AlertDialog.Builder builder = new AlertDialog.Builder(itemView.getContext());
            View dialogView = LayoutInflater.from(itemView.getContext())
                    .inflate(R.layout.dialog_call_box_settings, null);

            EditText etName = dialogView.findViewById(R.id.etName);
            EditText etChannel = dialogView.findViewById(R.id.etChannel);
            EditText etAddress = dialogView.findViewById(R.id.etAddress);
            MaterialButton btnDelete = dialogView.findViewById(R.id.btnDelete);
            MaterialButton btnConfirm = dialogView.findViewById(R.id.btnConfirm);

            etName.setText(callBox.getName());
            etChannel.setText(String.valueOf(callBox.getChannel()));
            etAddress.setText(String.valueOf(callBox.getAddress()));

            AlertDialog dialog = builder.setView(dialogView).create();

            btnConfirm.setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String channelStr = etChannel.getText().toString().trim();
                String addressStr = etAddress.getText().toString().trim();

                if (name.isEmpty() || channelStr.isEmpty() || addressStr.isEmpty()) {
                    Toast.makeText(itemView.getContext(), getString(R.string.error_fill_complete_info), Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    int channel = Integer.parseInt(channelStr);
                    int address = Integer.parseInt(addressStr);

                    if (channel < 1 || channel > 125) {
                        Toast.makeText(itemView.getContext(), getString(R.string.error_channel_range), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (address < 0 || address > 255) {
                        Toast.makeText(itemView.getContext(), getString(R.string.error_address_range), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 检查是否存在同名呼叫盒（排除当前正在编辑的呼叫盒）
                    List<CallBox> existingCallBoxes = callboxViewModel.getCallBoxes().getValue();
                    if (existingCallBoxes != null) {
                        for (CallBox existing : existingCallBoxes) {
                            if (!existing.getId().equals(callBox.getId()) && existing.getName().equals(name)) {
                                Toast.makeText(itemView.getContext(), getString(R.string.add_failed_duplicate_callbox_name, name), Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                    }

                    callBox.setName(name);
                    callBox.setChannel(channel);
                    callBox.setAddress(address);

                    callboxViewModel.updateCallBox(callBox);
                    dialog.dismiss();
                } catch (NumberFormatException e) {
                    Toast.makeText(itemView.getContext(), getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show();
                }
            });

            btnDelete.setOnClickListener(v -> {
                View confirmDialogView = LayoutInflater.from(itemView.getContext())
                        .inflate(R.layout.dialog_confirm_delete, null);

                AlertDialog confirmDialog = new AlertDialog.Builder(itemView.getContext())
                        .setView(confirmDialogView)
                        .create();

                MaterialButton positiveButton = confirmDialogView.findViewById(R.id.positive_button);
                MaterialButton negativeButton = confirmDialogView.findViewById(R.id.negative_button);

                TextView messageView = confirmDialogView.findViewById(R.id.dialog_message);
                messageView.setText(getString(R.string.confirm_delete_call_box_message));

                positiveButton.setOnClickListener(v1 -> {
                    callboxViewModel.deleteCallBox(callBox.getId());
                    dialog.dismiss();
                    confirmDialog.dismiss();
                });

                negativeButton.setOnClickListener(v1 -> {
                    confirmDialog.dismiss();
                });

                confirmDialog.show();
            });

            dialog.show();
        }
    }

    class CallButtonAdapter extends ListAdapter<CallButton, CallButtonViewHolder> {
        protected CallButtonAdapter() {
            super(new DiffUtil.ItemCallback<CallButton>() {
                @Override
                public boolean areItemsTheSame(@NonNull CallButton oldItem, @NonNull CallButton newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull CallButton oldItem, @NonNull CallButton newItem) {
                    return Objects.equals(oldItem.getName(), newItem.getName()) &&
                            Objects.equals(oldItem.getPosition(), newItem.getPosition()) &&
                            Objects.equals(oldItem.getTask(), newItem.getTask());
                }

                @Nullable
                @Override
                public Object getChangePayload(@NonNull CallButton oldItem, @NonNull CallButton newItem) {
                    Bundle payload = new Bundle();
                    if (!Objects.equals(oldItem.getName(), newItem.getName())) {
                        payload.putString("name", newItem.getName());
                    }
                    if (!Objects.equals(oldItem.getPosition(), newItem.getPosition())) {
                        payload.putString("position", newItem.getPosition());
                    }
                    if (!Objects.equals(oldItem.getTask(), newItem.getTask())) {
                        payload.putString("task", newItem.getTask());
                    }
                    return payload.isEmpty() ? null : payload;
                }
            });
        }

        @NonNull
        @Override
        public CallButtonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_call_button, parent, false);
            return new CallButtonViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CallButtonViewHolder holder, int position) {
            holder.bind(getItem(position), getCurrentList());
        }
    }

    class CallButtonViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvButtonName, tvPosition, tvTask;
        private final MaterialButton btnConfig;

        // 楼层站点适配器
        private class FloorPositionAdapter extends ArrayAdapter<String> {
            private final Map<Integer, List<Position>> floorMap;
            private final List<String> displayItems = new ArrayList<>();
            private final List<Position> allPositions = new ArrayList<>();

            public FloorPositionAdapter(Context context, Map<Integer, List<Position>> floorMap) {
                super(context, android.R.layout.simple_list_item_1);
                this.floorMap = floorMap;
                prepareDisplayItems();
            }

            private void prepareDisplayItems() {
                List<Integer> floors = new ArrayList<>(floorMap.keySet());
                Collections.sort(floors);

                for (int floor : floors) {
                    List<Position> positions = floorMap.get(floor);
                    for (Position pos : positions) {
                        displayItems.add(pos.getName());
                        allPositions.add(pos);
                    }
                }
            }

            @Override
            public int getCount() {
                return displayItems.size();
            }

            @Override
            public String getItem(int position) {
                return displayItems.get(position);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return super.getView(position, convertView, parent);
            }

            @NonNull
            @Override
            public Filter getFilter() {
                return new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        results.values = displayItems;
                        results.count = displayItems.size();
                        return results;
                    }

                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {
                        notifyDataSetChanged();
                    }
                };
            }

            public Position getPosition(int position) {
                return allPositions.get(position);
            }
        }

        public CallButtonViewHolder(@NonNull View itemView) {
            super(itemView);
            tvButtonName = itemView.findViewById(R.id.tvButtonName);
            tvPosition = itemView.findViewById(R.id.tvPosition);
            tvTask = itemView.findViewById(R.id.tvTask);
            btnConfig = itemView.findViewById(R.id.btnConfig);
        }

        public void bind(CallButton callButton, List<CallButton> buttons) {
            tvButtonName.setText(callButton.getName());
            tvPosition.setText(callButton.getPosition() != null ? callButton.getPosition() : getString(R.string.not_configured));
            tvTask.setText(callButton.getTask() != null ? callButton.getTask() : getString(R.string.not_configured));

            btnConfig.setOnClickListener(v -> showButtonConfigDialog(callButton));
        }

        private void showButtonConfigDialog(CallButton callButton) {
            AlertDialog.Builder builder = new AlertDialog.Builder(itemView.getContext());
            View dialogView = LayoutInflater.from(itemView.getContext())
                    .inflate(R.layout.dialog_add_call_button, null);

            // 初始化视图
            TextView tvButtonName = dialogView.findViewById(R.id.tvButtonName);
            AutoCompleteTextView actvTaskType = dialogView.findViewById(R.id.actvTaskType);
            AutoCompleteTextView actvTarget = dialogView.findViewById(R.id.actvTarget);
            TextView tvTargetLabel = dialogView.findViewById(R.id.tvTargetLabel);
            MaterialButton btnDelete = dialogView.findViewById(R.id.btnDelete);
            MaterialButton btnSave = dialogView.findViewById(R.id.btnConfirm);
            MaterialButton btnAdd = dialogView.findViewById(R.id.btnAdd);

            // 设置初始值
            tvButtonName.setText(callButton.getName());

            // 任务类型选项
            final String TYPE_STATION = getString(R.string.task_type_station);
            final String TYPE_TASK = getString(R.string.task_type_task);
            String[] taskTypes = {TYPE_STATION, TYPE_TASK};
            // 使用无过滤的适配器，避免当前选中文本过滤掉其他选项
            ArrayAdapter<String> taskTypeAdapter = new ArrayAdapter<String>(
                    itemView.getContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    taskTypes
            ) {
                @NonNull
                @Override
                public Filter getFilter() {
                    return new Filter() {
                        @Override
                        protected FilterResults performFiltering(CharSequence constraint) {
                            FilterResults results = new FilterResults();
                            results.values = taskTypes;
                            results.count = taskTypes.length;
                            return results;
                        }

                        @Override
                        protected void publishResults(CharSequence constraint, FilterResults results) {
                            notifyDataSetChanged();
                        }
                    };
                }
            };
            actvTaskType.setAdapter(taskTypeAdapter);

            // 判断当前配置的类型
            String currentTaskType;
            if (callButton.getPosition() != null && !callButton.getPosition().isEmpty()) {
                currentTaskType = TYPE_STATION;
            } else if (callButton.getTask() != null && !callButton.getTask().isEmpty()) {
                currentTaskType = TYPE_TASK;
            } else {
                currentTaskType = TYPE_STATION;
            }
            actvTaskType.setText(currentTaskType);

            // 任务类型选择监听
            actvTaskType.setOnItemClickListener((parent, view, position, id) -> {
                String selectedType = taskTypes[position];
                updateTargetDropdown(selectedType, actvTarget, tvTargetLabel);
            });

            // 初始化目标下拉框
            updateTargetDropdown(currentTaskType, actvTarget, tvTargetLabel);

            // 设置当前选中的目标
            if (currentTaskType.equals(TYPE_STATION)) {
                actvTarget.setText(callButton.getPosition() != null ? callButton.getPosition() : "");
            } else {
                String currentTask = callButton.getTask();
                if (currentTask != null && !currentTask.isEmpty()) {
                    if (!currentTask.startsWith(getString(R.string.cruise_task_prefix)) && !currentTask.startsWith(getString(R.string.jack_task_prefix))) {
                        currentTask = getString(R.string.cruise_task_prefix) + currentTask;
                    }
                    actvTarget.setText(currentTask);
                }
            }

            AlertDialog dialog = builder.setView(dialogView).create();

            btnSave.setOnClickListener(v -> {
                String selectedType = actvTaskType.getText().toString().trim();
                String target = actvTarget.getText().toString().trim();

                // 只计算数据，不修改原 callButton 对象（避免 DiffUtil 误判无变化）
                String position = selectedType.equals(TYPE_STATION) ? target : "";
                String task = selectedType.equals(TYPE_STATION) ? "" : target;

                CallBox parentCallBox = findParentCallBox(callButton.getId());
                if (parentCallBox != null) {
                    callboxViewModel.updateButtonInCallBox(parentCallBox.getId(), callButton.getId(), position, task);
                }
                dialog.dismiss();
            });

            btnDelete.setOnClickListener(v -> {
                View confirmDialogView = LayoutInflater.from(itemView.getContext())
                        .inflate(R.layout.dialog_confirm_delete, null);

                AlertDialog confirmDialog = new AlertDialog.Builder(itemView.getContext())
                        .setView(confirmDialogView)
                        .create();

                MaterialButton positiveButton = confirmDialogView.findViewById(R.id.positive_button);
                MaterialButton negativeButton = confirmDialogView.findViewById(R.id.negative_button);

                TextView messageView = confirmDialogView.findViewById(R.id.dialog_message);
                messageView.setText(getString(R.string.confirm_delete_button_message));

                positiveButton.setOnClickListener(v1 -> {
                    CallBox parentCallBox = findParentCallBox(callButton.getId());
                    if (parentCallBox != null) {
                        callboxViewModel.deleteButtonFromCallBox(parentCallBox.getId(), callButton.getId());
                    }
                    dialog.dismiss();
                    confirmDialog.dismiss();
                });

                negativeButton.setOnClickListener(v1 -> {
                    confirmDialog.dismiss();
                });

                confirmDialog.show();
            });

            btnAdd.setOnClickListener(v -> {
                CallBox parentCallBox = findParentCallBox(callButton.getId());
                if (parentCallBox != null) {
                    if (parentCallBox.getButtons().size() >= 20) {
                        Toast.makeText(itemView.getContext(), getString(R.string.error_button_count_max), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CallButton newButton = new CallButton();
                    newButton.setName(getString(R.string.button_default_name_format, parentCallBox.getButtons().size() + 1));
                    callboxViewModel.addButtonToCallBox(parentCallBox.getId(), newButton);
                }
                dialog.dismiss();
            });

            dialog.show();
        }

        private void updateTargetDropdown(String taskType, AutoCompleteTextView actvTarget, TextView tvTargetLabel) {
            Context context = itemView.getContext();
            final String TYPE_STATION = context.getString(R.string.task_type_station);

            if (taskType.equals(TYPE_STATION)) {
                tvTargetLabel.setText(R.string.select_station);
                actvTarget.setHint(R.string.select_station_hint);
                actvTarget.setText("");
                Map<Integer, List<Position>> floorMap = mapViewModel.getAllFloorPositionsFromMapPoints();
                if (floorMap != null && !floorMap.isEmpty()) {
                    FloorPositionAdapter positionAdapter = new FloorPositionAdapter(context, floorMap);
                    actvTarget.setAdapter(positionAdapter);
                    actvTarget.setOnItemClickListener((parent, view, position, id) -> {
                        Position selectedPos = positionAdapter.getPosition(position);
                        if (selectedPos != null) {
                            actvTarget.setText(selectedPos.getName());
                        }
                    });
                    actvTarget.setEnabled(true);
                } else {
                    actvTarget.setHint(R.string.no_available_stations);
                    actvTarget.setEnabled(false);
                }
            } else {
                tvTargetLabel.setText(R.string.select_task);
                actvTarget.setHint(R.string.select_task);
                actvTarget.setText("");
                Map<Integer, JackTask> jackTaskMap = DataStoreManager.getInstance(context)
                        .getJackTaskMap()
                        .blockingFirst(Collections.emptyMap());

                List<String> taskNames = new ArrayList<>();
                String jackPrefix = getString(R.string.jack_task_prefix);
                for (JackTask task : jackTaskMap.values()) {
                    // 优先显示任务名称，如果名称为空则显示任务ID
                    String displayName = task.getTaskName();
                    if (displayName == null || displayName.trim().isEmpty()) {
                        displayName = String.valueOf(task.getTaskId());
                    }
                    taskNames.add(jackPrefix + displayName);
                }

                if (!taskNames.isEmpty()) {
                    ArrayAdapter<String> taskAdapter = new ArrayAdapter<String>(
                            context,
                            android.R.layout.simple_dropdown_item_1line,
                            taskNames
                    ) {
                        @NonNull
                        @Override
                        public Filter getFilter() {
                            return new Filter() {
                                @Override
                                protected FilterResults performFiltering(CharSequence constraint) {
                                    FilterResults results = new FilterResults();
                                    results.values = taskNames;
                                    results.count = taskNames.size();
                                    return results;
                                }

                                @Override
                                protected void publishResults(CharSequence constraint, FilterResults results) {
                                    notifyDataSetChanged();
                                }
                            };
                        }
                    };
                    actvTarget.setAdapter(taskAdapter);
                    actvTarget.setOnItemClickListener((parent, view, position, id) -> {
                        String selectedTask = taskAdapter.getItem(position);
                        if (selectedTask != null) {
                            actvTarget.setText(selectedTask);
                        }
                    });
                } else {
                    actvTarget.setHint(R.string.no_available_tasks);
                    actvTarget.setEnabled(false);
                }
            }
        }

        private CallBox findParentCallBox(String buttonId) {
            List<CallBox> callBoxes = callboxViewModel.getCallBoxes().getValue();
            if (callBoxes != null) {
                for (CallBox callBox : callBoxes) {
                    for (CallButton button : callBox.getButtons()) {
                        if (button.getId().equals(buttonId)) {
                            return callBox;
                        }
                    }
                }
            }
            return null;
        }
    }
}
