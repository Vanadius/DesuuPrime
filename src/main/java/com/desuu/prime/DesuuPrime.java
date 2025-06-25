package com.desuu.prime;

import com.desuu.prime.audio.AudioPlayerSendHandler;
import com.desuu.prime.audio.GuildMusicManager;
import com.desuu.prime.chat.ChatSessionManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
// LavaPlayer fork maintained by dev.arbjerg (artifact on Maven Central)
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.apache.commons.cli.*;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main entry‑point for the DesuuPrime Discord bot.
 *
 * LavaPlayer dependency note: we now rely on the *dev.arbjerg* fork of LavaPlayer (artifact
 * `dev.arbjerg:lavaplayer:<version>` on Maven Central). The Java API and package names remain
 * `com.sedmelluq.discord.lavaplayer.*`, so imports stay unchanged.
 *
 * Features implemented so far:
 * - Command‑line parsing via Apache Commons CLI
 * - External config via Java .properties file (default: config.properties)
 * - Slash commands "join-assistant" and "play"
 * - Music playback (YouTube) via LavaPlayer
 * - Short confirmation beep (optional) when a /play command is accepted
 * - Per‑user chat sessions with OpenAI (ChatSessionManager)
 * - Skeleton AudioReceiveHandler left for future voice command ingestion
 */
public class DesuuPrime extends ListenerAdapter {

    private final Properties cfg;
    private final Map<String, String> personalities;
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final Map<Long, GuildMusicManager> musicManagers = new ConcurrentHashMap<>();

    /* -------- entry‑point -------- */
    public static void main(String[] args) throws Exception {
        // Parse CLI
        Options opts = new Options();
        opts.addOption("c", "config", true, "Path to config.properties (default: ./config.properties)");
        opts.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try { cmd = parser.parse(opts, args); }
        catch (ParseException e) {
            System.err.println("Invalid CLI: " + e.getMessage());
            new HelpFormatter().printHelp("desuuprime", opts);
            return;
        }
        if (cmd.hasOption('h')) {
            new HelpFormatter().printHelp("desuuprime", opts);
            return;
        }

        File cfgFile = new File(cmd.getOptionValue('c', "config.properties"));
        if (!cfgFile.exists()) {
            System.err.println("Config file not found: " + cfgFile.getAbsolutePath());
            return;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(cfgFile)) { props.load(fis); }

        // Load personalities.json if present
        Map<String,String> personalities = new HashMap<>();
        File pJson = new File("personalities.json");
        if (pJson.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String,String>> raw = mapper.readValue(pJson, new TypeReference<>(){});
            raw.forEach((k,v)-> personalities.put(k, v.get("system")));
        }

        // Initialise ChatSessionManager
        ChatSessionManager.init(props.getProperty("openai.api_key"));

        // Build JDA
        JDA jda = JDABuilder.createDefault(props.getProperty("discord.token"))
                .addEventListeners(new DesuuPrime(props, personalities))
                .build();

        // Wait until ready then register commands
        jda.awaitReady();
        jda.updateCommands().addCommands(
                Commands.slash("join-assistant", "Invite GPT assistant to this channel")
                        .addOption(OptionType.STRING, "personality", "Assistant personality", false),
                Commands.slash("play", "Play audio from YouTube URL")
                        .addOption(OptionType.STRING, "url", "YouTube URL", true)
        ).queue();
    }

    private DesuuPrime(Properties cfg, Map<String,String> personalities) {
        this.cfg = cfg;
        this.personalities = personalities;

        // 1) Register your audio sources
        playerManager.registerSourceManager(new YoutubeAudioSourceManager());
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);

        // 2) **Initialize the GuildMusicManager with that same manager**
        GuildMusicManager.init(playerManager);
    }

    /* -------- event callbacks -------- */
    @Override public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent ev) {
        switch (ev.getName()) {
            case "join-assistant" -> handleJoin(ev);
            case "play" -> handlePlay(ev);
        }
    }

    @Override public void onMessageReceived(@Nonnull MessageReceivedEvent ev) {
        ChatSessionManager.handleMessage(ev);
    }

    /* -------- command handlers -------- */
    private void handleJoin(SlashCommandInteractionEvent ev) {
        String persona = Optional.ofNullable(ev.getOption("personality"))
                .map(o->o.getAsString())
                .orElse(cfg.getProperty("default_personality", "helpful"));
        String prompt = personalities.getOrDefault(persona, personalities.get("helpful"));
        ChatSessionManager.setSystemPrompt(ev.getChannel().getIdLong(), prompt);
        ev.reply("Assistant joined with personality \"" + persona + "\"").setEphemeral(true).queue();
    }

    private void handlePlay(SlashCommandInteractionEvent ev) {
        Member member = Objects.requireNonNull(ev.getMember());
        AudioChannel userVc = member.getVoiceState().getChannel();
        if (userVc == null) {
            ev.reply("You must be in a voice channel.").setEphemeral(true).queue();
            return;
        }

        String url = ev.getOption("url").getAsString();
        GuildMusicManager mgr = musicManagers.computeIfAbsent(ev.getGuild().getIdLong(),
                gid -> new GuildMusicManager(playerManager));
        ((AudioPlayerSendHandler) mgr.getSendHandler()).connect(userVc);

        // Optional beep sound to confirm command
        String beep = cfg.getProperty("beep.sound", "beep.mp3");
        mgr.scheduler.queueLocal(beep);

        ev.reply("Loading track...").queue(hook ->
                playerManager.loadItem(url, mgr.scheduler.createLoadHandler(ev.getName(), hook))
        );
    }
}
