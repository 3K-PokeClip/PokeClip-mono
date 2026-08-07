package com.pokeclip.chat.collector.chzzk;

/** SYSTEM은 connected · subscribed · unsubscribed · revoked 넷뿐이다. */
public record SystemEvent(String type, String sessionKey) { }
