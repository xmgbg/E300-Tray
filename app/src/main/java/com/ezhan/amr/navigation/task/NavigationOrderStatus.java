package com.ezhan.amr.navigation.task;

public enum NavigationOrderStatus {
    ORDER_RAW,           // Initial state
    ORDER_PREREADY,      // Ready to be processed
    ORDER_READY,         // Ready for execution
    ORDER_EXECUTING,     // Currently executing
    ORDER_PAUSED,        // Paused
    ORDER_RESUMED,       // Resumed
    ORDER_COMPLETED,     // Successfully completed
    ORDER_CANCELLED,     // Cancelled
    ORDER_FAILED,         // Failed
    ORDER_WAITING_CONFIRMATION,
    ORDER_SUSPENDED,
}
