package com.ezhan.amr.web;

import com.ezhan.amr.navigation.task.NavigationEventType;

public class QueuedTask {
    private int queueId;
    private NavigationEventType eventType;
    private String stationId;
    private Integer jackTaskId;
    private long queueTime;

    public QueuedTask(int queueId, NavigationEventType eventType, String stationId, Integer jackTaskId) {
        this.queueId = queueId;
        this.eventType = eventType;
        this.stationId = stationId;
        this.jackTaskId = jackTaskId;
        this.queueTime = System.currentTimeMillis();
    }

    public int getQueueId() {
        return queueId;
    }

    public NavigationEventType getEventType() {
        return eventType;
    }

    public String getStationId() {
        return stationId;
    }

    public Integer getJackTaskId() {
        return jackTaskId;
    }

    public long getQueueTime() {
        return queueTime;
    }

    public String getTaskName() {
        if (eventType == NavigationEventType.DELIVERY_TASK) {
            return stationId;
        } else if (eventType == NavigationEventType.JACK_TASK) {
            if (stationId != null && !stationId.isEmpty()) {
                return stationId;
            }
            return "Jack task-" + jackTaskId;
        }
        return eventType.name();
    }
}
