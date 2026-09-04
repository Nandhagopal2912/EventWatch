package com.main;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class Main {
    // A thread-Safe list to store the icoming logs in local for testing

    private static final List<LogEntry> logStorage = new CopyOnWriteArrayList<>();

    static class LogEntry {
        String level;
        String message;

        LogEntry(String level, String message) {
            this.level = level;
            this.message = message;
        }
    }

    public static void main(String[] args) throws IOException {

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/receive", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    // String the information from the body we got from the request using a helper
                    // extractJsonValue
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode json = objectMapper.readTree(body);

                    String level = json.path("level").asText("INFO");
                    String msg = json.path("msg").asText("Unknown Event");



                    // Store the logs in a temporory storage.
                    logStorage.add(new LogEntry(level, msg));

                    // generate the dashborad in terminal using a helper function.
                    generateDashboardReport();

                    // Send a success message to the browser or endpoint.
                    String response = "Log processed successfully";
                    exchange.sendResponseHeaders(200, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                }

                else {
                    exchange.sendResponseHeaders(400, -1); // Method not Allowed

                }
            }
        });

        server.setExecutor(null); // created default executer

        System.out.println("Waiting for logs....(Test with one or two entries first)\n");

        server.start();
    }

    // helper function to create the dashbord

    private static void generateDashboardReport() {
        // 1.Filter out non-errors
        // 2.Group by messages text
        // 3.Count occurences of each error

        Map<String, Long> errorCounts = logStorage.stream()
                .filter(log -> "ERROR".equalsIgnoreCase(log.level))
                .collect(Collectors.groupingBy(log -> log.message, Collectors.counting()));

        // Get the total count of logs processed

        long totalProcessed = logStorage.size();

        // Print the dashboard in terminal

        // Print the dashboard directly to the terminal
        System.out.println("\n================ LIVE CLOUD ALERT DASHBOARD ================");
        System.out.println("Total Logs Processed (All Types): " + totalProcessed);
        System.out.println("------------------------------------------------------------");
        if (errorCounts.isEmpty()) {
            System.out.println(" No critical errors detected yet.");
        } else {
            errorCounts.forEach((errorMessage, count) -> System.out
                    .printf(" 🚨 [ERROR] \"%s\" -> occurred %d time(s)\n", errorMessage, count));
        }
        System.out.println("============================================================");

    }

    // // helper for extracting string values out of a simple json falt string

    // private static String extractJsonValue(String json , String key){
    //     try{
    //         String pattern = "\"" + key + "\":\"";
    //         int start = json.indexOf(pattern)+pattern.length();
    //         int end = json.indexOf("\"", start);
    //         return json.substring(start, end);
    //     } catch(Exception e){
    //         return "Unknown";
    //     }
    // }

}
