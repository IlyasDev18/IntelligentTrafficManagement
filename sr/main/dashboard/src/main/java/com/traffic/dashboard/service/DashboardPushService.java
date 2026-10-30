package com.traffic.dashboard.service;

import com.traffic.dashboard.model.DashboardSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Pushes a fresh {@link DashboardSnapshot} to all subscribed WebSocket
 * clients on the /topic/dashboard channel every
 * {@code dashboard.push.interval-ms} milliseconds.
 */
@Service
public class DashboardPushService {

    private final SimpMessagingTemplate ws;
    private final DashboardDataService  dataService;

    public DashboardPushService(SimpMessagingTemplate ws, DashboardDataService dataService) {
        this.ws = ws;
        this.dataService = dataService;
        System.out.println("[DEBUG] DashboardPushService initialized");
    }

    @Scheduled(fixedRateString = "${dashboard.push.interval-ms}")
    public void push() {
        System.out.println("[DEBUG] DashboardPushService.push() called - scheduled push");
        try {
            long startTime = System.currentTimeMillis();
            DashboardSnapshot snap = dataService.buildSnapshot();
            long buildTime = System.currentTimeMillis() - startTime;
            
            System.out.println("[DEBUG] DashboardPushService.push() - snapshot built in " + buildTime + "ms");
            System.out.println("[DEBUG] DashboardPushService.push() - pushing snapshot with " + 
                (snap.getLanes() != null ? snap.getLanes().size() : 0) + " lanes, " +
                (snap.getPollution() != null ? "pollution data" : "no pollution data"));
            
            ws.convertAndSend("/topic/dashboard", snap);
            
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("[DEBUG] DashboardPushService.push() - completed in " + totalTime + "ms");
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardPushService.push() - Error building/sending snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }
}