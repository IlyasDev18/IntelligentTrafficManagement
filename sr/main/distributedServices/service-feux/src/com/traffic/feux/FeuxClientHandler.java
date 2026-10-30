package com.traffic.feux;

import com.city.sensors.trafficLight.TrafficLightRegistry;
import com.city.sensors.trafficLight.TrafficLightSensor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class FeuxClientHandler implements Runnable {

    private final Socket clientSocket;
    private static final TrafficLightRegistry registry = new TrafficLightRegistry("MAIN-STREET");

    public FeuxClientHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (clientSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request = in.readLine();

            if (request == null) {
                return;
            }

            System.out.println("   [DEBUG] Received: " + request);

            if (request.startsWith("EXTEND:")) {
                String[] parts = request.split(":");
                if (parts.length == 3) {
                    String direction = parts[1];
                    int seconds = Integer.parseInt(parts[2]);

                   
                    registry.extendGreenTime(direction, seconds); 

                    out.println("OK|Extended " + direction + " by " + seconds + "s");
                    System.out.println("   [CMD] Extended " + direction + " by " + seconds + "s");
                } else {
                    out.println("ERROR|Invalid format. Use EXTEND:DIR:SEC");
                }
                return;
            }

            String lightId = request.trim();

            if (lightId.isEmpty()) {
                out.println("ERROR: Empty light ID");
                return;
            }

            registry.update();

            int idx = lightId.lastIndexOf('-');
            String direction = (idx >= 0) ? lightId.substring(idx + 1) : lightId;

            TrafficLightSensor sensor = registry.get(direction);
            if (sensor == null) {
                out.println("ERROR: Unknown light id");
                return;
            }

            String state = sensor.getCurrentState().name();
            String response = String.format("%s|%s|%d", lightId, state, System.currentTimeMillis());

            out.println(response);

        } catch (IOException e) {
            System.err.println("   [WARN] Connection issue: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("   [ERROR] Processing error: " + e.getMessage());
        }
    }
}