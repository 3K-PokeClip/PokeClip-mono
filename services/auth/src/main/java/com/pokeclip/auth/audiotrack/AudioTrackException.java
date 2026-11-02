package com.pokeclip.auth.audiotrack;

public class AudioTrackException extends RuntimeException {

    private final AudioTrackFailure failure;

    public AudioTrackException(AudioTrackFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AudioTrackFailure getFailure() {
        return failure;
    }
}
