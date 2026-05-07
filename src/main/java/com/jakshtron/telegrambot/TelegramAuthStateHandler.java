package com.jakshtron.telegrambot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
public class TelegramAuthStateHandler {

    private final TdJsonService tdJsonService;
    private final MessageBridgeService bridgeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Configuration - Replace with your actual data
    private static final int API_ID = 32727327;
    private static final String API_HASH = "ad78e2682d3b37ce0fd7f8a366f5307d";
    private static final String PHONE_NUMBER = "+918446120325";

    public TelegramAuthStateHandler(TdJsonService tdJsonService, MessageBridgeService bridgeService) {
        this.tdJsonService = tdJsonService;
        this.bridgeService = bridgeService;
    }

    /**
     * Entry point to start the Telegram connection
     */
    public void startAuthentication() {
        System.out.println(">>> Initializing TdJson Connection...");
        // This dummy request triggers the library to start sending Authorization updates
        tdJsonService.send("{\"@type\":\"getAuthorizationState\"}");
    }

    /**
     * Processes every JSON string received from Telegram
     */
    public void onUpdate(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.get("@type").asText();

            if ("updateAuthorizationState".equals(type)) {
                handleState(node.get("authorization_state"));
            }

            // Pass all updates to the bridge service to check for new messages
            System.out.println("RAW TDLIB UPDATE:");
            System.out.println(json);
            bridgeService.processUpdate(json);

        } catch (Exception e) {
            System.err.println("Error parsing update JSON: " + e.getMessage());
        }
    }

    private void handleState(JsonNode stateNode) {
        String stateType = stateNode.get("@type").asText();

        switch (stateType) {
            case "authorizationStateWaitTdlibParameters":
                sendParameters();
                break;

            case "authorizationStateWaitEncryptionKey":
                // In newer versions, this might not be needed, but good to have as a fallback
                tdJsonService.send("{\"@type\":\"checkDatabaseEncryptionKey\"}");
                break;

            case "authorizationStateWaitPhoneNumber":
                sendPhoneNumber();
                break;

            case "authorizationStateWaitCode":
                enterCode();
                break;

            case "authorizationStateReady":
                System.out.println("==========================================");
                System.out.println("SUCCESS: Jakshtron Signal Bridge is LIVE!");
                System.out.println("==========================================");
                loadChats();
                break;

            case "authorizationStateClosing":
                System.out.println("Telegram is closing...");
                break;

            case "authorizationStateClosed":
                System.out.println("Telegram Connection Closed.");
                break;
        }
    }
    private void loadChats() {

        String json = "{"
                + "\"@type\":\"loadChats\","
                + "\"chat_list\":{"
                + "\"@type\":\"chatListMain\""
                + "},"
                + "\"limit\":100"
                + "}";

        System.out.println("Loading chats...");

        tdJsonService.send(json);
    }

//    private void sendParameters() {
//        String json = "{"
//                + "\"@type\":\"setTdlibParameters\","
//                + "\"parameters\":{"
//                + "\"database_directory\":\"tdlib-db\","
//                + "\"use_message_database\":true,"
//                + "\"use_secret_chats\":false,"
//                + "\"api_id\":" + API_ID + ","
//                + "\"api_hash\":\"" + API_HASH + "\","
//                + "\"system_language_code\":\"en\","
//                + "\"device_model\":\"SpringBoot-Server\","
//                + "\"application_version\":\"1.0\""
//                + "}}";
//        tdJsonService.send(json);
//    }
private void sendParameters() {

    String json = "{"
            + "\"@type\":\"setTdlibParameters\","

            + "\"database_directory\":\"tdlib-db\","
            + "\"files_directory\":\"tdlib-files\","

            + "\"use_file_database\":true,"
            + "\"use_chat_info_database\":true,"
            + "\"use_message_database\":true,"
            + "\"use_secret_chats\":false,"

            + "\"api_id\":32727327,"
            + "\"api_hash\":\"ad78e2682d3b37ce0fd7f8a366f5307d\","

            + "\"system_language_code\":\"en\","
            + "\"device_model\":\"Desktop\","
            + "\"system_version\":\"Windows\","
            + "\"application_version\":\"1.0\","

            + "\"enable_storage_optimizer\":true"

            + "}";
    System.out.println("Sending TDLib parameters...");
    System.out.println(json);
    tdJsonService.send(json);
}

    private void sendPhoneNumber() {
        System.out.println("Sending phone number: " + PHONE_NUMBER);
        String json = "{\"@type\":\"setAuthenticationPhoneNumber\",\"phone_number\":\"" + PHONE_NUMBER + "\"}";
        tdJsonService.send(json);
    }

    private void enterCode() {
        Scanner scanner = new Scanner(System.in);
        System.out.print(">>> Enter the Telegram code sent to your device: ");
        String code = scanner.nextLine();
        String json = "{\"@type\":\"checkAuthenticationCode\",\"code\":\"" + code + "\"}";
        tdJsonService.send(json);
    }
}