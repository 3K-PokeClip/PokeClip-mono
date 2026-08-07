package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;

public class SessionEstablishException extends RuntimeException {

    private final EstablishStage stage;
    private final StopReason reason;

    public SessionEstablishException(EstablishStage stage, StopReason reason, String message) {
        super(message);
        this.stage = stage;
        this.reason = reason;
    }

    public EstablishStage stage() { return stage; }
    public StopReason reason() { return reason; }
}
