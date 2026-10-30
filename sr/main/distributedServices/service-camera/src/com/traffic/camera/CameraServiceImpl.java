package com.traffic.camera;

import com.city.sensors.camera.CameraEvent;
import com.city.sensors.camera.CameraRegistry;
import com.city.sensors.camera.CameraSensor;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Random;

/**
 * Implementation of the CameraService remote interface.
 * Now delegates camera logic to the sensors-project Camera classes.
 */
public class CameraServiceImpl extends UnicastRemoteObject implements CameraService {

    private final Random random = new Random();
    private static final String[] LANE_IDS = {
        "NORTH-IN", "SOUTH-IN", "EAST-IN", "WEST-IN",
        "NORTH-OUT", "SOUTH-OUT", "EAST-OUT", "WEST-OUT"
    };
    private static final CameraRegistry registry = new CameraRegistry("MAIN-STREET-ROTARY", LANE_IDS);

    /**
     * The constructor must throw RemoteException because UnicastRemoteObject's constructor does.
     */
    public CameraServiceImpl() throws RemoteException {
        super();
    }

    @Override
    public String captureSnapshot(String cameraId) {
        System.out.println("Received request for cameraId: " + cameraId);

        if (cameraId == null || cameraId.trim().isEmpty()) {
            return "ERROR: Invalid camera ID provided.";
        }

        // Use registry to get the camera event for the lane (non-blocking simulation)
        CameraSensor cam = registry.get(cameraId);
        CameraEvent event = (cam != null) ? cam.scan() : null;

        // Keep vehicle count generation as before (camera event currently models incidents, not counts)
        int vehicleCount = 5 + random.nextInt(25); // Between 5 and 30 vehicles
        long timestamp = System.currentTimeMillis();

        String snapshotData = String.format("SNAPSHOT|%s|%d|%d", cameraId, vehicleCount, timestamp);

        if (event != null) {
            System.out.println("Camera event for " + cameraId + ": " + event.toString());
        }
        System.out.println("Sending snapshot data: " + snapshotData);
        return snapshotData;
    }
}