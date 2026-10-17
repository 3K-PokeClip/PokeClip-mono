package com.pokeclip.clip.collector;

/**
 * 방송 하나의 치지직 제목과 카테고리(POK-259). 둘 다 수집기의 같은 관측 한 줄에서 왔다({@link BroadcastTitleClient}).
 *
 * @param category 비어 있을 수 있다(치지직이 카테고리 없이 방송한다)
 */
public record BroadcastTitle(String title, String category) {
}
