package com.desuu.prime.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages music playback for a single Discord guild.
 * Holds the AudioPlayer, scheduler, and send handler.
 * Provides load-and-play functionality for YouTube/Spotify/queries.
 */
public class GuildMusicManager {
    private static AudioPlayerManager audioPlayerManager;
    private static final Map<Long, GuildMusicManager> INSTANCES = new ConcurrentHashMap<>();

    private final Guild guild;

    /**
     * Initialize with the shared AudioPlayerManager (must be called once at startup).
     */
    public static void init(AudioPlayerManager manager) {
        audioPlayerManager = manager;
        AudioSourceManagers.registerRemoteSources(audioPlayerManager);
        AudioSourceManagers.registerLocalSource(audioPlayerManager);
    }

    /**
     * Get or create the music manager for the given guild.
     */
    public static GuildMusicManager get(Guild guild) {
        return INSTANCES.computeIfAbsent(guild.getIdLong(), id -> new GuildMusicManager(guild));
    }

    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;

    private GuildMusicManager(Guild guild) {
        this.guild = guild;
        this.player = audioPlayerManager.createPlayer();
        this.scheduler = new TrackScheduler(player, audioPlayerManager, guild);
        this.sendHandler = new AudioPlayerSendHandler(player);
    }

    public TrackScheduler getScheduler() {
        return scheduler;
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public AudioPlayerSendHandler getSendHandler() {
        return sendHandler;
    }

    /**
     * Load and play the given query (URL or search term).
     * Joins the user's voice channel before playing if not already connected.
     */
    public void loadAndPlay(InteractionHook hook, String query, VoiceChannel voiceChannel) {
        connectToVoice(voiceChannel);

        // Determine identifier for search or URL
        String identifier;
        if (query.startsWith("http://") || query.startsWith("https://")) {
            identifier = query;
        } else {
            // Use YouTube search for non-URL queries
            identifier = "ytsearch:" + query;
        }

        // Load item with ordered execution, send feedback via hook
        audioPlayerManager.loadItemOrdered(
                this,
                identifier,
                scheduler.createLoadHandler(identifier, hook)
        );
    }

    /**
     * Connect bot to the specified voice channel and set the send handler.
     */
    public void connectToVoice(VoiceChannel channel) {
        AudioManager am = channel.getGuild().getAudioManager();
        am.setSendingHandler(sendHandler);
        am.openAudioConnection(channel);
    }

    /**
     * Skip to the next track.
     */
    public void skip() {
        scheduler.nextTrack();
    }

    public void pause() {
        player.setPaused(true);
    }

    public void resume() {
        player.setPaused(false);
    }

    public void shuffle() {
        scheduler.shuffleQueue();
    }
}
