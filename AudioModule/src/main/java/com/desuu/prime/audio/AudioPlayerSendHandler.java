package com.desuu.prime.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.managers.AudioManager;

import java.nio.ByteBuffer;

/**
 * This class bridges a LavaPlayer AudioPlayer with JDA's AudioSendHandler
 * so audio can be sent into a Discord voice channel.
 */
public class AudioPlayerSendHandler implements AudioSendHandler {
    private final AudioPlayer audioPlayer;
    private AudioFrame lastFrame;

    public AudioPlayerSendHandler(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    @Override
    public boolean canProvide() {
        lastFrame = audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() {
        return true;
    }

    /**
     * Connects this audio handler to a voice channel using JDA's AudioManager.
     * @param channel the target AudioChannel to join
     */
    public void connect(AudioChannel channel) {
        AudioManager manager = channel.getGuild().getAudioManager();
        manager.setSendingHandler(this);
        manager.openAudioConnection(channel);
    }
}
