package com.desuu.prime.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TrackScheduler extends AudioEventAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayer player;
    private final AudioPlayerManager playerManager;
    private final BlockingQueue<AudioTrack> queue;

    /**
     * Constructor now takes an AudioPlayer *and* its AudioPlayerManager.
     */
    public TrackScheduler(AudioPlayer player, AudioPlayerManager playerManager) {
        this.player = player;
        this.playerManager = playerManager;
        this.queue = new LinkedBlockingQueue<>();
        this.player.addListener(this);
    }

    /**
     * Queue a local audio file for playback. The file path is converted to a URI
     * and loaded via the AudioPlayerManager. We handle track vs playlist results.
     */
    public void queueLocal(String path) {
        String uri = new File(path).toURI().toString();
        playerManager.loadItem(uri, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // Successfully loaded a single track – queue it for playback.
                queue(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // Successfully loaded a playlist – queue each track.
                for (AudioTrack t : playlist.getTracks()) {
                    queue(t);
                }
            }

            @Override
            public void noMatches() {
                // No track found for the given identifier.
                log.warn("No track found for identifier: " + uri);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                // Loading failed - log the error.
                log.error("Failed to load track for path: " + path, exception);
            }
        });
    }

    /**
     * Queues a track to the player. If nothing is currently playing, start immediately;
     * otherwise add to the queue.
     */
    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        }
    }

    /**
     * Skip to the next track in the queue (or stop if queue is empty).
     */
    public void nextTrack() {
        // Poll the next track (or null) and start it. Passing false forces an immediate switch.
        player.startTrack(queue.poll(), false);
    }

    /**
     * Shuffle the remaining tracks in the queue randomly.
     */
    public void shuffleQueue() {
        synchronized (queue) {
            List<AudioTrack> list = new ArrayList<>(queue);
            Collections.shuffle(list);
            queue.clear();
            queue.addAll(list);
        }
    }

    public AudioLoadResultHandler createLoadHandler(String identifier, InteractionHook hook) {
        return new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                queue(track);
                log.info("Queued track: {}", track.getInfo().title);
                hook.sendMessage("Queued track: " + track.getInfo().title).queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                for (AudioTrack track : playlist.getTracks()) {
                    queue(track);
                }
                log.info("Queued playlist with {} tracks.", playlist.getTracks().size());
                hook.sendMessage("Queued playlist with " + playlist.getTracks().size() + " tracks.").queue();
            }

            @Override
            public void noMatches() {
                log.warn("No track found for identifier: {}", identifier);
                hook.sendMessage("No matches found for: " + identifier).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                log.error("Failed to load track for identifier: {}", identifier, exception);
                hook.sendMessage("Failed to load: " + exception.getMessage()).queue();
            }
        };
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // If the track completed normally, start the next track in queue if available.
        if (endReason.mayStartNext) {
            AudioTrack nextTrack = queue.poll();
            if (nextTrack != null) {
                player.startTrack(nextTrack, false);
            }
        }
    }
}
