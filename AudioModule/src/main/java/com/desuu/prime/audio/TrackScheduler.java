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
import net.dv8tion.jda.api.entities.Guild;

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
    private final Guild guild;

    // State for interrupting playback with a notification sound
    private boolean isPlayingNotification = false;
    private AudioTrack interruptedTrack = null;
    private long interruptedTrackPosition = 0;

    /**
     * Constructor now takes an AudioPlayer *and* its AudioPlayerManager.
     */
    public TrackScheduler(AudioPlayer player, AudioPlayerManager playerManager, Guild guild) {
        this.player = player;
        this.playerManager = playerManager;
        this.queue = new LinkedBlockingQueue<>();
        this.guild = guild;
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
                playNotificationSound();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                boolean soundPlayed = false;
                // Successfully loaded a playlist – queue each track.
                for (AudioTrack t : playlist.getTracks()) {
                    queue(t);
                    if (!soundPlayed) {
                        playNotificationSound();
                        soundPlayed = true;
                    }
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
        // Stopping the current track will trigger onTrackEnd, which will play the next track.
        // This prevents a race condition where both a skip command and the track end event
        // try to poll from the queue, causing a track to be skipped inadvertently.
        player.stopTrack();
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
                playNotificationSound(); // Play sound on new track
                log.info("Queued track: {}", track.getInfo().title);
                hook.sendMessage("Queued track: " + track.getInfo().title).queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                boolean soundPlayed = false;
                for (AudioTrack track : playlist.getTracks()) {
                    queue(track);
                    if (!soundPlayed) {
                        playNotificationSound(); // Play sound once for playlist
                        soundPlayed = true;
                    }
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
    public void onTrackEnd(AudioPlayer player, AudioTrack endedTrack, AudioTrackEndReason endReason) {
        // Check if the track that just ended was our notification sound
        if (isPlayingNotification) {
            isPlayingNotification = false;

            // If a track was interrupted, we resume it.
            if (this.interruptedTrack != null) {
                log.info("Resuming interrupted track: {}", interruptedTrack.getInfo().title);
                // We must clone the track to play it again and seek to its previous position.
                AudioTrack trackToResume = this.interruptedTrack.makeClone();
                trackToResume.setPosition(this.interruptedTrackPosition);
                player.playTrack(trackToResume);

                // Clear the state
                this.interruptedTrack = null;
                this.interruptedTrackPosition = 0;
            } else {
                // No track was interrupted, so play the next song from the main queue.
                player.startTrack(queue.poll(), false);
            }
            return; // Our custom logic is done for this event.
        }

        // Original logic for when a regular track finishes
        if (endReason.mayStartNext) {
            player.startTrack(queue.poll(), false);
        }
    }

    /**
     * Interrupts the current track to play a notification sound, then resumes.
     * Assumes 'llama.wav' is in the application's working directory.
     */
    private void playNotificationSound() {
        // Don't play a sound if the bot isn't in a voice channel or if we're already playing one.
        if (isPlayingNotification || !guild.getAudioManager().isConnected()) {
            return;
        }

        AudioTrack currentlyPlaying = player.getPlayingTrack();
        if (currentlyPlaying != null) {
            this.interruptedTrack = currentlyPlaying;
            this.interruptedTrackPosition = currentlyPlaying.getPosition();
        }

        String soundFilePath = "llama.wav";
        playerManager.loadItem(soundFilePath, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                isPlayingNotification = true;
                // Use playTrack to immediately start, which will stop the current track.
                player.playTrack(track);
                log.info("Playing notification sound.");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) { /* Should not happen for a .wav file */ }

            @Override
            public void noMatches() {
                log.warn("Notification sound not found at path: {}", soundFilePath);
                interruptedTrack = null; // Clear interruption state so playback isn't blocked
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                log.error("Failed to load notification sound '{}': {}", soundFilePath, exception.getMessage());
                interruptedTrack = null; // Clear interruption state
            }
        });
    }
}
