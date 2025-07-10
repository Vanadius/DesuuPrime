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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(ChatSessionManager.class);
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String vertexApiUrl;

    private static final Map<Long, String> systemPrompts = new ConcurrentHashMap<>();
    private static final Map<Long, List<MessageEntry>> histories = new ConcurrentHashMap<>();

    public static void init(String projectId, String location, String modelId) {
        vertexApiUrl = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, projectId, location, modelId
        );
        GoogleAuthManager.getInstance();
        logger.info("ChatSessionManager initialized for Vertex model {}", modelId);
    }

    public static void setSystemPrompt(long channelId, String prompt) {
        systemPrompts.put(channelId, prompt);
        histories.remove(channelId);
    }

    public static void handleMessage(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }

        long channelId = event.getChannel().getIdLong();
        String system = systemPrompts.get(channelId);
        if (system == null) {
            return; // assistant not enabled in this channel
        }

        String userMessage = event.getMessage().getContentDisplay();
        histories.computeIfAbsent(channelId, k -> new ArrayList<>());
        List<MessageEntry> history = histories.get(channelId);

        ObjectNode payload = mapper.createObjectNode();
        ObjectNode instance = mapper.createObjectNode();
        instance.put("context", system);
        instance.set("examples", mapper.createArrayNode());

        ArrayNode messages = mapper.createArrayNode();
        for (MessageEntry entry : history) {
            String authorRole = entry.role.equals("assistant") ? "bot" : "user";
            messages.add(mapper.createObjectNode()
                    .put("author", authorRole)
                    .put("content", entry.content));
        }
        messages.add(mapper.createObjectNode()
                .put("author", "user")
                .put("content", userMessage));
        instance.set("messages", messages);

        ObjectNode params = mapper.createObjectNode();
        params.put("temperature", 0.5);
        params.put("maxOutputTokens", 1024);

        payload.set("instances", mapper.createArrayNode().add(instance));
        payload.set("parameters", params);

        Request request = new Request.Builder()
                .url(vertexApiUrl)
                .addHeader("Authorization", "Bearer " + GoogleAuthManager.getAccessToken())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.error("Vertex AI request failed", e);
                event.getChannel().sendMessage("\u26A0\uFE0F Error contacting AI: " + e.getMessage()).queue();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    logger.warn("Vertex AI API error: HTTP {}", response.code());
                    event.getChannel().sendMessage("\u26A0\uFE0F AI API error: " + response.code()).queue();
                    return;
                }

                JsonNode json = mapper.readTree(response.body().string());
                String reply = json.get("predictions").get(0)
                        .get("candidates").get(0)
                        .get("content").asText();

                history.add(new MessageEntry("user", userMessage));
                history.add(new MessageEntry("assistant", reply));
                event.getChannel().sendMessage(reply).queue();
            }
        });
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
