package com.traffic.producer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.traffic.producer.model.CitySnapshot;
import com.traffic.producer.model.LaneData;
import com.traffic.producer.model.RecommendedAction;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.rmi.Naming;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TrafficProducerMain {

    private static final String TOPIC_NAME = "city-snapshots";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    // --- Service Endpoints ---
    private static final String POLLUTION_SERVICE_URL = "http://localhost:8082/api/pollution";
    private static final String FLUX_SERVICE_URL      = "http://localhost:8081/flux";
    private static final String CAMERA_SERVICE_RMI_NAME = "rmi://localhost:1099/CameraService";
    private static final String FEUX_SERVICE_HOST = "localhost";
    private static final int FEUX_SERVICE_PORT = 7000;
    private static final String NOISE_SERVICE_HOST = "localhost";
    private static final int NOISE_SERVICE_PORT = 6000;

        public static void main(String[] args) {

            // --- Kafka Producer Setup ---

            Properties props = new Properties();

            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            KafkaProducer<String, String> producer = new KafkaProducer<>(props);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();

    

            System.out.println("Starting Traffic Data Aggregator for 8-lane rotary. Press Ctrl-C to stop.");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {

                System.out.println("Closing producer...");

                producer.close();

                System.out.println("Producer closed.");

            }));

    

            List<String> lanes = Arrays.asList("NORTH-IN", "SOUTH-IN", "EAST-IN", "WEST-IN", "NORTH-OUT", "SOUTH-OUT", "EAST-OUT", "WEST-OUT");

            List<String> trafficLights = Arrays.asList("TL-MAIN-STREET-NORTH", "TL-MAIN-STREET-SOUTH", "TL-MAIN-STREET-EAST", "TL-MAIN-STREET-WEST");

    

            try {

                while (true) {

                    // --- Create the main snapshot object ---

                    CitySnapshot snapshot = new CitySnapshot();

                    snapshot.setZoneId("MAIN-STREET-ROTARY");

                    snapshot.setType("rotary");

                    snapshot.setTimestamp(System.currentTimeMillis());

    

                    // --- Collect data from all services ---

                    System.out.println("\n--- Collecting data from services ---");

                    

                    // 1. Pollution Data (once per snapshot)

                    snapshot.setPollutionData(callPollutionService());

    

                    // 2. Traffic Light Data (once per snapshot)

                    Map<String, String> trafficLightData = new HashMap<>();

                    for (String lightId : trafficLights) {

                        trafficLightData.put(lightId, callFeuxService(lightId));

                    }

                    snapshot.setTrafficLightData(trafficLightData);

    

                    // 3. Lane-specific Data (for each of the 8 lanes)
                    Map<String, LaneData> laneDataMap = new HashMap<>();

                    for (String laneId : lanes) {

                        LaneData laneData = new LaneData();

    

                        // Dummy data for flux and vehicle count

                        laneData.setFlux(getRandomFlux());

                        String cameraData = callCameraService(laneId);

                        String[] cameraParts = cameraData.split("\\|");

                        // Fix: Check if data is a valid number before parsing to avoid crash
                        if(cameraParts.length > 1 && cameraParts[2].trim().matches("-?\\d+")) {

                            laneData.setVehicleCount(Integer.parseInt(cameraParts[2].trim()));

                        } else {

                            // If flux service provided count, keep it; otherwise random fallback
                            if (laneData.getVehicleCount() == 0) {
                                laneData.setVehicleCount(ThreadLocalRandom.current().nextInt(5, 50));
                            }

                        }

                        

    

                        // Call noise and camera services

                        laneData.setNoise(callNoiseService(laneId));

                        String cameraStatus = getCameraStatusFromData(cameraData);

                        laneData.setCameraStatus(cameraStatus);


                        if (cameraStatus.contains("ACCIDENT")) {

                            laneData.setCameraDetails("Severity: HIGH, Location: 50m into lane");

                        } else if (cameraStatus.contains("STOPPED_VEHICLE")) {

                             laneData.setCameraDetails("Duration: 2m 15s");

                        }

    

                        laneDataMap.put(laneId, laneData);

                    }

                    snapshot.setLaneData(laneDataMap);

    

                    // 4. Recommended Actions (dummy data)
                    List<RecommendedAction> recommendedActions = new ArrayList<>();

                    if (snapshot.getLaneData().get("SOUTH-IN").getCameraStatus().equals("ACCIDENT")) {
                        RecommendedAction action1 = new RecommendedAction();

                        action1.setAction("CHANGE_LIGHT");

                        action1.setTarget("TL-MAIN-STREET-NORTH");

                        action1.setNewValue("RED");

                        action1.setReason("ACCIDENT detected on SOUTH-IN, clear incoming traffic.");

                        recommendedActions.add(action1);

    

                        RecommendedAction action2 = new RecommendedAction();

                        action2.setAction("CHANGE_LIGHT");

                        action2.setTarget("TL-MAIN-STREET-EAST");

                        action2.setNewValue("RED");

                        action2.setReason("ACCIDENT detected on SOUTH-IN, clear incoming traffic.");

                        recommendedActions.add(action2);

                    }

                    snapshot.setRecommendedActions(recommendedActions);

    

    

                    // --- Create and publish the aggregated event ---

                    // Convert to JsonObject to handle nested JSON strings (specifically pollutionData)
                    JsonObject jsonSnapshotObj = gson.toJsonTree(snapshot).getAsJsonObject();

                    if (jsonSnapshotObj.has("pollutionData")) {
                        String pData = jsonSnapshotObj.get("pollutionData").getAsString();
                        // If the string looks like JSON, parse it and replace the field
                        if (pData != null && pData.trim().startsWith("{")) {
                            try {
                                jsonSnapshotObj.add("pollutionData", JsonParser.parseString(pData));
                            } catch (Exception e) { /* Ignore parsing errors */ }
                        }
                    }

                    String jsonSnapshot = gson.toJson(jsonSnapshotObj);
                    System.out.println(jsonSnapshot);

                    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, "city-" + System.currentTimeMillis(), jsonSnapshot);

    

                    producer.send(record, (metadata, exception) -> {

                        if (exception == null) {

                            System.out.println("✅ Successfully published event to Kafka:");

                            System.out.println("  Topic: " + metadata.topic() + ", Partition: " + metadata.partition() + ", Offset: " + metadata.offset());

                            // System.out.println("  Data: " + jsonSnapshot); // Commented out to reduce noise, uncomment if needed

                        } else {

                            System.err.println("❌ Failed to publish to Kafka. See exception below:");

                            exception.printStackTrace();

                        }

                    });

    

                    System.out.println("--- Waiting for 10 seconds... ---");

                    Thread.sleep(10000);

                }

            } catch (InterruptedException e) {

                System.out.println("Aggregator interrupted. Shutting down.");

            }

        }

    

        private static String getCameraStatusFromData(String cameraData) {

            // For now, let's simulate different camera statuses based on vehicle count

            if(cameraData == null || cameraData.startsWith("ERROR")){
                return "ERROR";

            }

            String[] parts = cameraData.split("\\|");

           if (parts.length >= 2 && parts[2].matches("-?\\d+")) {
        int vehicleCount = Integer.parseInt(parts[2]);
        
        if (vehicleCount > 40) {
            return "ACCIDENT";
        }
        if (vehicleCount > 30) {
            return "STOPPED_VEHICLE";
        }
        return "NORMAL";
    }

    // 3. Fallback if the data format was unexpected
    return "UNKNOWN_FORMAT";
}

    

        private static String getRandomFlux() {

            String[] statuses = {"LOW", "OK", "HIGH"};

            return statuses[ThreadLocalRandom.current().nextInt(0, statuses.length)];

        }

    

        // --- Helper methods to call each service ---

    

        private static String callPollutionService() {

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {

                HttpGet request = new HttpGet(POLLUTION_SERVICE_URL);

                try (CloseableHttpResponse response = httpClient.execute(request)) {

                    HttpEntity entity = response.getEntity();

                    if (entity != null) {

                        String result = EntityUtils.toString(entity);
                        return result;

                    }

                }

            } catch (Exception e) {
                return "ERROR: " + e.getMessage();

            }

            return "ERROR: No response from pollution service.";

        }

    

        private static String callCameraService(String cameraId) {

            try {

                com.traffic.camera.CameraService service = (com.traffic.camera.CameraService) Naming.lookup(CAMERA_SERVICE_RMI_NAME);

                String result = service.captureSnapshot(cameraId);
                return result;

            } catch (Exception e) {

                return "ERROR: " + e.getMessage();

            }

        }

    

        private static String callFeuxService(String lightId) {

            try (Socket socket = new Socket(FEUX_SERVICE_HOST, FEUX_SERVICE_PORT);

                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

    

                out.println(lightId); // Send a request

                String response = in.readLine(); // Read the response
                if (response != null && !response.startsWith("ERROR")) {

                    return response.split("\\|")[1];

                }

                return "ERROR";

            } catch (IOException e) {
                return "ERROR: " + e.getMessage();

            }

        }

    

        private static String callNoiseService(String laneId) {

             try (Socket socket = new Socket(NOISE_SERVICE_HOST, NOISE_SERVICE_PORT);

                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

    

                out.println(laneId); // Send a request

                String response = in.readLine();
                return response;

            } catch (IOException e) {

                int noiseLevel = ThreadLocalRandom.current().nextInt(50, 75);

                return noiseLevel + "dB";

            }

        }

    }

    