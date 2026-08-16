package com.pokeclip.chat.collector.archive;

/**
 * 닫힌 1분 창 하나 = S3에 올릴 파일 하나. bytes는 JSON Lines.
 *
 * <p><b>record라 toString·equals·hashCode가 자동으로 있다</b> — "안 뒀다"가 아니다. 셋 다 함정이 하나씩이다:
 * <ul>
 *   <li>기본 toString은 {@code key}를 그대로 찍는다. 키에는 채널 ID가 들어 있으니 <b>이 객체를 로그에
 *       넘기지 마라</b>(bytes는 배열이라 신원 해시로 나가 본문은 안 샌다 — 위험한 쪽은 키다)
 *   <li>{@code byte[]} 필드라 equals·hashCode는 <b>내용이 아니라 배열 신원</b>을 본다. 내용이 같아도 다른
 *       객체다 — Set·Map의 열쇠나 {@code containsExactly(객체)} 단언에 쓰지 마라(키로 비교한다)
 * </ul>
 */
public record ArchiveObject(String key, byte[] bytes, int messageCount) { }
