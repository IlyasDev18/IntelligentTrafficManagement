package com.traffic.pollution;

import com.city.sensors.pollution.PollutionSensor;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Random;


@Path("/pollution")
public class PollutionResource {

    private static final String SENSOR_ID = "POLL-MAIN-STREET-ROTARY";
    private static final PollutionSensor sensor = new PollutionSensor("POLL-MAIN-STREET-ROTARY");
    private final Random random = new Random();

    /**
     * This method handles HTTP GET requests to /api/pollution
     * The @Produces annotation specifies that the response will be in JSON format.
     *
     * @return A JSON string representing the pollution data.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getPollutionLevel() {
        // Simulate vehicle count for the sensor algorithm (approx 50-150 cars)
        int totalVehicles = 50 + random.nextInt(100);
        sensor.measure(totalVehicles);
        return sensor.toJsonString();
    }
}