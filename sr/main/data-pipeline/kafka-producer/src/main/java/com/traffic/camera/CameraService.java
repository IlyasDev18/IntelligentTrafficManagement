package com.traffic.camera;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The remote interface for the Camera Service.
 * This is the contract that clients will use to call methods on the server.
 */
public interface CameraService extends Remote {

    /**
     * Captures a traffic snapshot from a specific camera.
     *
     * @param cameraId The ID of the camera to query.
     * @return A string representing the snapshot data.
     * @throws RemoteException If a communication error occurs.
     */
    String captureSnapshot(String cameraId) throws RemoteException;
}