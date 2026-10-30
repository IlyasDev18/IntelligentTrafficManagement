package com.traffic.feux;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FeuxServer {

    private static final int PORT = 7000;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        System.out.println("Traffic Light (Feux) Server is starting...");
        System.out.println("Listening for connections on port " + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted new connection from " + clientSocket.getRemoteSocketAddress());
                threadPool.execute(new FeuxClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Error in Feux Server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }
}