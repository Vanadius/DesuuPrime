package com.desuu.prime.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Collections;

public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private final BlockingQueue<AudioTrack> queue;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
    }

    /**
     * Attempt to play the track immediately if the player is idle;
     * otherwise add it to the queue.
     */
    public void queue(AudioTrack track) {
        // startTrack(track, true) only starts if nothing is playing;
        // if it returns false, we queue the track instead.
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

    /** Queue a local audio file (e.g. confirmation beep). */
    public void queueLocal(String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            System.err.println("[TrackScheduler] Local file not found: " + path);
            return;
        }
        String identifier = file.toURI().toString();
        player.getSourceManager().loadItem(identifier, (result) -> {
            if (result == null) {
                System.err.println("[TrackScheduler] Failed to load local track: " + path);
            } else {
                AudioTrack track = (AudioTrack) result;
                queue(track);
            }
        });
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // Automatically start the next track if the previous finished normally or failed to load.
        if (endReason.mayStartNext) {
            nextTrack();
        }
    }
}
