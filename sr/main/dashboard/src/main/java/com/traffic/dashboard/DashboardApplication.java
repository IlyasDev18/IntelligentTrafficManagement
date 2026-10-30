package com.traffic.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DashboardApplication {
    public static void main(String[] args) {
        System.out.println("[DEBUG] DashboardApplication starting with args: " + java.util.Arrays.toString(args));
        try {
            SpringApplication.run(DashboardApplication.class, args);
            System.out.println("[DEBUG] DashboardApplication started successfully");
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardApplication failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }
}