package com.desuu.prime;

import com.desuu.prime.chat.ChatSessionManager;
import com.desuu.prime.chat.GoogleAuthManager;
import com.desuu.prime.commands.CommandHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent; // <-- Import this
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
        // (Argument parsing and config loading code remains the same...)
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
        // Music player initialization is disabled for now.

        // Initialize Google authentication and chat session manager for Vertex

        String credentialsPath = props.getProperty("gcp.credentials_path");
        try {
            GoogleAuthManager.init(credentialsPath);
        } catch (Exception e) {
            System.err.println("Could not initialize Google Authentication. Chat features will be disabled. Error: " + e.getMessage());
            // Depending on requirements, you might want to exit here: System.exit(1);
        }
        String projectNumber = props.getProperty("gcp.project_number");
        String location = props.getProperty("gcp.location", "us-central1");
        String endpointId = props.getProperty("vertex.endpoint_id");
        ChatSessionManager.init(projectNumber, location, endpointId);

        // 5. Build and Launch JDA
        JDA jda = JDABuilder.createDefault(props.getProperty("discord.token"))
                // THIS IS THE LINE YOU NEED TO ADD
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                // Register event listeners. All logic is now in dedicated handlers.
                .addEventListeners(new CommandHandler(props, personalities))
                .build();

        jda.awaitReady();
        System.out.println("DesuuPrime is online and ready!");
    }
}