package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.Platform;

/**
 * 아카이브 바구니에 들어가는 한 건: 플랫폼 · 채널 · 우리가 받은 시각(epoch ms) · 안쪽 JSON 원문.
 * 플랫폼은 세션 key에서 온다(프레임에는 없다). 파일의 폴더({@link ArchiveKey})와 줄 안 칸이 이 값을 쓴다.
 * toString을 두지 않는다 — record 기본 toString이 raw(=본문)를 통째로 찍는다.
 * 로그에 객체를 넘기지 마라(ChatMessage와 같은 규칙, ArchiveLogLeakTest가 못박는다).
 */
public record ArchivableChat(Platform platform, String channelId, long receivedAtMillis, String raw) { }
