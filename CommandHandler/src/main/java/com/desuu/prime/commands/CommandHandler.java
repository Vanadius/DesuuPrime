package com.desuu.prime.commands;

import com.desuu.prime.audio.GuildMusicManager;
import com.desuu.prime.chat.ChatSessionManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * CommandHandler listens for all Discord interactions, registers slash commands on startup,
 * and delegates command handling to the appropriate services.
 */
public class CommandHandler extends ListenerAdapter {

    private final Properties config;
    private final Map<String, String> personalities;

    public CommandHandler(Properties config, Map<String, String> personalities) {
        this.config = config;
        this.personalities = personalities;
    }

    @Override
    public void onReady(ReadyEvent event) {
        // Register all slash commands globally on startup
        event.getJDA().updateCommands().addCommands(
                // Chat Commands
                Commands.slash("join-assistant", "Invite desuu to this channel for chat")
                        .addOption(OptionType.STRING, "personality", "Assistant personality", false),
                // Music Commands
                Commands.slash("play", "Play a track")
                        .addOption(OptionType.STRING, "query", "Track name or URL", true),
                Commands.slash("skip", "Skip the current track"),
                Commands.slash("pause", "Pause playback"),
                Commands.slash("resume", "Resume playback"),
                Commands.slash("shuffle", "Shuffle the queue"),
                Commands.slash("join", "Join your voice channel"),
                Commands.slash("leave", "Leave the voice channel")
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        // These are needed for most music commands
        GuildMusicManager musicManager = GuildMusicManager.get(guild);
        AudioManager audioManager = guild.getAudioManager();
        Member member = event.getMember();

        switch (command) {
            // Chat Commands
            case "join-assistant": {
                String persona = Optional.ofNullable(event.getOption("personality"))
                        .map(o -> o.getAsString())
                        .orElse(config.getProperty("default_personality", "helpful"));
                String prompt = personalities.getOrDefault(persona, personalities.get("helpful"));
                ChatSessionManager.setSystemPrompt(event.getChannel().getIdLong(), prompt);
                event.reply("Assistant joined with personality \"" + persona + "\"").setEphemeral(true).queue();
                break;
            }

            // Music Commands
            case "play": {
                event.deferReply().queue(); // Defer reply as loading can take time
                if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
                    event.getHook().sendMessage("You need to be in a voice channel to play music.").setEphemeral(true).queue();
                    return;
                }
                VoiceChannel memberChannel = member.getVoiceState().getChannel().asVoiceChannel();
                String query = event.getOption("query").getAsString();
                musicManager.loadAndPlay(event.getHook(), query, memberChannel);
                break;
            }
            case "skip": {
                if (!isBotInVoiceWithMember(event, audioManager, member)) break;
                musicManager.skip();
                event.reply("Skipped the current track.").queue();
                break;
            }
            case "pause": {
                if (!isBotInVoiceWithMember(event, audioManager, member)) break;
                musicManager.pause();
                event.reply("Playback paused.").queue();
                break;
            }
            case "resume": {
                if (!isBotInVoiceWithMember(event, audioManager, member)) break;
                musicManager.resume();
                event.reply("Playback resumed.").queue();
                break;
            }
            case "shuffle": {
                if (!isBotInVoiceWithMember(event, audioManager, member)) break;
                musicManager.shuffle();
                event.reply("Shuffled the queue.").queue();
                break;
            }
            case "join": {
                if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
                    event.reply("You need to join a voice channel first!").setEphemeral(true).queue();
                    break;
                }
                VoiceChannel memberChannel = member.getVoiceState().getChannel().asVoiceChannel();
                musicManager.connectToVoice(memberChannel);
                event.reply("Joined voice channel: " + memberChannel.getName()).queue();
                break;
            }
            case "leave": {
                if (!audioManager.isConnected()) {
                    event.reply("I'm not in a voice channel.").setEphemeral(true).queue();
                    break;
                }
                audioManager.closeAudioConnection();
                event.reply("Left the voice channel.").queue();
                break;
            }
            default:
                event.reply("Unknown command: " + command).setEphemeral(true).queue();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        // Delegate message handling to the ChatSessionManager
        ChatSessionManager.handleMessage(event);
    }

    private boolean isBotInVoiceWithMember(SlashCommandInteractionEvent event, AudioManager audioManager, Member member) {
        if (!audioManager.isConnected()) {
            event.reply("I'm not currently in a voice channel.").setEphemeral(true).queue();
            return false;
        }
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            event.reply("You must be in a voice channel to use this command.").setEphemeral(true).queue();
            return false;
        }
        if (member.getVoiceState().getChannel() != audioManager.getConnectedChannel()) {
            event.reply("You must be in the same voice channel as me to use this command.").setEphemeral(true).queue();
            return false;
        }
        return true;
    }
}