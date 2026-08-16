package com.pokeclip.chat.collector.archive;

/** 닫힌 1분 창 하나 = S3에 올릴 파일 하나. bytes는 JSON Lines. toString 안 둔다(bytes에 본문). */
public record ArchiveObject(String key, byte[] bytes, int messageCount, String channelId, long windowStartMillis) { }
