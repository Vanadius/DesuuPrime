package com.desuu.prime;

import com.desuu.prime.audio.GuildMusicManager;
import com.desuu.prime.chat.ChatSessionManager;
import com.desuu.prime.commands.CommandHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Main entry-point for the DesuuPrime Discord bot.
 * This class is responsible for parsing configuration, initializing services,
 * and launching the JDA instance with the appropriate event listeners.
 */
public class DesuuPrime {

    public static void main(String[] args) throws Exception {
        // 1. Parse Command-Line Arguments
        Options opts = new Options();
        opts.addOption("c", "config", true, "Path to config.properties (default: ./config.properties)");
        opts.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(opts, args);
        } catch (ParseException e) {
            System.err.println("Invalid CLI arguments: " + e.getMessage());
            new HelpFormatter().printHelp("desuuprime", opts);
            return;
        }
        if (cmd.hasOption('h')) {
            new HelpFormatter().printHelp("desuuprime", opts);
            return;
        }

        // 2. Load Configuration File
        File cfgFile = new File(cmd.getOptionValue('c', "config.properties"));
        if (!cfgFile.exists()) {
            System.err.println("Config file not found: " + cfgFile.getAbsolutePath());
            return;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(cfgFile)) {
            props.load(fis);
        }

        // 3. Load Personalities File
        Map<String, String> personalities = new HashMap<>();
        File pJson = new File("personalities.json");
        if (pJson.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, String>> raw = mapper.readValue(pJson, new TypeReference<>() {});
            raw.forEach((k, v) -> personalities.put(k, v.get("system")));
        }

        // 4. Initialize Core Services
        // Initialize LavaPlayer
        GuildMusicManager.init(new DefaultAudioPlayerManager());

        // Initialize Google authentication and chat session manager for Vertex
        String projectId = props.getProperty("gcp.project_id");
        String location = props.getProperty("gcp.location", "us-central1");
        String model = props.getProperty("vertex.model", "chat-bison@001");
        ChatSessionManager.init(projectId, location, model);

        // 5. Build and Launch JDA
        JDA jda = JDABuilder.createDefault(props.getProperty("discord.token"))
                // Register event listeners. All logic is now in dedicated handlers.
                .addEventListeners(new CommandHandler(props, personalities))
                .build();

        jda.awaitReady();
        System.out.println("DesuuPrime is online and ready!");
    }
}