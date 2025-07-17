package com.desuu.prime.commands;

import com.desuu.prime.audio.GuildMusicManager;
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

        GuildMusicManager musicManager = GuildMusicManager.get(guild);
        AudioManager audioManager = guild.getAudioManager();
        Member member = event.getMember();

        switch (command) {
            case "play": {
                // Defer reply as loading can take time and we reply via a hook later
                event.deferReply().queue();

                if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
                    event.getHook().sendMessage("You need to be in a voice channel to play music.").setEphemeral(true).queue();
                    return;
                }
                // Safe to get channel now
                VoiceChannel memberChannel = member.getVoiceState().getChannel().asVoiceChannel();

                // Delegate to the GuildMusicManager to load and queue
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
                event.reply("Unknown command: " + command).queue();
        }
    }

    /**
     * Checks if the bot is in a voice channel and if the interacting member is in the same channel.
     * Sends an appropriate ephemeral reply if checks fail.
     *
     * @return true if all checks pass, false otherwise.
     */
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
