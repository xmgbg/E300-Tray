package com.ezhan.amr.navigation.task;

public enum NavigationOrderType {
    OP_NOP,                      // No operation
    OP_FREE_MOVE,                     // Regular movement
    OP_FIXED_MOVE,
    OP_IN_ELEVATOR_MOVE,         // Move into elevator
    OP_OUT_ELEVATOR_MOVE,        // Move out of elevator
    OP_RECOGNIZE_LOAD,           // Recognize and load (type 5)
    OP_RECOGNIZE_UNLOAD,         // Recognize and unload (type 6)
    OP_EXIT_SHELF,               // Exit shelf (type 7)
    OP_NON_RECOGNIZE_LOAD,       // Non-recognize load (type 8)
    OP_NON_RECOGNIZE_UNLOAD,     // Non-recognize unload (type 9)
    OP_CHARGE_START,             // Start charge (type 10)
    OP_CHARGE_STOP,              // Stop charge (type 11)
    OP_ELEVATOR_CALL,            // Call elevator
    OP_ELEVATOR_CANCEL_CALL,     // Cancel elevator call
    OP_ELEVATOR_CHECK,           // Check elevator
    OP_ELEVATOR_OPEN,            // Open elevator door
    OP_ELEVATOR_CANCEL_OPEN,     // Cancel elevator door open
    OP_ELEVATOR_DOOR_CHECK,
    OP_ELEVATOR_CLAIM_ACCESS,
    OP_ELEVATOR_RELEASE_ACCESS,
    OP_ELEVATOR_QUERY_ACCESS,
    OP_AUTO_DOOR_OPEN,           // Open automatic door
    OP_AUTO_DOOR_CANCEL_OPEN,    // Cancel automatic door open
    OP_AUTO_DOOR_CHECK,
    OP_PAUSE,                    // Manual pause
    OP_RESUME,                   // Manual resume
    OP_CANCEL,                   // Cancel navigation
    OP_EMERGENCY_STOP,           // Emergency stop
    OP_SAFE_EDGE,                // Safe edge trigger
    OP_CONNECTION_FAILURE,        // Connection failure
    OP_RELOCALIZE,                   // Relocalize
    OP_CHANGE_MAP,                  // change map
    OP_ELEVATOR_ARRIVAL_DOOR_CHECK,
    OP_FORWARD,
    OP_BACKWARD,
    OP_RECOGNIZE_ENTRY_ONLY      // Recognize entry only, no jack (type 19)
}
