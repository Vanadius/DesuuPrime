package com.desuu.prime.commands;

import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.managers.AudioManager;

import com.desuu.prime.audio.GuildMusicManager;

/**
 * CommandHandler listens for Discord interactions and message commands,
 * registers slash commands on startup, and delegates command handling
 * to the music manager or voice connection. Replies are sent via the interaction hook or channel.
 */
public class CommandHandler extends ListenerAdapter {

    @Override
    public void onReady(ReadyEvent event) {
        // Register slash commands at startup
        event.getJDA().updateCommands().addCommands(
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
            event.reply("This command can only be used in a server.").queue();
            return;
        }
        switch (command) {
            case "play": {
                Member member = event.getMember();
                if (member == null) {
                    event.reply("You need to be in a voice channel to play music.").setEphemeral(true).queue();
                    return;
                }
                VoiceChannel voiceChannel = member.getVoiceState().getChannel().asVoiceChannel();
                if (voiceChannel == null) {
                    event.reply("You need to join a voice channel first!").setEphemeral(true).queue();
                    return;
                }
                // Join voice channel if not already connected
                AudioManager audioManager = guild.getAudioManager();
                if (!audioManager.isConnected() || audioManager.getConnectedChannel() != voiceChannel) {
                    audioManager.openAudioConnection(voiceChannel);
                }
                // Delegate to the GuildMusicManager to load and queue
                GuildMusicManager musicManager = GuildMusicManager.get(guild);
                String query = event.getOption("query").getAsString();
                musicManager.loadAndPlay(event.getInteraction().getHook(), query);
                event.reply("Loading track: " + query).queue();
                break;
            }
            case "skip": {
                // Handle /skip: skip the current track
                GuildMusicManager musicManager = GuildMusicManager.get(guild);
               musicManager.skip();
                event.reply("Skipped the current track.").queue();
                break;
            }
            case "pause": {
                // Handle /pause: pause playback
                GuildMusicManager musicManager = GuildMusicManager.get(guild);
                musicManager.pause();
                event.reply("Playback paused.").queue();
                break;
            }
            case "resume": {
                // Handle /resume: resume playback
                GuildMusicManager musicManager = GuildMusicManager.get(guild);
                musicManager.resume();
                event.reply("Playback resumed.").queue();
                break;
            }
            case "shuffle": {
                // Handle /shuffle: shuffle the queue
                GuildMusicManager musicManager = GuildMusicManager.get(guild);
                musicManager.shuffle();
                event.reply("Shuffled the queue.").queue();
                break;
            }
            case "join": {
                // Handle /join: join the user's voice channel
                Member member = event.getMember();
                if (member == null) {
                    event.reply("You must be in a voice channel to use this command.").queue();
                    break;
                }
                VoiceChannel channel = member.getVoiceState().getChannel().asVoiceChannel();
                if (channel == null) {
                    event.reply("You need to join a voice channel first!").queue();
                } else {
                    AudioManager audioManager = guild.getAudioManager();
                    audioManager.openAudioConnection(channel);
                    event.reply("Joined voice channel: " + channel.getName()).queue();
                }
                break;
            }
            case "leave": {
                // Handle /leave: disconnect from voice channel
                AudioManager audioManager = guild.getAudioManager();
                audioManager.closeAudioConnection();
                event.reply("Left the voice channel.").queue();
                break;
            }
            default:
                event.reply("Unknown command: " + command).queue();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Stub for future text-based commands prefixed by @DesuuPrime mention
        if (event.getAuthor().isBot()) {
            return;
        }
        String content = event.getMessage().getContentRaw().trim();
        if (content.startsWith(event.getJDA().getSelfUser().getAsMention())) {
            event.getChannel().sendMessage("Text commands are not implemented yet.").queue();
        }
    }
}
