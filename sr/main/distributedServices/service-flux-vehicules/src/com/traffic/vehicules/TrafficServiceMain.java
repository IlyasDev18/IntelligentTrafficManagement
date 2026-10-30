package com.traffic.vehicules;

import jakarta.xml.ws.Endpoint;

/**
 * Main class to start the SOAP Traffic Flow Service.
 */
public class TrafficServiceMain {

    public static void main(String[] args) {
   
        String address = "http://localhost:8081/flux";

        System.out.println("Starting SOAP Traffic Flow Service...");
        System.out.println("Service will be available at: " + address);

        FluxVehiculeServiceImpl implementor = new FluxVehiculeServiceImpl();


        Endpoint endpoint = Endpoint.publish(address, implementor);

        if (endpoint.isPublished()) {
            System.out.println("Service started successfully!");
            System.out.println("WSDL is available at: " + address + "?wsdl");
        } else {
            System.err.println("Failed to start the service.");
            return;
        }

        System.out.println("Press ENTER to stop the server...");
        try {
            System.in.read();
        } catch (Exception e) {
        }

        System.out.println("Stopping the service...");
        endpoint.stop();
        System.out.println("Service stopped.");
    }
}