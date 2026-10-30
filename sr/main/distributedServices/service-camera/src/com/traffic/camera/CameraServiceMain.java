package com.traffic.camera;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Main class to start the RMI Camera Service.
 */
public class CameraServiceMain {
    public static void main(String[] args) {
        try {
            // 1. Create the service implementation instance
            CameraService serviceImpl = new CameraServiceImpl();

            // 2. Create or locate the RMI registry on port 1099 (the default)
            Registry registry = LocateRegistry.createRegistry(1099);
            System.out.println("RMI Registry created on port 1099.");

            // 3. Bind the service implementation to a name in the registry
            // Clients will look up this name to get a reference to the service.
            String serviceName = "CameraService";
            registry.rebind(serviceName, serviceImpl);

            System.out.println("CameraService is ready and bound as '" + serviceName + "'");
            System.out.println("Waiting for client invocations...");
            System.out.println("Press Ctrl-C to stop.");

            // The main thread can now sleep indefinitely. The RMI runtime will
            // handle incoming requests in background threads.
            Thread.sleep(Long.MAX_VALUE);

        } catch (Exception e) {
            System.err.println("CameraService exception:");
            e.printStackTrace();
        }
    }
}