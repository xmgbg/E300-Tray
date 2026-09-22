package com.ezhan.amr.navigation.task;

// State tracking
public enum NavigationState {
    IDLE,                   // Ready for new navigation
    EXECUTING_COMMANDS,     // Executing pre-navigation commands
    NAVIGATING_TO_WAYPOINT, // Moving to waypoint
    WAITING_FOR_COMPLETION,  // Waiting for navigation to finish
    PAUSED,
    EMERGENCY_STOPPED, // ← Optional: dedicated emergency state
    CHARGING,
    CANCELLED,
    BUMPED,
    CONNECTION_FAILURE,
}
