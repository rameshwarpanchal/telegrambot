package com.jakshtron.telegrambot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jakshtron.telegrambot.dto.TradeSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor // Automatically generates constructor for tdJsonService and aiParsingService
public class MessageBridgeService {

    private final TdJsonService tdJsonService;
    private final AiParsingService aiParsingService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, Long> sourceToTargetMessageMap = new ConcurrentHashMap<>();

    private static final long SOURCE_CHAT_ID = -1003944440181L;
    private static final long TARGET_CHAT_ID = -1002523140853L;

    // Prevent duplicate forwarding
    private final Set<Long> processedMessages = ConcurrentHashMap.newKeySet();

    public void processUpdate(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("@type").asText();

            // ONLY HANDLE NEW MESSAGES
            if (!"updateNewMessage".equals(type) && !"updateChatLastMessage".equals(type)) {
                return;
            }

            JsonNode message = "updateNewMessage".equals(type) ? node.get("message") : node.get("last_message");
            if (message == null) return;

            long chatId = message.path("chat_id").asLong();

            // LOGGING FOR SOURCE AND TARGET CHANNELS
            if (chatId == SOURCE_CHAT_ID || chatId == TARGET_CHAT_ID) {
                System.out.println("--- [" + (chatId == SOURCE_CHAT_ID ? "SOURCE" : "TARGET") + "] UPDATE ---");
            }

            // ONLY SOURCE GROUP
            if (chatId != SOURCE_CHAT_ID) {
                return;
            }

            long sourceMessageId = message.path("id").asLong();

            // PREVENT DUPLICATES
            if (!processedMessages.add(sourceMessageId)) {
                return;
            }

            // =====================================
            // 1. FIND REPLY TARGET FIRST
            // =====================================
            Long targetReplyMessageId = null;

            if (message.has("reply_to")) {
                JsonNode replyTo = message.get("reply_to");
                long sourceReplyMessageId = replyTo.has("message_id")
                        ? replyTo.get("message_id").asLong() : replyTo.path("reply_to_message_id").asLong();

                if (sourceReplyMessageId != 0) {
                    targetReplyMessageId = sourceToTargetMessageMap.get(sourceReplyMessageId);
                    System.out.println("SOURCE REPLY ID = " + sourceReplyMessageId);
                    System.out.println("TARGET REPLY ID = " + targetReplyMessageId);
                }
            }

            JsonNode content = message.get("content");
            if (content == null) return;

            String contentType = content.path("@type").asText();
            String text = null;

            // =====================================
            // HANDLE PHOTO (FORWARD IMMEDIATELY)
            // =====================================
            if ("messagePhoto".equals(contentType)) {
                JsonNode sizes = content.get("photo").path("sizes");
                if (sizes.isArray() && sizes.size() > 0) {
                    JsonNode largestPhoto = sizes.get(sizes.size() - 1);
                    String fileId = largestPhoto.path("photo").path("remote").path("id").asText();

                    JsonNode captionNode = content.get("caption");
                    String caption = (captionNode != null && captionNode.has("text")) ? captionNode.get("text").asText() : "";

                    System.out.println("FORWARDING PHOTO ID: " + fileId);
                    long targetMessageId = sendPhotoAndReturnId(fileId, caption, targetReplyMessageId);

                    if (targetMessageId != 0) {
                        sourceToTargetMessageMap.put(sourceMessageId, targetMessageId);
                        System.out.println("MAPPED PHOTO " + sourceMessageId + " -> " + targetMessageId);
                    }
                    return;
                }
            }

            // =====================================
            // NORMAL TEXT + TEXT WITH EMOJIS EXTRACTION
            // =====================================
            if ("messageText".equals(contentType)) {
                JsonNode textNode = content.get("text");
                if (textNode != null) {
                    text = textNode.has("text") ? textNode.get("text").asText() : textNode.asText();
                }
            }
            else if ("messagePhoto".equals(contentType) || "messageDocument".equals(contentType)) {
                JsonNode captionNode = content.get("caption");
                if (captionNode != null && captionNode.has("text")) {
                    text = captionNode.get("text").asText();
                }
            }

            // DEBUG LOGS & TEXT VALIDATION
            System.out.println("==============================");
            System.out.println("CONTENT TYPE = " + contentType);
            System.out.println("EXTRACTED TEXT = " + text);

            if (text == null || text.isBlank()) {
                return;
            }

            System.out.println("=================================");
            System.out.println("FORWARDING MESSAGE:");
            System.out.println(text);
            System.out.println("=================================");

            // Send the raw text to target channel instantly to preserve telegram logs
            long targetMessageId = sendMessage(text, targetReplyMessageId);

            if (targetMessageId != 0) {
                sourceToTargetMessageMap.put(sourceMessageId, targetMessageId);
                System.out.println("MAPPED SOURCE " + sourceMessageId + " -> TARGET " + targetMessageId);
            }

            // ========================================================
            // 🤖 SMART FILTERING: CHECK IF THIS IS A GENUINE CALL OR CLUTTER
            // ========================================================
            if (isActualTradingSignal(text)) {
                System.out.println("🚀 [SIGNAL VALIDATED] Forwarding raw text payload to Gemini extraction pipeline...");
                TradeSignal signal = aiParsingService.parseRawSignal(text);

                if (signal != null && signal.asset() != null) {
                    System.out.println("✨ [AI STRUCTURAL ANALYSIS SUCCESSFUL]");
                    System.out.println("-> Target Underlying Ticker: " + signal.asset());
                    System.out.println("-> Option Contract: Strike " + signal.strikePrice() + " " + signal.optionType());
                    System.out.println("-> Calculated Trigger: " + signal.entryType() + " at " + signal.entryPrice());
                    System.out.println("-> Extracted Target Levels: " + signal.targets());
                    System.out.println("-> Validated Invalidation/SL: " + signal.stopLoss());

                    // TODO: Wire 'signal' to your table stream controller here
                }
            } else {
                System.out.println("🛑 [FILTERED OUT] Message skipped by AI (Detected as an LTP status update or milestone target completion banner).");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 🔍 NEW METHOD: Validates structural requirements before consuming AI resource quota
     */
    private boolean isActualTradingSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String lowerText = text.toLowerCase().trim();

        // 1. Instantly block ongoing tracking metrics or hit targets
        if (lowerText.contains("done") ||
                lowerText.contains("hit") ||
                lowerText.contains("ltp") ||
                lowerText.contains("running") ||
                lowerText.contains("achieved") ||
                lowerText.contains("book profit") ||
                lowerText.contains("stop trailing") ||
                lowerText.contains("target done")) {
            return false;
        }

        // 2. Strict Core Asset Ticker Validation Layer
        boolean containsAsset = lowerText.contains("nifty") ||
                lowerText.contains("banknifty") ||
                lowerText.contains("bank nifty") ||
                lowerText.contains("sensex") ||
                lowerText.contains("finnifty") ||
                lowerText.contains("midcpnifty");

        if (!containsAsset) {
            return false;
        }

        // 3. Strict Contract Option Derivative Type Validation Layer
        boolean containsOptionType = lowerText.contains("ce") ||
                lowerText.contains("pe") ||
                lowerText.contains("call") ||
                lowerText.contains("put");

        if (!containsOptionType) {
            return false;
        }

        // 4. Structural Verification: Look for active execution thresholds or numeric triggers
        boolean hasOrderTriggers = lowerText.contains("buy") ||
                lowerText.contains("above") ||
                lowerText.contains("abv") ||
                lowerText.contains("sl") ||
                lowerText.contains("stop") ||
                lowerText.contains("tgt") ||
                lowerText.contains("target") ||
                lowerText.contains("@");

        return hasOrderTriggers;
    }

    private long sendPhotoAndReturnId(String fileRemoteId, String caption, Long replyToMessageId) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("@type", "sendMessage");
            root.put("chat_id", TARGET_CHAT_ID);

            if (replyToMessageId != null) {
                ObjectNode replyNode = mapper.createObjectNode();
                replyNode.put("@type", "inputMessageReplyToMessage");
                replyNode.put("message_id", replyToMessageId);
                root.set("reply_to", replyNode);
            }

            ObjectNode messageContent = mapper.createObjectNode();
            messageContent.put("@type", "inputMessagePhoto");

            ObjectNode inputFile = mapper.createObjectNode();
            inputFile.put("@type", "inputFileRemote");
            inputFile.put("id", fileRemoteId);
            messageContent.set("photo", inputFile);

            ObjectNode captionNode = mapper.createObjectNode();
            captionNode.put("@type", "formattedText");
            captionNode.put("text", caption);
            messageContent.set("caption", captionNode);

            root.set("input_message_content", messageContent);
            tdJsonService.send(mapper.writeValueAsString(root));

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                String response = TdJsonLibrary.INSTANCE.td_json_client_receive(tdJsonService.getClient(), 1.0);
                if (response == null || response.isBlank()) continue;

                JsonNode responseNode = mapper.readTree(response);
                if ("message".equals(responseNode.path("@type").asText())) {
                    return responseNode.path("id").asLong();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private long sendMessage(String text, Long replyToMessageId) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("@type", "sendMessage");
            root.put("chat_id", TARGET_CHAT_ID);

            if (replyToMessageId != null) {
                ObjectNode replyNode = mapper.createObjectNode();
                replyNode.put("@type", "inputMessageReplyToMessage");
                replyNode.put("message_id", replyToMessageId);
                root.set("reply_to", replyNode);
            }

            ObjectNode messageContent = mapper.createObjectNode();
            messageContent.put("@type", "inputMessageText");

            ObjectNode formattedText = mapper.createObjectNode();
            formattedText.put("@type", "formattedText");
            formattedText.put("text", text);

            messageContent.set("text", formattedText);
            root.set("input_message_content", messageContent);

            tdJsonService.send(mapper.writeValueAsString(root));

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                String response = TdJsonLibrary.INSTANCE.td_json_client_receive(tdJsonService.getClient(), 1.0);
                if (response == null || response.isBlank()) continue;

                JsonNode responseNode = mapper.readTree(response);
                if ("message".equals(responseNode.path("@type").asText())) {
                    return responseNode.path("id").asLong();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}