package com.jakshtron.telegrambot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MessageBridgeService {

    private final TdJsonService tdJsonService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, Long> sourceToTargetMessageMap =
            new ConcurrentHashMap<>();

    //     SOURCE AND TARGET GROUP IDs zero to hero channel id below
//    private static final long SOURCE_CHAT_ID = -1002560862430L;
//    private static final long TARGET_CHAT_ID = -1002523140853L;

    private static final long SOURCE_CHAT_ID = -1003944440181L;
    private static final long TARGET_CHAT_ID = -1002523140853L;

    // Prevent duplicate forwarding
    private final Set<Long> processedMessages =
            ConcurrentHashMap.newKeySet();

    // Store original messages for reply lookup
    private final Map<Long, String> messageStore =
            new ConcurrentHashMap<>();

    public MessageBridgeService(TdJsonService tdJsonService) {
        this.tdJsonService = tdJsonService;
    }

    public void processUpdate(String json) {

        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("@type").asText();

            // ONLY HANDLE NEW MESSAGES
            if (!"updateNewMessage".equals(type) && !"updateChatLastMessage".equals(type)) {
                return;
            }

            JsonNode message;

            if ("updateNewMessage".equals(type)) {
                message = node.get("message");
            } else {
                message = node.get("last_message");
            }

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
            // 1. FIND REPLY TARGET FIRST (Moved Up)
            // =====================================
            Long targetReplyMessageId = null;

            if (message.has("reply_to")) {
                JsonNode replyTo = message.get("reply_to");
                long sourceReplyMessageId = 0;

                if (replyTo.has("message_id")) {
                    sourceReplyMessageId = replyTo.get("message_id").asLong();
                } else if (replyTo.has("reply_to_message_id")) {
                    sourceReplyMessageId = replyTo.get("reply_to_message_id").asLong();
                }

                if (sourceReplyMessageId != 0) {
                    targetReplyMessageId = sourceToTargetMessageMap.get(sourceReplyMessageId);
                    System.out.println("SOURCE REPLY ID = " + sourceReplyMessageId);
                    System.out.println("TARGET REPLY ID = " + targetReplyMessageId);
                }
            }

            JsonNode content = message.get("content");
            if (content == null) {
                return;
            }

            String contentType = content.path("@type").asText();
            String text = null;

            // =====================================
            // HANDLE PHOTO (NEW LOGIC)
            // =====================================
            if ("messagePhoto".equals(contentType)) {
                JsonNode sizes = content.get("photo").path("sizes");
                if (sizes.isArray() && sizes.size() > 0) {
                    // Get the largest size
                    JsonNode largestPhoto = sizes.get(sizes.size() - 1);
                    String fileId = largestPhoto.path("photo").path("remote").path("id").asText();

                    // Extract caption using your existing logic style
                    JsonNode captionNode = content.get("caption");
                    String caption = (captionNode != null && captionNode.has("text"))
                            ? captionNode.get("text").asText() : "";

                    System.out.println("FORWARDING PHOTO ID: " + fileId);
//                    sendPhoto(fileId, caption, targetReplyMessageId);
                    // ✅ NEW METHOD (returns message ID)
                    long targetMessageId =
                            sendPhotoAndReturnId(fileId, caption, targetReplyMessageId);

                    // ✅ STORE MAPPING (CRITICAL)
                    if (targetMessageId != 0) {
                        sourceToTargetMessageMap.put(sourceMessageId, targetMessageId);
                        System.out.println("MAPPED PHOTO " + sourceMessageId + " -> " + targetMessageId);
                    }
                    return; // Stop here for photos
                }
            }

            // =====================================
            // NORMAL TEXT + TEXT WITH EMOJIS
            // =====================================
            if ("messageText".equals(contentType)) {
                JsonNode textNode = content.get("text");
                if (textNode != null) {
                    if (textNode.has("text")) {
                        text = textNode.get("text").asText();
                    } else {
                        text = textNode.asText();
                    }
                }
            }
            // =====================================
            // PHOTO CAPTION (Fallback for text extraction)
            // =====================================
            else if ("messagePhoto".equals(contentType)) {
                JsonNode captionNode = content.get("caption");
                if (captionNode != null && captionNode.has("text")) {
                    text = captionNode.get("text").asText();
                }
            }
            // =====================================
            // DOCUMENT CAPTION
            // =====================================
            else if ("messageDocument".equals(contentType)) {
                JsonNode captionNode = content.get("caption");
                if (captionNode != null && captionNode.has("text")) {
                    text = captionNode.get("text").asText();
                }
            }
            // =====================================
            // ANIMATED EMOJI
            // =====================================
            else if ("messageAnimatedEmoji".equals(contentType)) {
                JsonNode emojiNode = content.get("emoji");
                if (emojiNode != null) {
                    text = emojiNode.asText();
                }
            }
            // =====================================
            // STICKER EMOJI
            // =====================================
            else if ("messageSticker".equals(contentType)) {
                JsonNode stickerNode = content.get("sticker");
                if (stickerNode != null && stickerNode.has("emoji")) {
                    text = stickerNode.get("emoji").asText();
                }
            }

            // =====================================
            // DEBUG LOGS & TEXT VALIDATION
            // =====================================
            System.out.println("==============================");
            System.out.println("CONTENT TYPE = " + contentType);
            System.out.println("EXTRACTED TEXT = " + text);

            if (text != null) {
                text.codePoints().forEach(cp ->
                        System.out.println("UNICODE = " + Integer.toHexString(cp)));
            }

            if (text == null || text.isBlank()) {
                return;
            }

            System.out.println("=================================");
            System.out.println("FORWARDING MESSAGE:");
            System.out.println(text);
            System.out.println("=================================");

            // SEND MESSAGE
            long targetMessageId = sendMessage(text, targetReplyMessageId);

            // STORE SOURCE -> TARGET MAPPING
            if (targetMessageId != 0) {
                sourceToTargetMessageMap.put(sourceMessageId, targetMessageId);
                System.out.println("MAPPED SOURCE " + sourceMessageId + " -> TARGET " + targetMessageId);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private long sendPhotoAndReturnId(String fileRemoteId, String caption, Long replyToMessageId) {

        try {

            ObjectMapper mapper = new ObjectMapper();

            ObjectNode root = mapper.createObjectNode();

            root.put("@type", "sendMessage");
            root.put("chat_id", TARGET_CHAT_ID);

            // Reply support
            if (replyToMessageId != null) {
                ObjectNode replyNode = mapper.createObjectNode();
                replyNode.put("@type", "inputMessageReplyToMessage");
                replyNode.put("message_id", replyToMessageId);
                root.set("reply_to", replyNode);
            }

            // Message content
            ObjectNode messageContent = mapper.createObjectNode();
            messageContent.put("@type", "inputMessagePhoto");

            ObjectNode inputFile = mapper.createObjectNode();
            inputFile.put("@type", "inputFileRemote");
            inputFile.put("id", fileRemoteId);

            messageContent.set("photo", inputFile);

            // ✅ CORRECT caption
            ObjectNode captionNode = mapper.createObjectNode();
            captionNode.put("@type", "formattedText");
            captionNode.put("text", caption);

            messageContent.set("caption", captionNode);

            root.set("input_message_content", messageContent);

            String json = mapper.writeValueAsString(root);

            System.out.println("SEND PHOTO JSON:");
            System.out.println(json);

            tdJsonService.send(json);

            // ⏳ WAIT FOR RESPONSE (same logic as sendMessage)
            long start = System.currentTimeMillis();

            while (System.currentTimeMillis() - start < 5000) {

                String response =
                        TdJsonLibrary.INSTANCE
                                .td_json_client_receive(
                                        tdJsonService.getClient(),
                                        1.0
                                );

                if (response == null || response.isBlank()) {
                    continue;
                }

                JsonNode responseNode = mapper.readTree(response);

                if ("message".equals(responseNode.path("@type").asText())) {

                    long targetMessageId =
                            responseNode.path("id").asLong();

                    System.out.println("PHOTO TARGET MESSAGE ID = " + targetMessageId);

                    return targetMessageId;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0;
    }

//    private void    sendPhoto(String fileRemoteId, String caption, Long replyToMessageId) {
//        try {
//            ObjectNode root = objectMapper.createObjectNode();
//            root.put("@type", "sendMessage");
//            root.put("chat_id", TARGET_CHAT_ID);
//
//            if (replyToMessageId != null) {
//                ObjectNode replyNode = objectMapper.createObjectNode();
//                replyNode.put("@type", "inputMessageReplyToMessage");
//                replyNode.put("message_id", replyToMessageId);
//                root.set("reply_to", replyNode);
//            }
//
//            ObjectNode messageContent = objectMapper.createObjectNode();
//            messageContent.put("@type", "inputMessagePhoto");
//
//            // Use the remote file ID so TDLib doesn't have to re-upload the file
//            ObjectNode photo = objectMapper.createObjectNode();
//            photo.put("@type", "inputThumbnail"); // This is a trick to pass the ID
//            // Note: For remote files, you usually use inputFileRemote
//            ObjectNode inputFile = objectMapper.createObjectNode();
//            inputFile.put("@type", "inputFileRemote");
//            inputFile.put("id", fileRemoteId);
//
//            messageContent.set("photo", inputFile);
//
//            // Add the caption
//            ObjectNode captionNode  = objectMapper.createObjectNode();
//            captionNode .put("@type", "formattedText");
//            captionNode .put("text", caption);
//            messageContent.set("caption", captionNode );
//
////            root.set("input_message_content", messageContent);
//            root.set("input_message_content", messageContent);
//
//            tdJsonService.send(objectMapper.writeValueAsString(root));
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    private long sendMessage(String text, Long replyToMessageId
    ) {

        try {

            ObjectMapper mapper = new ObjectMapper();

            ObjectNode root = mapper.createObjectNode();

            root.put("@type", "sendMessage");
            root.put("chat_id", TARGET_CHAT_ID);

            // REAL TELEGRAM REPLY
            if (replyToMessageId != null) {

                ObjectNode replyNode =
                        mapper.createObjectNode();

                replyNode.put(
                        "@type",
                        "inputMessageReplyToMessage"
                );

                replyNode.put(
                        "message_id",
                        replyToMessageId
                );

                root.set("reply_to", replyNode);
            }

            // MESSAGE CONTENT
            ObjectNode messageContent =
                    mapper.createObjectNode();

            messageContent.put(
                    "@type",
                    "inputMessageText"
            );

            ObjectNode formattedText =
                    mapper.createObjectNode();

            formattedText.put(
                    "@type",
                    "formattedText"
            );

            // EMOJIS SAFE HERE
            formattedText.put("text", text);

            messageContent.set(
                    "text",
                    formattedText
            );

            root.set(
                    "input_message_content",
                    messageContent
            );

            String json =
                    mapper.writeValueAsString(root);

            System.out.println("SEND JSON:");
            System.out.println(json);

            tdJsonService.send(json);

            // WAIT FOR RESULT
            long start =
                    System.currentTimeMillis();

            while (System.currentTimeMillis()
                    - start < 5000) {

                String response =
                        TdJsonLibrary.INSTANCE
                                .td_json_client_receive(
                                        tdJsonService.getClient(),
                                        1.0
                                );

                if (response == null
                        || response.isBlank()) {

                    continue;
                }

                JsonNode responseNode =
                        mapper.readTree(response);

                String responseType =
                        responseNode
                                .path("@type")
                                .asText();

                // SUCCESS
                if ("message".equals(responseType)) {

                    long targetMessageId =
                            responseNode
                                    .path("id")
                                    .asLong();

                    System.out.println(
                            "TARGET MESSAGE ID = "
                                    + targetMessageId
                    );

                    return targetMessageId;
                }
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

        return 0;
    }

//    private long sendMessage(String text, Long replyToMessageId) {
//
//        try {
//
//            String escapedText = text
//                    .replace("\\", "\\\\")
//                    .replace("\"", "\\\"")
//                    .replace("\n", "\\n");
//
//            StringBuilder json = new StringBuilder();
//
//            json.append("{");
//            json.append("\"@type\":\"sendMessage\",");
//            json.append("\"chat_id\":")
//                    .append(TARGET_CHAT_ID)
//                    .append(",");
//
//            // REAL TELEGRAM REPLY
//            if (replyToMessageId != null) {
//
//                json.append("\"reply_to\":{");
//                json.append("\"@type\":\"inputMessageReplyToMessage\",");
//                json.append("\"message_id\":")
//                        .append(replyToMessageId);
//                json.append("},");
//            }
//
//            json.append("\"input_message_content\":{");
//            json.append("\"@type\":\"inputMessageText\",");
//            json.append("\"text\":{");
//            json.append("\"@type\":\"formattedText\",");
//            json.append("\"text\":\"")
//                    .append(escapedText)
//                    .append("\"");
//            json.append("}");
//            json.append("}");
//            json.append("}");
//
//            System.out.println("SEND JSON:");
//            System.out.println(json);
//
//            // SEND
//            tdJsonService.send(json.toString());
//
//            // =====================================
//            // WAIT FOR SEND RESULT
//            // =====================================
//
//            long start = System.currentTimeMillis();
//
//            while (System.currentTimeMillis() - start < 5000) {
//
//                String response =
//                        TdJsonLibrary.INSTANCE
//                                .td_json_client_receive(
//                                        tdJsonService.getClient(),
//                                        1.0
//                                );
//
//                if (response == null
//                        || response.isBlank()) {
//                    continue;
//                }
//
//                JsonNode responseNode =
//                        objectMapper.readTree(response);
//
//                String responseType =
//                        responseNode.path("@type").asText();
//
//                // SUCCESS MESSAGE
//                if ("message".equals(responseType)) {
//
//                    long targetMessageId =
//                            responseNode.path("id").asLong();
//
//                    System.out.println(
//                            "TARGET MESSAGE ID = "
//                                    + targetMessageId);
//
//                    return targetMessageId;
//                }
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        return 0;
//    }

//    public void processUpdate(String json) {
//
//        try {
//
//            JsonNode node = objectMapper.readTree(json);
//
//            String type = node.path("@type").asText();
//
//            // ONLY HANDLE NEW MESSAGES
//            if (!"updateNewMessage".equals(type)) {
//                return;
//            }
//
//            JsonNode message = node.get("message");
//
//            if (message == null) {
//                return;
//            }
//
//            long chatId = message.path("chat_id").asLong();
//
//            // ONLY PROCESS SOURCE GROUP
//            if (chatId != SOURCE_CHAT_ID) {
//                return;
//            }
//
//            long messageId = message.path("id").asLong();
//
//            // PREVENT DUPLICATES
//            if (!processedMessages.add(messageId)) {
//                return;
//            }
//
//            JsonNode content = message.get("content");
//
//            if (content == null) {
//                return;
//            }
//
//            String contentType = content.path("@type").asText();
//
//            String text = null;
//
//            // =====================================
//            // NORMAL TEXT MESSAGE
//            // =====================================
//            if ("messageText".equals(contentType)) {
//
//                JsonNode textNode = content.get("text");
//
//                if (textNode != null) {
//
//                    // TDLib formattedText structure
//                    if (textNode.has("text")) {
//
//                        text = textNode
//                                .get("text")
//                                .asText();
//                    }
//
//                    // fallback
//                    else {
//                        text = textNode.asText();
//                    }
//                }
//            }
//
//            // =====================================
//            // PHOTO WITH CAPTION
//            // =====================================
//            else if ("messagePhoto".equals(contentType)) {
//
//                JsonNode captionNode = content.get("caption");
//
//                if (captionNode != null) {
//
//                    if (captionNode.has("text")) {
//
//                        text = captionNode
//                                .get("text")
//                                .asText();
//                    }
//                }
//            }
//
//            // =====================================
//            // DOCUMENT WITH CAPTION
//            // =====================================
//            else if ("messageDocument".equals(contentType)) {
//
//                JsonNode captionNode = content.get("caption");
//
//                if (captionNode != null) {
//
//                    if (captionNode.has("text")) {
//
//                        text = captionNode
//                                .get("text")
//                                .asText();
//                    }
//                }
//            }
//
//            // DEBUG LOGS
//            System.out.println("=================================");
//            System.out.println("CONTENT TYPE = " + contentType);
//            System.out.println("EXTRACTED TEXT = ");
//            System.out.println(text);
//            System.out.println("=================================");
//
//            // IGNORE EMPTY TEXT
//            if (text == null || text.isBlank()) {
//                return;
//            }
//
//            // STORE ORIGINAL MESSAGE
//            messageStore.put(messageId, text);
//
//            // =====================================
//            // HANDLE REPLY MESSAGES
//            // =====================================
//            if (message.has("reply_to")) {
//
//                JsonNode replyTo = message.get("reply_to");
//
//                long replyMessageId = 0;
//
//                // TDLib structure
//                if (replyTo.has("message_id")) {
//
//                    replyMessageId =
//                            replyTo.get("message_id").asLong();
//                }
//
//                // Alternate structure
//                else if (replyTo.has("reply_to_message_id")) {
//
//                    replyMessageId =
//                            replyTo.get("reply_to_message_id")
//                                    .asLong();
//                }
//
//                if (replyMessageId != 0) {
//
//                    String originalMessage =
//                            messageStore.get(replyMessageId);
//
//                    if (originalMessage != null
//                            && !originalMessage.isBlank()) {
//
//                        text =
//                                "REPLY TO:\n"
//                                        + originalMessage
//                                        + "\n\nUPDATE:\n"
//                                        + text;
//                    }
//                }
//            }
//
//            System.out.println("=================================");
//            System.out.println("FINAL MESSAGE TO SEND:");
//            System.out.println(text);
//            System.out.println("=================================");
//
//            sendMessage(text);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

//    private void sendMessage(String text) {
//
//        String escapedText = text
//                .replace("\\", "\\\\")
//                .replace("\"", "\\\"")
//                .replace("\n", "\\n");
//
//        String json = "{"
//                + "\"@type\":\"sendMessage\","
//                + "\"chat_id\":" + TARGET_CHAT_ID + ","
//                + "\"input_message_content\":{"
//                + "\"@type\":\"inputMessageText\","
//                + "\"text\":{"
//                + "\"@type\":\"formattedText\","
//                + "\"text\":\"" + escapedText + "\""
//                + "}"
//                + "}"
//                + "}";
//
//        tdJsonService.send(json);
//    }
}


//package com.jakshtron.telegrambot;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.springframework.stereotype.Service;
//
//@Service
//public class MessageBridgeService {
//
//    private final TdJsonService tdJsonService;
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    // Your actual group IDs
//    private static final long SOURCE_CHAT_ID = -1002560862430L;
//    private static final long TARGET_CHAT_ID = -1002523140853L;
//
//    public MessageBridgeService(TdJsonService tdJsonService) {
//        this.tdJsonService = tdJsonService;
//    }
//
//    /**
//     * Updated to accept String instead of TdApi.Object
//     */
//    public void processUpdate(String json) {
//
//        try {
//
//            JsonNode node = objectMapper.readTree(json);
//
//            String type = node.get("@type").asText();
//
//            System.out.println("UPDATE TYPE: " + type);
//
//            JsonNode message = null;
//
//            // CASE 1
//            if ("updateNewMessage".equals(type)) {
//                message = node.get("message");
//            }
//
//            // CASE 2
////            else if ("updateChatLastMessage".equals(type)) {
////                message = node.get("last_message");
////            }
//
//            // Ignore other updates
//            if (message == null) {
//                return;
//            }
//
//            long chatId = message.get("chat_id").asLong();
//
//            System.out.println("CHAT ID: " + chatId);
//
//            if (chatId == SOURCE_CHAT_ID) {
//
//                JsonNode content = message.get("content");
//
//                if (content == null) {
//                    return;
//                }
//
//                String contentType = content.get("@type").asText();
//
//                System.out.println("CONTENT TYPE: " + contentType);
//
//                // TEXT MESSAGE
//                if ("messageText".equals(contentType)) {
//
//                    String text = content
//                            .get("text")
//                            .get("text")
//                            .asText();
//
//                    System.out.println("TEXT RECEIVED:");
//                    System.out.println(text);
//
//                    sendMessage(text);
//                }
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//    private void sendMessage(String text) {
//
//        String escapedText = text.replace("\"", "\\\"");
//
//        String json = "{"
//                + "\"@type\":\"sendMessage\","
//                + "\"chat_id\":" + TARGET_CHAT_ID + ","
//                + "\"input_message_content\":{"
//                + "\"@type\":\"inputMessageText\","
//                + "\"text\":{"
//                + "\"@type\":\"formattedText\","
//                + "\"text\":\"" + escapedText + "\""
//                + "}"
//                + "}"
//                + "}";
//
//        System.out.println("MESSAGE SENT TO TARGET GROUP");
//        System.out.println(json);
//
//        tdJsonService.send(json);
//    }
//
//
//}