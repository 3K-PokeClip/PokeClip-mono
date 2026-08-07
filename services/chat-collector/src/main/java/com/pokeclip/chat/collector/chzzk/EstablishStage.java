package com.pokeclip.chat.collector.chzzk;

/** 수립이 어디서 걸렸는지. 시한 초과 로그에 stage=로 찍힌다. */
public enum EstablishStage {
    AUTH, CONNECT, WAITING_CONNECTED, SUBSCRIBE, WAITING_SUBSCRIBED
}
