package com.desuu.prime.chat;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-channel, per-user chat sessions with OpenAI.
 * Allows setting a system prompt and handles incoming messages,
 * forwarding them to the OpenAI ChatCompletion API and relaying replies.
 */
public class ChatSessionManager {
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static OkHttpClient client = new OkHttpClient();
    private static ObjectMapper mapper = new ObjectMapper();
    private static String apiKey;

    // channelId -> systemPrompt
    private static final Map<Long, String> systemPrompts = new ConcurrentHashMap<>();
    // channelId -> (userId -> history of messages)
    private static final Map<Long, Map<Long, List<MessageEntry>>> histories = new ConcurrentHashMap<>();

    /**
     * Initialize the manager with an OpenAI API key.
     */
    public static void init(String key) {
        apiKey = key;
    }

    /**
     * Set the system prompt for a given text channel, resetting its history.
     */
    public static void setSystemPrompt(long channelId, String prompt) {
        systemPrompts.put(channelId, prompt);
        histories.put(channelId, new ConcurrentHashMap<>());
    }

    /**
     * Clear the session for a given channel.
     */
    public static void clearSession(long channelId) {
        systemPrompts.remove(channelId);
        histories.remove(channelId);
    }

    /**
     * Handle incoming messages: if the assistant is active in this channel,
     * forward to OpenAI, update history, and send the reply.
     */
    public static void handleMessage(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isFromGuild() == false) {
            return;
        }
        long channelId = event.getChannel().getIdLong();
        long userId = event.getAuthor().getIdLong();
        String system = systemPrompts.get(channelId);
        if (system == null) {
            return; // assistant not enabled in this channel
        }

        String userMessage = event.getMessage().getContentDisplay();
        // Initialize history containers
        histories.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>());
        Map<Long, List<MessageEntry>> channelHistory = histories.get(channelId);
        channelHistory.computeIfAbsent(userId, k -> new ArrayList<>());
        List<MessageEntry> history = channelHistory.get(userId);

        // Build the messages array
        ArrayNode messages = mapper.createArrayNode();
        messages.add(mapper.createObjectNode()
                .put("role", "system")
                .put("content", system)
        );
        for (MessageEntry entry : history) {
            messages.add(mapper.createObjectNode()
                    .put("role", entry.role)
                    .put("content", entry.content)
            );
        }
        messages.add(mapper.createObjectNode()
                .put("role", "user")
                .put("content", userMessage)
        );

        // Build request JSON
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "gpt-3.5-turbo");
        body.set("messages", messages);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                event.getChannel().sendMessage("⚠️ Error contacting AI: " + e.getMessage()).queue();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    event.getChannel().sendMessage("⚠️ AI API error: " + response.code()).queue();
                    return;
                }
                JsonNode json = mapper.readTree(response.body().string());
                String reply = json.get("choices").get(0).get("message").get("content").asText();

                // Update history
                history.add(new MessageEntry("user", userMessage));
                history.add(new MessageEntry("assistant", reply));

                // Send reply
                event.getChannel().sendMessage(reply).queue();
            }
        });
    }

    // Simple class to store role/content for each message
    private static class MessageEntry {
        final String role;
        final String content;
        MessageEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
