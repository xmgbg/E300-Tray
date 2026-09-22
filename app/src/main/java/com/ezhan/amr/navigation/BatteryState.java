package com.ezhan.amr.navigation;

/**
 * Battery state machine states for the AMR robot.
 *
 * State hierarchy (by battery level, when not charging):
 *   CRITICAL  (<= critical)      - Interrupt current task immediately, go charge
 *   LOW       (<= min working)   - Reject new tasks; low-battery auto charge may trigger
 *   IDLE      (<= idle charge upper) - Normal tasks; eligible for idle auto-charge band
 *   NORMAL    (> idle charge upper)   - Normal operation
 *   CHARGING                        - Currently charging
 *   FULL                            - Charging complete (fixed at 100%)
 */
public enum BatteryState {
    CRITICAL,
    LOW,
    IDLE,
    NORMAL,
    CHARGING,
    FULL
}
