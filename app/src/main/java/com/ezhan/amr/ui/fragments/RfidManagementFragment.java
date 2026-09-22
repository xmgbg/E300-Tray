package com.ezhan.amr.ui.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.communication.rfid.RFIDCommunicator;
import com.ezhan.amr.data.datatype.RfidData;
import com.ezhan.amr.data.datatype.User;
import com.ezhan.amr.viewmodels.RfidViewModel;
import com.ezhan.amr.viewmodels.UserViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RfidManagementFragment extends Fragment {
    private static final String TAG = "RfidManagement";

    private RfidViewModel rfidViewModel;
    private UserViewModel userViewModel;
    private RecyclerView rfidRecyclerView;
    private RfidAdapter rfidAdapter;
    private List<RfidData> rfidList;
    private List<User> userList;
    private EditText searchEditText;
    private Spinner userSpinner, typeSpinner, statusSpinner;
    private RFIDCommunicator rfidCommunicator;
    private RFIDCommunicator.RFIDDataListener rfidDataListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rfid_management, container, false);

        rfidViewModel = new ViewModelProvider(this).get(RfidViewModel.class);
        userViewModel = new ViewModelProvider(this).get(UserViewModel.class);

        rfidRecyclerView = view.findViewById(R.id.rfid_recycler_view);
        rfidRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        rfidAdapter = new RfidAdapter(new ArrayList<>());
        rfidRecyclerView.setAdapter(rfidAdapter);

        searchEditText = view.findViewById(R.id.search_edit_text);
        userSpinner = view.findViewById(R.id.user_spinner);
        typeSpinner = view.findViewById(R.id.type_spinner);
        statusSpinner = view.findViewById(R.id.status_spinner);

        setupFilterSpinners();
        setupObservers();

        Button addRfidButton = view.findViewById(R.id.add_rfid_button);
        addRfidButton.setOnClickListener(v -> showAddRfidDialog());

        initRfidCommunicator();
        return view;
    }

    private void setupFilterSpinners() {
        ArrayAdapter<String> userAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.rfid_filter_all_users), "user001", "user002", "user003"});
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userSpinner.setAdapter(userAdapter);

        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.rfid_filter_all_types),
                        getString(R.string.rfid_card_type_operation),
                        getString(R.string.rfid_card_type_management)
                });
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);

        ArrayAdapter<String> statusAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.rfid_filter_all_statuses),
                        getString(R.string.rfid_status_normal),
                        getString(R.string.rfid_status_lost_disabled)
                });
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusSpinner.setAdapter(statusAdapter);
    }

    private void setupObservers() {
        rfidViewModel.getRfidList().observe(getViewLifecycleOwner(), rfids -> {
            rfidList = rfids;
            rfidAdapter.setRfidList(rfids);
        });

        userViewModel.getUserList().observe(getViewLifecycleOwner(), users -> {
            userList = users;
        });
    }

    private void initRfidCommunicator() {
        try {
            rfidCommunicator = MyApplication.getInstance().getRfidCommunicator();
            rfidCommunicator.startListening();

            rfidDataListener = rfidData -> {
                Log.d(TAG, "RFID data received: " + rfidData);
                if (rfidData.length() >= 24) {
                    String cardSerial = rfidData.substring(16, 24);
                    Log.d(TAG, "RFID card serial parsed: " + cardSerial);
                }
            };
            rfidCommunicator.addRfidDataListener(rfidDataListener);

            Log.i(TAG, "RFID communication initialized and listening");
        } catch (IOException e) {
            Log.e(TAG, "RFID communication initialization failed", e);
        }
    }

    private void showAddRfidDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.rfid_add_card_title);

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_rfid_edit, null);
        builder.setView(dialogView);

        EditText rfidSerialEditText = dialogView.findViewById(R.id.rfid_serial_edit_text);
        EditText userIdEditText = dialogView.findViewById(R.id.user_id_edit_text);
        Spinner cardTypeSpinner = dialogView.findViewById(R.id.card_type_spinner);
        Spinner statusSpinner = dialogView.findViewById(R.id.status_spinner);
        Button readRfidButton = dialogView.findViewById(R.id.read_rfid_button);

        setupCardTypeSpinner(cardTypeSpinner);
        setupStatusSpinner(statusSpinner);
        setupReadRfidButton(readRfidButton, rfidSerialEditText);

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String rfidSerial = rfidSerialEditText.getText().toString().trim();
            String userId = userIdEditText.getText().toString().trim();
            int cardType = cardTypeSpinner.getSelectedItemPosition() + 1;
            int status = statusSpinner.getSelectedItemPosition() == 0 ? 1 : 0;

            if (TextUtils.isEmpty(rfidSerial) || TextUtils.isEmpty(userId)) {
                showAlert(getString(R.string.rfid_error_title), getString(R.string.rfid_required_error));
                return;
            }

            RfidData rfid = new RfidData(0, rfidSerial, userId, cardType, status);
            if (rfidViewModel.addRfid(rfid)) {
                showAlert(getString(R.string.rfid_success_title), getString(R.string.rfid_add_success));
            } else {
                showAlert(getString(R.string.rfid_error_title), getString(R.string.rfid_duplicate_error));
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void showEditRfidDialog(RfidData rfid) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.rfid_edit_card_title);

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_rfid_edit, null);
        builder.setView(dialogView);

        EditText rfidSerialEditText = dialogView.findViewById(R.id.rfid_serial_edit_text);
        EditText userIdEditText = dialogView.findViewById(R.id.user_id_edit_text);
        Spinner cardTypeSpinner = dialogView.findViewById(R.id.card_type_spinner);
        Spinner statusSpinner = dialogView.findViewById(R.id.status_spinner);
        Button readRfidButton = dialogView.findViewById(R.id.read_rfid_button);

        setupCardTypeSpinner(cardTypeSpinner);
        setupStatusSpinner(statusSpinner);

        rfidSerialEditText.setText(rfid.getRfidSerial());
        userIdEditText.setText(rfid.getUserId());
        cardTypeSpinner.setSelection(Math.max(0, rfid.getCardType() - 1));
        statusSpinner.setSelection(rfid.getStatus() == 1 ? 0 : 1);

        setupReadRfidButton(readRfidButton, rfidSerialEditText);

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String rfidSerial = rfidSerialEditText.getText().toString().trim();
            String userId = userIdEditText.getText().toString().trim();
            int cardType = cardTypeSpinner.getSelectedItemPosition() + 1;
            int status = statusSpinner.getSelectedItemPosition() == 0 ? 1 : 0;

            if (TextUtils.isEmpty(rfidSerial) || TextUtils.isEmpty(userId)) {
                showAlert(getString(R.string.rfid_error_title), getString(R.string.rfid_required_error));
                return;
            }

            RfidData updatedRfid = new RfidData(rfid.getId(), rfidSerial, userId, cardType, status);
            if (rfidViewModel.updateRfid(updatedRfid)) {
                showAlert(getString(R.string.rfid_success_title), getString(R.string.rfid_edit_success));
            } else {
                showAlert(getString(R.string.rfid_error_title), getString(R.string.rfid_duplicate_error));
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void showDeleteConfirmDialog(RfidData rfid) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.confirm_delete);
        builder.setMessage(getString(R.string.rfid_confirm_delete_message, rfid.getRfidSerial()));

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            if (rfidViewModel.deleteRfid(rfid.getId())) {
                showAlert(getString(R.string.rfid_success_title), getString(R.string.rfid_delete_success));
            } else {
                showAlert(getString(R.string.rfid_error_title), getString(R.string.rfid_delete_not_found));
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void setupCardTypeSpinner(Spinner spinner) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.rfid_card_type_operation),
                        getString(R.string.rfid_card_type_management)
                });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setupStatusSpinner(Spinner spinner) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.rfid_status_normal),
                        getString(R.string.rfid_status_lost_disabled)
                });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setupReadRfidButton(Button button, EditText targetEditText) {
        button.setOnClickListener(v -> {
            if (rfidCommunicator != null) {
                new Thread(() -> {
                    String rfidId = readRfidSerial();
                    if (rfidId != null) {
                        requireActivity().runOnUiThread(() -> targetEditText.setText(rfidId));
                    }
                }).start();
            }
        });
    }

    private String readRfidSerial() {
        Log.d(TAG, "Reading RFID serial...");
        String tagData = rfidCommunicator.readRfidWithTimeout();

        if (tagData != null && !tagData.isEmpty()) {
            Log.d(TAG, "RFID data read: " + tagData);
            if (tagData.length() >= 24) {
                String cardSerial = tagData.substring(16, 24);
                Log.d(TAG, "RFID card serial parsed: " + cardSerial);
                return cardSerial;
            } else {
                Log.w(TAG, "RFID data length insufficient: " + tagData.length());
                return tagData;
            }
        }

        Log.w(TAG, "No RFID data read within timeout");
        return null;
    }

    private void showAlert(String title, String message) {
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private String getLocalizedStatus(int status) {
        return status == 1 ? getString(R.string.rfid_status_normal) : getString(R.string.rfid_status_lost_disabled);
    }

    private String getLocalizedCardType(int cardType) {
        switch (cardType) {
            case 1:
                return getString(R.string.rfid_card_type_operation);
            case 2:
                return getString(R.string.rfid_card_type_management);
            default:
                return getString(R.string.door_lock_status_unknown);
        }
    }

    private class RfidAdapter extends RecyclerView.Adapter<RfidAdapter.RfidViewHolder> {
        private List<RfidData> rfidList;

        RfidAdapter(List<RfidData> rfidList) {
            this.rfidList = rfidList;
        }

        void setRfidList(List<RfidData> rfidList) {
            this.rfidList = rfidList;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RfidViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_rfid, parent, false);
            return new RfidViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RfidViewHolder holder, int position) {
            holder.bind(rfidList.get(position));
        }

        @Override
        public int getItemCount() {
            return rfidList == null ? 0 : rfidList.size();
        }

        class RfidViewHolder extends RecyclerView.ViewHolder {
            private final TextView idTextView;
            private final TextView serialTextView;
            private final TextView nameTextView;
            private final TextView userIdTextView;
            private final TextView statusTextView;
            private final TextView typeTextView;
            private final Button editButton;
            private final Button deleteButton;

            RfidViewHolder(@NonNull View itemView) {
                super(itemView);
                idTextView = itemView.findViewById(R.id.rfid_id_text);
                serialTextView = itemView.findViewById(R.id.rfid_serial_text);
                nameTextView = itemView.findViewById(R.id.rfid_name_text);
                userIdTextView = itemView.findViewById(R.id.rfid_user_id_text);
                statusTextView = itemView.findViewById(R.id.rfid_status_text);
                typeTextView = itemView.findViewById(R.id.rfid_type_text);
                editButton = itemView.findViewById(R.id.edit_button);
                deleteButton = itemView.findViewById(R.id.delete_button);
            }

            void bind(RfidData rfid) {
                idTextView.setText(String.valueOf(rfid.getId()));
                serialTextView.setText(rfid.getRfidSerial());
                nameTextView.setText(rfid.getUserId());
                userIdTextView.setText(rfid.getUserId());
                statusTextView.setText(getLocalizedStatus(rfid.getStatus()));
                typeTextView.setText(getLocalizedCardType(rfid.getCardType()));

                editButton.setOnClickListener(v -> showEditRfidDialog(rfid));
                deleteButton.setOnClickListener(v -> showDeleteConfirmDialog(rfid));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rfidCommunicator != null && rfidDataListener != null) {
            rfidCommunicator.removeRfidDataListener(rfidDataListener);
            Log.i(TAG, "RFID data listener removed");
        }
    }
}
