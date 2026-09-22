package com.ezhan.amr.navigation;

import com.ezhan.amr.data.datatype.Position;
import com.ezhan.amr.navigation.task.CommandBuilder;
import com.ezhan.amr.navigation.task.NavigationOrderType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LoraCommand {

    public final NavigationOrderType type;
    private final String stationId;
    public final Map<String, Object> hexParams;  // For hex commands
//    public final String asciiPayload;            // For ASCII commands
    private final String expectedResponse;
    private final int maxRetries;
    private final long retryDelayMs;
    private final String description;            // Add this field
    private final boolean isPlayMusic;           // Add this field
    private final Position target;                // Add this field
    private final Position preridePoint;
    private final Position ridePoint;


    public static class Builder {
        // Required fields
        private final NavigationOrderType type;
        private final String stationId;

        // Optional fields
        private final Map<String, Object> hexParams = new HashMap<>();
        private String asciiPayload = "";
        private String expectedResponse = "OK";
        private int maxRetries = 3;
        private long retryDelayMs = 2000;
        private String description = "";         // Add this field
        private boolean isPlayMusic = false;     // Add this field - default to false
        private Position target = null;           // Add this field - default to null
        private Position preridePoint = null;
        private Position ridePoint = null;

        public Builder(NavigationOrderType type, String stationId) {
            this.type = type;
            this.stationId = stationId;
        }

        /**
         * For hex commands - adds a parameter key-value pair
         * (e.g., .withHexParam("floor", "3"))
         */
        public Builder withHexParam(String key, Object value) {
            this.hexParams.put(key, value);
            return this;
        }

//        /**
//         * For ASCII commands - sets the full payload string
//         */
//        public Builder withAsciiPayload(String payload) {
//            this.asciiPayload = payload;
//            return this;
//        }

        public Builder withExpectedResponse(String response) {
            this.expectedResponse = response;
            return this;
        }

        public Builder withRetries(int maxRetries, long delayMs) {
            this.maxRetries = maxRetries;
            this.retryDelayMs = delayMs;
            return this;
        }

        // Add this new method for setting description
        public Builder withDescription(String description) {
            this.description = description;
            return this;
        }

        // Add this new method for setting isPlayMusic
        public Builder withPlayMusic(boolean isPlayMusic) {
            this.isPlayMusic = isPlayMusic;
            return this;
        }

        // Add this new method for setting target position
        public Builder withTarget(Position target) {
            this.target = target;
            return this;
        }

        public Builder withPreridePoint(Position preridePoint) {
            this.preridePoint = preridePoint;
            return this;
        }

        public Builder withRidePoint(Position ridePoint) {
            this.ridePoint = ridePoint;
            return this;
        }

        public LoraCommand build() {
            return new LoraCommand(this);
        }
    }

    private LoraCommand(Builder builder) {
        this.type = builder.type;
        this.stationId = builder.stationId;
        this.hexParams = Collections.unmodifiableMap(builder.hexParams);
//        this.asciiPayload = builder.asciiPayload;
        this.expectedResponse = builder.expectedResponse;
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
        this.description = builder.description;  // Initialize description
        this.isPlayMusic = builder.isPlayMusic;  // Initialize isPlayMusic
        this.target = builder.target;             // Initialize target
        this.preridePoint = builder.preridePoint;
        this.ridePoint = builder.ridePoint;

    }

    // Getters
    public String getDeviceCommand() {
        return CommandBuilder.buildHexMessage(type, hexParams);
    }

    public Map<String, Object> getHexParams() {
        return hexParams;
    }

    public String getExpectedResponse() {
        return expectedResponse;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    // Add getter for description
    public String getDescription() {
        return description;
    }

    // Add getter for isPlayMusic
    public boolean isPlayMusic() {
        return isPlayMusic;
    }

    // Add getter for target
    public Position getTarget() {
        return target;
    }

    public Position getPreridePoint() {
        return preridePoint;
    }

    public Position getRidePoint() {
        return ridePoint;
    }
}