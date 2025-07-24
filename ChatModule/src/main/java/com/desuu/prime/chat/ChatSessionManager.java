package com.desuu.prime.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(ChatSessionManager.class);
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;

    private static String vertexApiUrl;

    private static final Map<Long, String> systemPrompts = new ConcurrentHashMap<>();
    private static final Map<Long, List<MessageEntry>> histories = new ConcurrentHashMap<>();

    public static void init(String projectNumber, String location, String endpointId) {
        if (projectNumber == null || projectNumber.isBlank()) {
            logger.error("FATAL: gcp.project_number is not configured. Chat functionality will be disabled.");
            vertexApiUrl = null;
            return;
        }
        if (endpointId == null || endpointId.isBlank()) {
            logger.error("FATAL: vertex.endpoint_id is not configured. Chat functionality will be disabled.");
            vertexApiUrl = null;
            return;
        }

        vertexApiUrl = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/endpoints/%s:streamGenerateContent",
                location, projectNumber, location, endpointId
        );

        logger.info("Constructed Vertex AI Endpoint URL: {}", vertexApiUrl);

        GoogleAuthManager.getInstance();
        logger.info("ChatSessionManager initialized for Vertex Endpoint ID {}", endpointId);
    }

    public static void setSystemPrompt(long channelId, String prompt) {
        systemPrompts.put(channelId, prompt);
        histories.remove(channelId);
    }

    public static void handleMessage(MessageReceivedEvent event) {
        if (vertexApiUrl == null) {
            return;
        }
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }

        long channelId = event.getChannel().getIdLong();
        String system = systemPrompts.get(channelId);
        if (system == null) {
            return;
        }

        String accessToken = GoogleAuthManager.getAccessToken();
        if (accessToken == null) {
            logger.error("Could not obtain Google Cloud access token. Check authentication configuration.");
            event.getChannel().sendMessage("⚠️ AI authentication failed. Please check the bot's logs.").queue();
            return;
        }

        String userMessage = event.getMessage().getContentDisplay();
        // --- UPDATED: Format the user's message to include their name ---
        String formattedUserMessage = String.format("%s: %s", event.getAuthor().getName(), userMessage);

        histories.computeIfAbsent(channelId, k -> new ArrayList<>());
        List<MessageEntry> history = histories.get(channelId);

        ObjectNode payload = mapper.createObjectNode();
        ArrayNode contentsArray = mapper.createArrayNode();

        if (history.isEmpty() && system != null && !system.isBlank()) {
            contentsArray.add(createContentNode("user", system));
            contentsArray.add(createContentNode("model", "Understood. I will follow those instructions."));
        }

        for (MessageEntry entry : history) {
            contentsArray.add(createContentNode(entry.role, entry.content));
        }
        // Use the formatted message for the current turn
        contentsArray.add(createContentNode("user", formattedUserMessage));

        ObjectNode generationConfig = mapper.createObjectNode();
        generationConfig.put("temperature", 0.9);
        generationConfig.put("topP", 1.0);
        generationConfig.put("maxOutputTokens", 2048);

        ArrayNode safetySettings = mapper.createArrayNode();
        safetySettings.add(createSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"));
        safetySettings.add(createSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE"));
        safetySettings.add(createSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"));
        safetySettings.add(createSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"));

        payload.set("contents", contentsArray);
        payload.set("generationConfig", generationConfig);
        payload.set("safetySettings", safetySettings);

        Request request = new Request.Builder()
                .url(vertexApiUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.error("Vertex AI request failed", e);
                event.getChannel().sendMessage("⚠️ Error contacting AI: " + e.getMessage()).queue();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorBody = "";
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody != null) {
                            errorBody = responseBody.string();
                        }
                    }
                    logger.warn("Vertex AI API error: HTTP {} for URL: {}. Response: {}", response.code(), response.request().url(), errorBody);
                    event.getChannel().sendMessage("⚠️ AI API error: " + response.code() + ". Check logs for details.").queue();
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) return;

                    String responseStream = responseBody.string();
                    JsonNode root = mapper.readTree(responseStream);
                    logger.info("Received Vertex AI stream response: {}", root.toString());

                    StringBuilder fullReply = new StringBuilder();
                    if (root.isArray()) {
                        for (JsonNode chunk : root) {
                            JsonNode textNode = chunk.path("candidates").path(0).path("content").path("parts").path(0).path("text");
                            if (textNode.isTextual()) {
                                fullReply.append(textNode.asText());
                            }
                        }
                    }

                    String reply = fullReply.toString();
                    if (reply.isEmpty()) {
                        logger.error("Failed to extract any text from Vertex AI response. Full response: {}", root.toString());
                        event.getChannel().sendMessage("⚠️ Error: Could not parse the AI's response.").queue();
                        return;
                    }

                    // --- UPDATED: Store the formatted user message in the history ---
                    history.add(new MessageEntry("user", formattedUserMessage));
                    history.add(new MessageEntry("model", reply));

                    List<String> messages = splitMessage(reply);
                    for (String msg : messages) {
                        event.getChannel().sendMessage(msg).queue();
                    }

                } catch (Exception e) {
                    logger.error("Failed to parse Vertex AI response", e);
                    event.getChannel().sendMessage("⚠️ Error parsing AI response.").queue();
                }
            }
        });
    }

    private static ObjectNode createContentNode(String role, String text) {
        ObjectNode textPart = mapper.createObjectNode().put("text", text);
        ArrayNode partsArray = mapper.createArrayNode().add(textPart);
        ObjectNode contentEntry = mapper.createObjectNode();
        contentEntry.put("role", role);
        contentEntry.set("parts", partsArray);
        return contentEntry;
    }

    private static ObjectNode createSafetySetting(String category, String threshold) {
        ObjectNode setting = mapper.createObjectNode();
        setting.put("category", category);
        setting.put("threshold", threshold);
        return setting;
    }

    /**
     * Splits a long string into a list of smaller strings, each under the Discord character limit.
     * This method attempts to split at newlines first, then spaces, to avoid breaking words.
     *
     * @param text The long string to split.
     * @return A list of strings, each guaranteed to be under 2000 characters.
     */
    private static List<String> splitMessage(String text) {
        if (text.length() <= DISCORD_MESSAGE_MAX_LENGTH) {
            return Collections.singletonList(text);
        }

        List<String> messages = new ArrayList<>();
        StringBuilder sb = new StringBuilder(text);

        while (sb.length() > 0) {
            if (sb.length() <= DISCORD_MESSAGE_MAX_LENGTH) {
                messages.add(sb.toString());
                break;
            }

            // Find the best place to split, looking backwards from the max length
            int splitPos = sb.lastIndexOf("\n", DISCORD_MESSAGE_MAX_LENGTH);
            if (splitPos == -1) {
                splitPos = sb.lastIndexOf(" ", DISCORD_MESSAGE_MAX_LENGTH);
            }
            if (splitPos == -1) {
                // No clean split point found, so we have to cut at the max length
                splitPos = DISCORD_MESSAGE_MAX_LENGTH;
            }

            messages.add(sb.substring(0, splitPos));
            sb.delete(0, splitPos);

            // Trim leading whitespace from the next chunk
            while (sb.length() > 0 && Character.isWhitespace(sb.charAt(0))) {
                sb.deleteCharAt(0);
            }
        }
        return messages;
    }

    private static class MessageEntry {
        final String role;
        final String content;

        MessageEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}