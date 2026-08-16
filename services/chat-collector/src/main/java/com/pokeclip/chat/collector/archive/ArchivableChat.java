package com.pokeclip.chat.collector.archive;

/**
 * 아카이브 바구니에 들어가는 한 건 — 채널 · 우리가 받은 시각(epoch ms) · 치지직 안쪽 JSON 원문.
 * toString을 두지 않는다 — record 기본 toString이 raw(=본문)를 통째로 찍는다.
 * 로그에 객체를 넘기지 마라(ChatMessage와 같은 규칙, ArchiveLogLeakTest가 못박는다).
 */
public record ArchivableChat(String channelId, long receivedAtMillis, String raw) { }
