package com.jakshtron.telegrambot;

import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class TdJsonService {

    private Pointer client;
    private final TelegramAuthStateHandler authHandler;
    private boolean isRunning = true;

    // We use @Lazy to avoid circular dependency since authHandler and service call each other
    public TdJsonService(@Lazy TelegramAuthStateHandler authHandler) {
        this.authHandler = authHandler;
    }

    @PostConstruct
    public void init() {
        // 1. Create the TDLib client instance
        this.client = TdJsonLibrary.INSTANCE.td_json_client_create();

        // 2. Start the reception loop in a separate thread
        // This thread stays alive for the duration of the application
        Thread receiveThread = new Thread(() -> {
            System.out.println(">>> TDLib Receive Thread started.");
            while (isRunning) {
                // Poll for updates with a 10-second timeout
                String update = TdJsonLibrary.INSTANCE.td_json_client_receive(client, 10.0);

                if (update != null && !update.isEmpty()) {
                    // Send every raw JSON string to the AuthHandler/Bridge logic
                    authHandler.onUpdate(update);
                }
            }
            System.out.println(">>> TDLib Receive Thread stopped.");
        });

        receiveThread.setName("TdJson-Receiver");
        receiveThread.setDaemon(true); // Ensures thread doesn't block JVM shutdown
        receiveThread.start();
    }

    /**
     * Sends a JSON request to TDLib
     */
    public void send(String json) {
        if (client != null) {
            TdJsonLibrary.INSTANCE.td_json_client_send(client, json);
        }
    }
    public Pointer getClient() {
        return client;
    }

    /**
     * Executes a synchronous JSON request (e.g. for simple queries)
     */
    public String execute(String json) {
        if (client != null) {
            return TdJsonLibrary.INSTANCE.td_json_client_execute(client, json);
        }
        return null;
    }

    @PreDestroy
    public void stop() {
        isRunning = false;
        if (client != null) {
            // Standard TDLib shutdown procedure
            send("{\"@type\":\"close\"}");
            System.out.println(">>> Sent Close request to TDLib.");
        }
    }
}