package com.pokeclip.clip.paging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 이어받기 표시를 감싸고 푼다. 목록 둘이 나눠 쓴다.
 *
 * <p><b>왜 감싸나</b> — 가리키는 값이 문마다 다르고(방송은 줄 번호 하나, 카드는 방송 시간 +
 * 줄 번호) 그대로 노출하면 <b>웹이 조합 규칙을 알아야 한다</b>(PRD 결정). 표시는 불투명한
 * 문자열 하나이고, 웹은 그것을 풀어 보거나 만들지 않는다.
 *
 * <p>🔴 <b>감추기가 아니다.</b> base64는 되돌릴 수 있으므로 여기 담기는 값은 어차피 보인다 —
 * 담는 것이 줄 번호와 방송 시각뿐인 이유가 그것이다. 이 감싸기가 실제로 사는 것은
 * <b>「우리가 준 표시만 받는다」</b>는 좁힘이다.
 *
 * <p><b>종류 태그를 강제한다.</b> 태그가 없으면 방송 목록에서 받은 표시를 카드 문에 넣어도
 * 숫자 하나로 읽혀 통과하고, 그러면 그 방송의 카드가 엉뚱한 자리부터 나온다. 칸 수까지
 * 세는 이유는 카드 조회의 이어받기 조건이 <b>두 값이 함께 있어야</b> 성립하기 때문이다 —
 * {@code afterId}가 비면 {@code (ts = :afterTs AND id > NULL)}이 NULL로 평가돼 같은 방송
 * 시간의 뒷줄이 <b>조용히 빠진다</b>(계획 검증 실측).
 */
public final class CursorCodec {

    /** 표시 안에서 칸을 가르는 글자. 담는 값이 숫자뿐이라 값에 섞일 수 없다. */
    private static final String 구분자 = ":";

    /**
     * 문마다 다른 표시의 종류. {@code arity}는 <b>그 문이 가리키는 값의 개수</b>다.
     *
     * <p>태그가 한 글자인 것은 표시를 짧게 하려는 것이고, 뜻은 여기서만 정해진다 —
     * 웹은 이 글자를 모른다.
     */
    public enum Kind {

        /** 방송 목록 — 줄 번호 하나. 정렬키와 이어받기 기준이 같은 값이라 하나면 된다. */
        BROADCAST("b", 1),
        /** 카드 목록 — 방송 시간 + 줄 번호. 방송 시간이 유일하지 않아 둘이 함께여야 한다. */
        CARD("c", 2);

        private final String tag;
        private final int arity;

        Kind(String tag, int arity) {
            this.tag = tag;
            this.arity = arity;
        }
    }

    /**
     * @throws IllegalArgumentException 칸 수가 그 문의 것과 다르다. <b>요청이 아니라 우리 버그다</b> —
     *         못 푸는 표시를 만들어 내보내면 다음 장에서 400이 나가고 원인이 여기서 안 보인다.
     */
    public static String encode(Kind kind, long... values) {
        if (values.length != kind.arity) {
            throw new IllegalArgumentException(
                    "%s 표시는 값이 %d개다: %d개를 받았다".formatted(kind, kind.arity, values.length));
        }
        StringBuilder raw = new StringBuilder(kind.tag);
        for (long value : values) {
            raw.append(구분자).append(value);
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidCursorException 우리가 감싼 모양이 아니다 — 형식이 깨졌거나, 다른 문의
     *         표시이거나, 칸 수가 다르거나, 칸이 숫자가 아니다. <b>거절은 이 한 타입뿐이다</b>
     */
    public static List<Long> decode(Kind kind, String cursor) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            // 여기서 갈아입히지 않으면 전역 조언이 이 타입을 안 잡아 500이 된다.
            throw new InvalidCursorException("표시를 풀 수 없다");
        }

        String[] parts = new String(bytes, StandardCharsets.UTF_8).split(구분자, -1);
        // 태그 한 칸 + 값 arity칸. 남는 것도 거절한다 — 「모자라면」만 막으면 늘어난 쪽이 통과한다.
        if (parts.length != kind.arity + 1 || !kind.tag.equals(parts[0])) {
            throw new InvalidCursorException("이 문의 표시가 아니다");
        }

        List<Long> values = new ArrayList<>(kind.arity);
        for (int i = 1; i < parts.length; i++) {
            try {
                values.add(Long.parseLong(parts[i]));
            } catch (NumberFormatException e) {
                // long 범위를 넘는 값도 같은 갈래다. 그대로 새면 전역 조언이 못 잡아 500이 된다.
                throw new InvalidCursorException("표시의 칸이 숫자가 아니다");
            }
        }
        return List.copyOf(values);
    }

    private CursorCodec() {
    }
}
