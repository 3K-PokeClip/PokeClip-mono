package com.pokeclip.clip.collector;

/** 수집기가 준 것 그대로. 본문을 우리가 다시 쓰지 않는다. */
public record CollectorResponse(int status, String body) {
}
