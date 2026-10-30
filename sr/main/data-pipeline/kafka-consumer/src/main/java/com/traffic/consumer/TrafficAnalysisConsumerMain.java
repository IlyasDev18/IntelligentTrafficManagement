package com.traffic.consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.traffic.consumer.model.CitySnapshot;
import com.traffic.consumer.model.LaneData;
import com.traffic.consumer.model.RecommendedAction;
import com.traffic.db.SimpleDriverDataSource;
import com.traffic.db.dao.TrafficDataDAO;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class TrafficAnalysisConsumerMain {

    private static final String TOPIC_NAME = "city-snapshots";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String GROUP_ID = "traffic-analysis-group-reread-2";

    // ── Database Settings ────────────────────────────────────────────────────
    private static final String DB_URL      = "jdbc:mysql://localhost:3306/traffic_db?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true";
    private static final String DB_USER     = "ilyas";
    private static final String DB_PASSWORD = "MyPass123!";

    public static void main(String[] args) {
        // 1. Create Consumer Properties
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // 2. Create the Kafka Consumer
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // ── Initialize Database Connection ─────────────────────────────────────
        System.out.println("[INIT] Connecting to database...");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("[WARN] MySQL driver not found on classpath: " + e.getMessage());
        }
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(DB_URL, DB_USER, DB_PASSWORD);
        TrafficDataDAO trafficDAO = new TrafficDataDAO(dataSource);

        try {
            trafficDAO.initSchema();
            System.out.println("[DB] Database schema initialized successfully.");
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to init DB schema: " + e.getMessage());
        }

        // --- Broker Health Check ---
        System.out.println("--- Performing Broker Health Check ---");
        if (!performBrokerHealthCheck(consumer)) {
            System.err.println("Broker health check failed. Aborting application.");
            consumer.close();
            System.exit(1);
        }
        System.out.println("--- Broker Health Check Successful ---\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing consumer...");
            consumer.close();
            System.out.println("Consumer closed.");
        }));

        // 3. Subscribe to the topic
        consumer.subscribe(Collections.singletonList(TOPIC_NAME));
        System.out.println("Subscribed to topic '" + TOPIC_NAME + "'. Waiting for messages...");

        // 4. Poll for new messages
        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(2000));

                for (ConsumerRecord<String, String> record : records) {
                    System.out.println("\n--- Received New Snapshot ---");
                    System.out.printf("Topic: %s, Partition: %d, Offset: %d, Key: %s%n",
                            record.topic(), record.partition(), record.offset(), record.key());

                    // 5. Process the message
                    try {
                        CitySnapshot snapshot = gson.fromJson(record.value(), CitySnapshot.class);
                        printSnapshot(snapshot);

                        // ── Persist to Database ──────────────────────────────────────────
                        try {
                            long snapshotId = trafficDAO.insertSnapshot(convertToRotarySnapshot(snapshot));
                            System.out.println("[DB] Saved Snapshot ID: " + snapshotId);
                        } catch (SQLException e) {
                            System.err.println("[DB] Error saving snapshot: " + e.getMessage());  
                        }

                    } catch (Exception e) {
                        System.err.println("Failed to parse JSON message: " + record.value());
                        e.printStackTrace();
                    }
                }
            }
        } finally {
            consumer.close();
        }
    }
    
    private static void printSnapshot(CitySnapshot snapshot) {
        System.out.println("== Zone: " + snapshot.getZoneId() + " (" + snapshot.getType() + ") at " + snapshot.getTimestamp() + " ==");

        // Pollution
        System.out.println("\n[ Pollution Data ]");
        System.out.println(snapshot.getPollutionData());

        // Traffic Lights
        System.out.println("\n[ Traffic Light Status ]");
        if (snapshot.getTrafficLightData() != null) {
            for (Map.Entry<String, String> entry : snapshot.getTrafficLightData().entrySet()) {
                System.out.println("  - " + entry.getKey() + ": " + entry.getValue());
            }
        }

        // Lane Data
        System.out.println("\n[ Lane Data ]");
        if (snapshot.getLaneData() != null) {
            for (Map.Entry<String, LaneData> entry : snapshot.getLaneData().entrySet()) {
                LaneData lane = entry.getValue();
                System.out.println("  - Lane: " + entry.getKey());
                System.out.println("    - Flux: " + lane.getFlux());
                System.out.println("    - Vehicle Count: " + lane.getVehicleCount());
                System.out.println("    - Noise: " + lane.getNoise());
                System.out.println("    - Camera Status: " + lane.getCameraStatus());
                if (lane.getCameraDetails() != null) {
                    System.out.println("    - Camera Details: " + lane.getCameraDetails());
                }
            }
        }

        // Recommended Actions
        System.out.println("\n[ Recommended Actions ]");
        if (snapshot.getRecommendedActions() != null && !snapshot.getRecommendedActions().isEmpty()) {
            for (RecommendedAction action : snapshot.getRecommendedActions()) {
                System.out.println("  - Action: " + action.getAction());
                System.out.println("    - Target: " + action.getTarget());
                System.out.println("    - New Value: " + action.getNewValue());
                System.out.println("    - Reason: " + action.getReason());
            }
        } else {
            System.out.println("  No actions recommended.");
        }
    }

    private static boolean performBrokerHealthCheck(KafkaConsumer<String, String> consumer) {
        try {
            System.out.println("Attempting to connect to broker at " + BOOTSTRAP_SERVERS + "...");
            Map<String, List<PartitionInfo>> topics = consumer.listTopics(Duration.ofSeconds(10));
            System.out.println("✅ Connection successful.");

            System.out.println("\n--- Broker Information ---");
            System.out.println("Found " + topics.size() + " topics on the broker.");

            if (topics.containsKey(TOPIC_NAME)) {
                System.out.println("✅ Target topic '" + TOPIC_NAME + "' found.");
                List<PartitionInfo> partitionInfos = topics.get(TOPIC_NAME);
                System.out.println("  -> It has " + partitionInfos.size() + " partition(s).");
                partitionInfos.forEach(p -> System.out.println("     - Partition " + p.partition() + " with leader on broker " + p.leader().id()));
            } else {
                System.err.println("❌ CRITICAL: Target topic '" + TOPIC_NAME + "' does not exist on the broker!");
                System.err.println("  Available topics are: " + topics.keySet());
                return false;
            }
            return true;

        } catch (Exception e) {
            System.err.println("❌ Failed to connect to Kafka broker or retrieve metadata.");
            System.err.println("  Please check the following:");
            System.err.println("  1. Is the Kafka broker running and accessible?");
            System.err.println("  2. Is the BOOTSTRAP_SERVERS config ('" + BOOTSTRAP_SERVERS + "') correct?");
            System.err.println("  3. Is there a network issue (e.g., firewall, Docker networking)?");
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to convert CitySnapshot to the format expected by TrafficDataDAO
  private static com.city.sensors.model.RotarySnapshot convertToRotarySnapshot(CitySnapshot snapshot) {
    return new com.city.sensors.model.RotarySnapshot(
            snapshot.getZoneId(),
            snapshot.getTimestamp(),
            snapshot.getPollutionData(),
            snapshot.getTrafficLightData(),
            convertLaneData(snapshot.getLaneData()),
            null // or Collections.emptyList() if you prefer
    );
}

    // Helper method to convert LaneData from consumer model to sensors model
   private static Map<String, com.city.sensors.model.LaneData> convertLaneData(Map<String, LaneData> laneData) {
    if (laneData == null) return null;

    Map<String, com.city.sensors.model.LaneData> converted = new HashMap<>();

    for (Map.Entry<String, LaneData> entry : laneData.entrySet()) {
        LaneData source = entry.getValue();

        com.city.sensors.model.LaneData target =
                new com.city.sensors.model.LaneData(
                        entry.getKey(),
                        source.getFlux(),
                        source.getVehicleCount(),
                        source.getNoise(),
                        source.getCameraStatus(),
                        source.getCameraDetails()
                );

        converted.put(entry.getKey(), target);
    }

    return converted;
}
}