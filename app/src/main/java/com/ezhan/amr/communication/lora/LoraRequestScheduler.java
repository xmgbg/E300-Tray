package com.ezhan.amr.communication.lora;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class LoraRequestScheduler {
    private static final String TAG = "LoraRequestScheduler";
    public static final int PRIORITY_ELEVATOR = 10;
    public static final int PRIORITY_CALLBOX = 20;
    public static final int PRIORITY_MAP_AREA = 30;
    public static final int PRIORITY_INFO_AREA = 40;

    private static volatile LoraRequestScheduler instance;

    private final LoraCommunicator communicator;
    private final PriorityBlockingQueue<LoraRequest> requestQueue = new PriorityBlockingQueue<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();
    private volatile boolean running = true;
    private final Thread workerThread;

    public static synchronized LoraRequestScheduler getInstance(LoraCommunicator communicator) {
        if (instance == null || !instance.running) {
            instance = new LoraRequestScheduler(communicator);
        }
        return instance;
    }

    private LoraRequestScheduler(LoraCommunicator communicator) {
        this.communicator = communicator;
        workerThread = new Thread(this::runLoop, "Lora-Request-Scheduler");
        workerThread.start();
    }

    public void enqueue(String source, String hexMessage, boolean expectResponse,
                        int timeoutMs, int priority) {
        if (!running) {
            Log.w(TAG, "Scheduler stopped, dropping request from " + source);
            return;
        }
        requestQueue.offer(new LoraRequest(
                source,
                hexMessage,
                expectResponse,
                timeoutMs,
                priority,
                sequenceGenerator.incrementAndGet()
        ));
    }

    public void shutdown() {
        running = false;
        workerThread.interrupt();
    }

    private void runLoop() {
        while (running) {
            try {
                LoraRequest request = requestQueue.take();
                sendRequest(request);
            } catch (InterruptedException e) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected LoRa scheduler error", e);
            }
        }
    }

    private void sendRequest(LoraRequest request) {
        try {
            Log.d(TAG, "Sending LoRa request from " + request.source
                    + ", priority=" + request.priority
                    + ", expectResponse=" + request.expectResponse);
            communicator.sendMessage(request.hexMessage, request.expectResponse, request.timeoutMs);
        } catch (IOException | TimeoutException e) {
            Log.e(TAG, "LoRa request failed from " + request.source + ": " + e.getMessage(), e);
        }
    }

    private static class LoraRequest implements Comparable<LoraRequest> {
        final String source;
        final String hexMessage;
        final boolean expectResponse;
        final int timeoutMs;
        final int priority;
        final long sequence;

        LoraRequest(String source, String hexMessage, boolean expectResponse,
                    int timeoutMs, int priority, long sequence) {
            this.source = source;
            this.hexMessage = hexMessage;
            this.expectResponse = expectResponse;
            this.timeoutMs = timeoutMs;
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(LoraRequest other) {
            int priorityCompare = Integer.compare(this.priority, other.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}
