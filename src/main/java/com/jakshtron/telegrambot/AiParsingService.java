package com.jakshtron.telegrambot;

import com.jakshtron.telegrambot.dto.TradeSignal;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AiParsingService {

    private final ChatClient chatClient;

    public AiParsingService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public TradeSignal parseRawSignal(String rawText) {
        String systemInstructions = """
            You are a deterministic financial parser. 
            Extract data fields from options signal texts into JSON matching the provided schema template.
            
            Follow these translation rules strictly:
            1. Clean asset tickers: Map "Buy Sensex..." -> asset: "SENSEX"
            2. Infer target values: Extract numbers from strings like "Target 230,270,300+" into an array. Ignore the plus sign.
            3. Detect entry values: Read values matching "@210", "Buy above 155", "at 140" into the entryPrice field.
            4. Invert option codes: Convert variations like "CALL" to "CE" and "PUT" to "PE".
            5. Map stop values: Map entries from "Sl 180" or "SL: 140" to the stopLoss field.
            
            If a text message does not explicitly present option values, return null fields.
            """;

        try {
            return chatClient.prompt()
                    .system(systemInstructions)
                    .user(rawText)
                    .call()
                    .entity(TradeSignal.class); // Spring AI uses Jackson to auto-construct the target schema configuration
        } catch (Exception e) {
            System.err.println("❌ AI Parsing Engine Error: " + e.getMessage());
            e.printStackTrace(); // Prints the core trace to help isolate hidden type conversion issues
            return null;
        }
    }
}