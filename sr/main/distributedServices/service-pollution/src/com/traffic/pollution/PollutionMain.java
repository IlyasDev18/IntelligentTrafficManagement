package com.traffic.pollution;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.IOException;
import java.net.URI;

/**
 * Main class to start the RESTful Pollution Service.
 */
public class PollutionMain {


    public static final String BASE_URI = "http://localhost:8082/api/";

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public static HttpServer startServer() {
      
        final ResourceConfig rc = new ResourceConfig().packages("com.traffic.pollution");


        return GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        final HttpServer server = startServer();
        System.out.println(String.format("Jersey app started with WADL available at "
                + "%sapplication.wadl%nHit Ctrl-C to stop it...", BASE_URI));
        System.out.println("Try accessing the endpoint at: " + BASE_URI + "pollution");
        
        
        System.out.println("Press ENTER to stop the server...");
        System.in.read();
        
        server.shutdownNow();
    }
}
