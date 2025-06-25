package com.desuu.prime.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.audio.AudioSendHandler;

public class GuildMusicManager {
    // The AudioPlayer for this guild and its TrackScheduler (queue manager).
    public final AudioPlayer player;
    public final TrackScheduler scheduler;

    /**
     * Initializes the player and scheduler for a guild.
     * @param playerManager the shared AudioPlayerManager (configured with sources)
     */
    public GuildMusicManager(AudioPlayerManager playerManager) {
        // Create a new player instance
        this.player = playerManager.createPlayer();
        // Create and register the track scheduler to handle queueing
        this.scheduler = new TrackScheduler(player);
        player.addListener(scheduler);
    }

    /** Queue a track or play immediately if idle. */
    public void queue(AudioTrack track) {
        scheduler.queue(track);
    }

    /** Skip the current track and play the next in queue. */
    public void skip() {
        scheduler.nextTrack();
    }

    /** Pause playback. */
    public void pause() {
        player.setPaused(true);
    }

    /** Resume playback if paused. */
    public void resume() {
        player.setPaused(false);
    }

    /** Randomize the order of the pending queue. */
    public void shuffle() {
        scheduler.shuffleQueue();
    }

    /** Returns true if a track is currently playing. */
    public boolean isPlaying() {
        return player.getPlayingTrack() != null;
    }

    /** Get the AudioSendHandler to send this player’s audio to Discord. */
    public AudioSendHandler getSendHandler() {
        return new AudioPlayerSendHandler(player);
    }
}
