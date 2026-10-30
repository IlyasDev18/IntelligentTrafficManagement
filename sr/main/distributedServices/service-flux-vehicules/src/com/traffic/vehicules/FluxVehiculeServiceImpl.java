package com.traffic.vehicules;

import com.city.sensors.model.LaneData;
import com.city.sensors.model.RotarySnapshot;
import com.city.sensors.simulator.RotarySimulator;
import jakarta.jws.WebMethod;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import java.util.Random;


@WebService
@SOAPBinding(style = SOAPBinding.Style.DOCUMENT) // Default and most common style
public class FluxVehiculeServiceImpl {

    private final Random random = new Random();

    private static final RotarySimulator simulator = new RotarySimulator("FLUX-SERVICE-ZONE");

    /**
     * This method will be exposed as a SOAP operation.
     * The @WebMethod annotation is optional for public methods but is good practice for clarity.
     *
     * @return A string containing fake traffic flow data.
     */
    @WebMethod
    public String getTrafficFlow() {
        System.out.println("Received request for getTrafficFlow()");

      
        RotarySnapshot snapshot = simulator.tick();
        
        String roadId = "NORTH-IN";
        LaneData lane = snapshot.getLaneData().get(roadId);
        int vehicleCount = (lane != null) ? lane.getVehicleCount() : 0;
        String fluxLevel = (lane != null) ? lane.getFlux() : "UNKNOWN";

        String dataPayload = String.format("%s|%d|%s|%d", roadId, vehicleCount, fluxLevel, snapshot.getTimestamp());

        System.out.println("Sending response: " + dataPayload);
        return dataPayload;
    }

    /**
     * Another example method to show how to handle parameters.
     *
     * @param roadIdentifier The ID of the road to query.
     * @return Traffic flow for the specific road.
     */
    @WebMethod
    public String getTrafficFlowForRoad(String roadIdentifier) {
        System.out.println("Received request for getTrafficFlowForRoad() with road: " + roadIdentifier);
        
        if (roadIdentifier == null || roadIdentifier.isEmpty()) {
            return "ERROR: Road identifier cannot be empty.";
        }

        RotarySnapshot snapshot = simulator.tick();
        LaneData lane = snapshot.getLaneData().get(roadIdentifier);
        int vehicleCount = (lane != null) ? lane.getVehicleCount() : 0;
        String fluxLevel = (lane != null) ? lane.getFlux() : "UNKNOWN";
        
        String dataPayload = String.format("%s|%d|%s|%d", roadIdentifier, vehicleCount, fluxLevel, snapshot.getTimestamp());
        
        System.out.println("Sending response: " + dataPayload);
        return dataPayload;
    }
}