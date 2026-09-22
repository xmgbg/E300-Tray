package com.ezhan.amr.viewmodels;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.communication.chassis.CommandWebSocketClient;
import com.ezhan.amr.communication.chassis.StatusWebSocketClient;
import com.ezhan.amr.communication.lora.LoraCommunicator;
import com.ezhan.amr.data.datastore.DataStoreManager;
import com.ezhan.amr.data.datatype.AgvStatusResponse;
import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.data.datatype.User;
import com.ezhan.amr.navigation.GeneralNavigationHandler;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

import java.util.HashMap;
import java.util.Map;

public class SharedViewModel extends AndroidViewModel {
    private final String TAG = "ShareViewModel";
    private final DataStoreManager dataStoreManager;
    private final CompositeDisposable disposables = new CompositeDisposable();
    public LoraCommunicator loraCommunicator;
    private GeneralNavigationHandler navigationHandler;
    private final Context context;

    // REPLACE UnifiedWebSocketClient with separate clients
    private StatusWebSocketClient statusClient;
    private CommandWebSocketClient commandClient;
    private final String host = "192.0.2.10";
    private final int port = 6060;
    private MutableLiveData<User> currentTaskCreator = new MutableLiveData<>();
    private MutableLiveData<Boolean> isTaskCreatorSet = new MutableLiveData<>(false);
    private final MutableLiveData<Position> pendingVerificationTarget = new MutableLiveData<>();
    private final MutableLiveData<Integer> pendingVerificationWaypointIndex = new MutableLiveData<>();
    private final MutableLiveData<String> pendingVerificationOperation = new MutableLiveData<>();
    private final MutableLiveData<Position> currentTaskTarget = new MutableLiveData<>();
    private final MutableLiveData<Integer> currentEffectiveIndex = new MutableLiveData<>(-1);
    private final MutableLiveData<Integer> currentWaypointIndex = new MutableLiveData<>(-1);
    private final MutableLiveData<String> selectedUserId = new MutableLiveData<>();
    private final MutableLiveData<String> selectedUserName = new MutableLiveData<>();
    private final MutableLiveData<String> inUseElevator = new MutableLiveData<>();
    private final MutableLiveData<Map<String, Boolean>> elevatorAvailability = new MutableLiveData<>();

    public SharedViewModel(@NonNull Application application) throws Exception {
        super(application);
        this.context = MyApplication.getAppContext();
        dataStoreManager = DataStoreManager.getInstance(application);
        loraCommunicator = MyApplication.getInstance().getLoraCommunicator();
    }

    @Override
    public void onCleared() {
        super.onCleared();
        if (loraCommunicator != null) {
            loraCommunicator.setPacketListener(null);
        }
        disposables.dispose();

        // Disconnect both clients
        if (statusClient != null) {
            statusClient.disconnect();
        }
        if (commandClient != null) {
            commandClient.disconnect();
        }
    }

    public void initNavigationHandler() {
        if (navigationHandler == null) {
            navigationHandler = new GeneralNavigationHandler(this);
        }
    }

    public void initializeWebSocket() {
        Log.d(TAG, "initializeWebSocket() called - setting up Status and Command clients");

        // Initialize Status Client (for real-time status updates)
        if (statusClient == null) {
            Log.d(TAG, "Creating new Status WebSocket client for " + host + ":" + port);
            statusClient = new StatusWebSocketClient(context, host, port);

            // Connect status client with listener
            statusClient.addConnectionListener(new StatusWebSocketClient.ConnectionListener() {
                @Override
                public void onConnectionStateChanged(boolean connected) {
                    Log.d(TAG, "🔗 Status Client CONNECTION STATE: " + connected);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "❌ Status Client ERROR: " + e.getMessage(), e);
                }
            });

            statusClient.connect();
        } else {
            Log.d(TAG, "Status client already initialized");
        }

        // Initialize Command Client (for sending commands)
        if (commandClient == null) {
            Log.d(TAG, "Creating new Command WebSocket client for " + host + ":" + port);
            commandClient = new CommandWebSocketClient(context, host, port);

            // Connect command client with listener
            commandClient.addConnectionListener(new CommandWebSocketClient.ConnectionListener() {
                @Override
                public void onConnectionStateChanged(boolean connected) {
                    Log.d(TAG, "🔗 Command Client CONNECTION STATE: " + connected);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "❌ Command Client ERROR: " + e.getMessage(), e);
                }
            });

            commandClient.connect();
        } else {
            Log.d(TAG, "Command client already initialized");
        }
    }

    // MODIFIED: Register status listener with Status Client
    public void registerStatusListener(StatusWebSocketClient.StatusListener listener) {
        Log.d(TAG, "📋 REGISTERING STATUS LISTENER: " + listener.getClass().getSimpleName());

        if (statusClient != null) {
            statusClient.addStatusListener(listener);
            Log.d(TAG, "✅ Status listener registered with Status client");

            // Immediately send the last known status if available
            if (statusClient.getLastStatusResponse() != null) {
                try {
                    Log.d(TAG, "📤 Sending last status to new listener");
                    listener.onStatusUpdate(statusClient.getLastStatusResponse());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending last status to listener", e);
                }
            }
        } else {
            Log.e(TAG, "❌ Cannot register status listener - Status client is null");
        }
    }

    // MODIFIED: Unregister status listener from Status Client
    public void unregisterStatusListener(StatusWebSocketClient.StatusListener listener) {
        Log.d(TAG, "📋 UNREGISTERING STATUS LISTENER: " + listener.getClass().getSimpleName());

        if (statusClient != null) {
            statusClient.removeStatusListener(listener);
            Log.d(TAG, "✅ Status listener unregistered from Status client");
        } else {
            Log.e(TAG, "❌ Cannot unregister status listener - Status client is null");
        }
    }

    // MODIFIED: Get last status from Status Client
    public AgvStatusResponse getLastStatusResponse() {
        if (statusClient != null) {
            return statusClient.getLastStatusResponse();
        }
        return null;
    }

    // MODIFIED: Enhanced health checking
    public boolean isWebSocketHealthy() {
        boolean statusHealthy = statusClient != null && statusClient.isStatusConnectionHealthy();
        boolean commandHealthy = commandClient != null && commandClient.isConnected();

        Log.d(TAG, "🔍 WebSocket Health - Status: " + statusHealthy + ", Command: " + commandHealthy);
        return statusHealthy && commandHealthy;
    }

    // NEW: Get detailed health status
    public String getWebSocketHealthStatus() {
        if (statusClient == null || commandClient == null) {
            return "Clients not initialized";
        }

        StatusWebSocketClient.ConnectionHealth statusHealth = statusClient.getConnectionHealth();
        boolean commandHealth = commandClient.isConnected();

        return String.format("Status: %s (last msg: %dms ago, last status: %dms ago), Command: %s",
                statusHealth.isConnected ? "Connected" : "Disconnected",
                statusHealth.timeSinceLastMessage,
                statusHealth.timeSinceLastStatus,
                commandHealth ? "Connected" : "Disconnected");
    }

    // NEW: Get Status Client (for UI components that need status)
    public StatusWebSocketClient getStatusClient() {
        return statusClient;
    }

    // NEW: Get Command Client (for sending commands)
    public CommandWebSocketClient getCommandClient() {
        return commandClient;
    }

    // NEW: Helper method to check if both clients are ready
    public boolean areClientsReady() {
        return statusClient != null && statusClient.isConnected() &&
                commandClient != null && commandClient.isConnected();
    }

    // NEW: Reconnect both clients if needed
    public void reconnectClients() {
        Log.d(TAG, "🔄 Attempting to reconnect both WebSocket clients");

        if (statusClient != null) {
            statusClient.disconnect();
            statusClient.connect();
        }

        if (commandClient != null) {
            commandClient.disconnect();
            commandClient.connect();
        }
    }

    // Keep existing methods but update to use command client where needed
    public Position getCurrentPosition() {
        return navigationHandler.getCurrentPosition();
    }

    public Boolean getIsMoving() {
        return navigationHandler.getIsMoving();
    }

    public int getGoalFinish() {
        return navigationHandler.getGoalFinish();
    }

    public Position getCurrentTaskTarget() {return navigationHandler.getCurrentTaskTarget();}

    public int getCurrentEffectiveIndex() {return navigationHandler.getCurrentEffectiveIndex();}

    public GeneralNavigationHandler getNavigationHandler() {
        return navigationHandler;
    }

    public void setTaskCreator(User user) {
        currentTaskCreator.setValue(user);
        isTaskCreatorSet.setValue(true);
        Log.d("SharedViewModel", "Task creator set: " + (user != null ? user.getUserId() : "null"));
    }

    public LiveData<User> getTaskCreator() {
        return currentTaskCreator;
    }

    public LiveData<Boolean> isTaskCreatorSet() {
        return isTaskCreatorSet;
    }

    public void clearTaskCreator() {
        currentTaskCreator.setValue(null);
        isTaskCreatorSet.setValue(false);
        Log.d("SharedViewModel", "Task creator cleared");
    }

    public void setPendingVerificationData(Position target, int waypointIndex, String operation) {
        pendingVerificationTarget.setValue(target);
        pendingVerificationWaypointIndex.setValue(waypointIndex);
        pendingVerificationOperation.setValue(operation);
        Log.d(TAG, "Pending verification data set - target: " + (target != null ? target.getName() : "null") +
                ", waypointIndex: " + waypointIndex + ", operation: " + operation);
    }

    public LiveData<Position> getPendingVerificationTarget() {
        return pendingVerificationTarget;
    }

    public LiveData<Integer> getPendingVerificationWaypointIndex() {
        return pendingVerificationWaypointIndex;
    }

    public LiveData<String> getPendingVerificationOperation() {
        return pendingVerificationOperation;
    }

    public void clearPendingVerificationData() {
        pendingVerificationTarget.setValue(null);
        pendingVerificationWaypointIndex.setValue(null);
        pendingVerificationOperation.setValue(null);
        Log.d(TAG, "Pending verification data cleared");
    }

    public void setCurrentTaskTarget(Position target) {
        currentTaskTarget.setValue(target);
    }

    public void setCurrentEffectiveIndex(int index) {
        currentEffectiveIndex.setValue(index);
    }

    public void setCurrentWaypointIndex(int index) {
        currentWaypointIndex.setValue(index);
    }

    public void setSelectedUserId(String userId) {
        selectedUserId.setValue(userId);
    }

    public String getSelectedUserId() {
        return selectedUserId.getValue();
    }

    public void setSelectedUserName(String userName) {
        selectedUserName.setValue(userName);
    }

    public String getSelectedUserName() {
        return selectedUserName.getValue();
    }

    // Getter and Setter for inUseElevator
    public LiveData<String> getInUseElevator() {
        return inUseElevator;
    }

    public void setInUseElevator(String elevatorId) {
        inUseElevator.postValue(elevatorId);  // ✅ Use postValue()
        Log.d(TAG, "In-use elevator set to: " + elevatorId);
    }

    public void clearInUseElevator() {
        inUseElevator.postValue(null);  // ✅ Use postValue()
        Log.d(TAG, "In-use elevator cleared");
    }

    public String getInUseElevatorValue() {
        return inUseElevator.getValue();
    }

    // Elevator availability (cloud-returned)
    public void setElevatorAvailability(Map<String, Boolean> availability) {
        elevatorAvailability.postValue(availability != null ? availability : new HashMap<>());
    }

    public Map<String, Boolean> getElevatorAvailabilityValue() {
        return elevatorAvailability.getValue();
    }

    public Boolean isElevatorAvailable(String elevatorId) {
        Map<String, Boolean> availability = elevatorAvailability.getValue();
        if (availability == null || elevatorId == null) {
            return null;
        }
        return availability.get(elevatorId);
    }

    public void clearElevatorAvailability() {
        elevatorAvailability.postValue(new HashMap<>());
        Log.d(TAG, "Elevator availability cleared");
    }

    // --------------------------------------------------------------------------------------------

    // Existing LoRa configuration (unchanged)
    private final MutableLiveData<LoraConfig> loraConfig = new MutableLiveData<>();

    public static class LoraConfig {
        private int channel;
        private int address;

        public LoraConfig(int channel, int address) {
            this.channel = channel;
            this.address = address;
        }

        public LoraConfig() {}

        public int getChannel() { return channel; }
        public void setChannel(int channel) { this.channel = channel; }

        public int getAddress() { return address; }
        public void setAddress(int address) { this.address = address; }
    }

    public void saveLoraConfig(int channel, int address) {
        LoraConfig config = new LoraConfig(channel, address);
        loraConfig.setValue(config);
    }
}