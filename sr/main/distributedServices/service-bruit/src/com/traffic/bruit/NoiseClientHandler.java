package com.traffic.bruit;

import com.city.sensors.model.LaneData;
import com.city.sensors.model.RotarySnapshot;
import com.city.sensors.simulator.RotarySimulator;
import com.city.sensors.noise.NoiseRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Handles a single client connection for the Noise Server.
 * It implements Runnable so it can be executed in a separate thread.
 */
public class NoiseClientHandler implements Runnable {

    private final Socket clientSocket;
    private final Random random = new Random();
    private static final RotarySimulator simulator = new RotarySimulator("NOISE-ZONE");
    private static final String[] LANE_IDS = {
        "NORTH-IN", "SOUTH-IN", "EAST-IN", "WEST-IN",
        "NORTH-OUT", "SOUTH-OUT", "EAST-OUT", "WEST-OUT"
    };
    private static final NoiseRegistry registry = new NoiseRegistry("NOISE-ZONE", LANE_IDS);

    public NoiseClientHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        // We use try-with-resources to auto-close the PrintWriter and Socket.
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            String laneId = in.readLine();
            System.out.println("Received request for lane ID: " + laneId);

            if (laneId == null || laneId.trim().isEmpty()) {
                out.println("ERROR: No lane ID provided.");
                return;
            }
            
            // --- Generate Fake Data ---
            // Use the simulator to get a realistic vehicle count for this lane
            RotarySnapshot snapshot = simulator.tick();

            // Prepare vehicle counts for the registry
            Map<String, Integer> counts = new HashMap<>();
            for (String id : LANE_IDS) {
                LaneData ld = snapshot.getLaneData().get(id);
                counts.put(id, (ld != null) ? ld.getVehicleCount() : 0);
            }

            Map<String, String> sensorOutputs = registry.snapshot(counts);
            String output = sensorOutputs.getOrDefault(laneId, "ERROR: Invalid Lane ID");

            System.out.println("Sending data to " + clientSocket.getRemoteSocketAddress() + ": " + output);

            // --- Send Data to Client ---
            out.println(output);

        } catch (IOException e) {
            System.err.println("Error handling client " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            try {
                // Ensure the socket is closed.
                clientSocket.close();
                System.out.println("Closed connection from " + clientSocket.getRemoteSocketAddress());
            } catch (IOException e) {
                // Ignore this error
            }
        }
    }
}