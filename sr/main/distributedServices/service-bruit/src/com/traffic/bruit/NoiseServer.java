package com.traffic.bruit;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The main server class for the Noise Service.
 * It listens for incoming TCP socket connections on a specified port.
 * For each client that connects, it spawns a new thread to handle the communication.
 */
public class NoiseServer {

    private static final int PORT = 6000;
    // Using a fixed thread pool is a good practice to prevent resource exhaustion
    // under heavy load. 10 threads is a reasonable starting point.
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        System.out.println("Noise Server is starting...");
        System.out.println("Listening for connections on port " + PORT);

        // The try-with-resources statement ensures the ServerSocket is closed automatically.
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            // This loop runs indefinitely, keeping the server alive.
            while (true) {
                // The accept() method BLOCKS until a client connects.
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted new connection from " + clientSocket.getRemoteSocketAddress());

                // Create a new task to handle the client and submit it to the thread pool.
                Runnable clientHandler = new NoiseClientHandler(clientSocket);
                threadPool.execute(clientHandler);
            }
        } catch (IOException e) {
            System.err.println("Error in Noise Server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Shutdown the thread pool when the server is stopped.
            threadPool.shutdown();
        }
    }
}