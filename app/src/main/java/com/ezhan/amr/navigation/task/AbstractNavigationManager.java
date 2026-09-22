package com.ezhan.amr.navigation.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractNavigationManager {
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractNavigationManager.class);

    // Shared processing lock
    protected static final Map<Long, Boolean> routeProcessing = new ConcurrentHashMap<>();

    public abstract void initManager();

    /**
     * Process tasks for a single route
     */
    public abstract void processSingleRoute(long routeId);

    /**
     * Update status for a single route
     */
    public abstract void updateSingleRouteStatus(long routeId);

    /**
     * Get all active route IDs
     */
    protected Set<Long> getAllActiveRouteIds() {
        Set<Long> routeIds = new HashSet<>();
        for (RouteStore route : RouteStore.getAllRoutes().values()) {
            NavigationOrderStatus status = route.getRouteStatus();
            if (status != NavigationOrderStatus.ORDER_COMPLETED &&
                    status != NavigationOrderStatus.ORDER_CANCELLED &&
                    status != NavigationOrderStatus.ORDER_FAILED) {
                routeIds.add(route.getRouteId());
            }
        }
        return routeIds;
    }

    /**
     * Main processing cycle - called from scheduler
     */
    public void executeCoordinatedRouteProcessing() {
        for (long routeId : getAllActiveRouteIds()) {
            // Skip if already processing
            if (routeProcessing.putIfAbsent(routeId, true) != null) {
                continue;
            }

            // Skip if route is being cancelled or already terminated
            RouteStore route = RouteStore.getRoute(routeId);
            if (route != null &&
                    (route.getRouteStatus() == NavigationOrderStatus.ORDER_CANCELLED ||
                            route.getRouteStatus() == NavigationOrderStatus.ORDER_FAILED ||
                            route.getRouteStatus() == NavigationOrderStatus.ORDER_COMPLETED)) {
                routeProcessing.remove(routeId);
                continue;
            }

//            // Skip if exception manager is active (handling recovery)
//            if (NavigationExceptionManager.getInstance().isActive()) {
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Exception manager is active, skipping route {} processing", routeId);
//                }
//                routeProcessing.remove(routeId);
//                continue;
//            }

            try {
                // Process in order: RouteManager -> PositionManager -> CommandManager
                NavigationRouteManager.getInstance().processSingleRoute(routeId);
                NavigationPositionManager.getInstance().processSingleRoute(routeId);
                NavigationCommandManager.getInstance().processSingleRoute(routeId);
                NavigationExceptionManager.getInstance().processSingleRoute(routeId);
            } catch (Exception e) {
                LOG.error("Error processing route {}: {}", routeId, e.getMessage(), e);
            } finally {
                routeProcessing.remove(routeId);
            }
        }
    }

    /**
     * Status update cycle - called from scheduler
     */
    /**
     * Status update cycle - called from scheduler
     */
    public void executeCoordinatedStatusUpdates() {
        for (long routeId : getAllActiveRouteIds()) {
            // Skip if route is being processed (to avoid conflicts)
            if (routeProcessing.containsKey(routeId)) {
                continue; // Skip if processing tasks
            }

            // Skip if route is being cancelled or already terminated
            RouteStore route = RouteStore.getRoute(routeId);
            if (route != null &&
                    (route.getRouteStatus() == NavigationOrderStatus.ORDER_CANCELLED ||
                            route.getRouteStatus() == NavigationOrderStatus.ORDER_FAILED ||
                            route.getRouteStatus() == NavigationOrderStatus.ORDER_COMPLETED)) {
                continue; // Skip terminated routes
            }

//            // Skip if exception manager is active (handling recovery)
//            // This prevents status updates from interfering with recovery
//            if (NavigationExceptionManager.getInstance().isActive()) {
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Exception manager is active, skipping status updates for route {}", routeId);
//                }
//                continue;
//            }

            try {
                // Update status in reverse order: CommandManager -> PositionManager -> RouteManager
                NavigationCommandManager.getInstance().updateSingleRouteStatus(routeId);
                NavigationPositionManager.getInstance().updateSingleRouteStatus(routeId);
                NavigationRouteManager.getInstance().updateSingleRouteStatus(routeId);
                NavigationExceptionManager.getInstance().updateSingleRouteStatus(routeId);
            } catch (Exception e) {
                LOG.error("Error updating status for route {}: {}", routeId, e.getMessage(), e);
            }
        }
    }

    /**
     * Cancel all tasks associated with a route
     * Similar to cancelAllTasks in AbstractTaskManager
     *
     * @param routeId The route ID to cancel
     * @param finishStatus The status to set (CANCELLED, FAILED, etc.)
     */
    public void cancelAllTasks(long routeId, NavigationOrderStatus finishStatus) {
        try {
            RouteStore cancelRoute = RouteStore.getRoute(routeId);
            if (cancelRoute == null) {
                LOG.warn("Route {} not found for cancellation", routeId);
                return;
            }

            LOG.info("Cancelling route {} with status {}", routeId, finishStatus);

            // Get all positions for this route
            List<PositionMonitor> cancelPositionList = getPositionsForRoute(routeId);

            if (!cancelPositionList.isEmpty()) {
                for (PositionMonitor cancelPosition : cancelPositionList) {

                    // Get all commands for this position
                    List<CommandMonitor> cancelCommandList = getCommandsForPosition(cancelPosition.getPositionId());

                    if (!cancelCommandList.isEmpty()) {
                        // Cancel related commands
                        for (CommandMonitor cancelCommand : cancelCommandList) {
                            cancelCommand.setCommandStatus(finishStatus);
                            CommandMonitor.updateCommand(cancelCommand);
                            LOG.info("Route {} command {} cancelled with status {}",
                                    routeId, cancelCommand.getCommandId(), finishStatus);
                        }
                    }

                    // Cancel related position
                    cancelPosition.setPositionStatus(finishStatus);
                    PositionMonitor.updatePosition(cancelPosition);
                    LOG.info("Route {} position {} cancelled with status {}",
                            routeId, cancelPosition.getPositionId(), finishStatus);
                }
            }

            // Cancel the route itself
            cancelRoute.setRouteStatus(finishStatus);
            if (finishStatus == NavigationOrderStatus.ORDER_CANCELLED) {
                cancelRoute.setCompleteTime(System.currentTimeMillis());
            }
            RouteStore.updateRoute(cancelRoute);
            LOG.info("Route {} cancelled with status {}", routeId, finishStatus);

        } catch (Exception e) {
            LOG.error("Failed to cancel route {}: {}", routeId, e.getMessage(), e);
        }
    }

    /**
     * Get all positions for a route
     */
    protected List<PositionMonitor> getPositionsForRoute(long routeId) {
        RouteStore route = RouteStore.getRoute(routeId);
        if (route == null) return Collections.emptyList();

        List<PositionMonitor> positions = new ArrayList<>();
        for (Long posId : route.getPositionIds()) {
            PositionMonitor pos = PositionMonitor.getPosition(posId);
            if (pos != null) {
                positions.add(pos);
            }
        }
        positions.sort(Comparator.comparingInt(PositionMonitor::getSequenceIndex));
        return positions;
    }

    /**
     * Get all commands for a position
     */
    protected List<CommandMonitor> getCommandsForPosition(long positionId) {
        PositionMonitor pos = PositionMonitor.getPosition(positionId);
        if (pos == null) return Collections.emptyList();

        List<CommandMonitor> commands = new ArrayList<>();
        for (Long cmdId : pos.getCommandIds()) {
            CommandMonitor cmd = CommandMonitor.getCommand(cmdId);
            if (cmd != null) {
                commands.add(cmd);
            }
        }
        commands.sort(Comparator.comparingLong(CommandMonitor::getCommandId));
        return commands;
    }

    protected void handleTaskFailure(Exception exception) {
        LOG.error("Task failure: {}", exception.getMessage(), exception);
    }
}
