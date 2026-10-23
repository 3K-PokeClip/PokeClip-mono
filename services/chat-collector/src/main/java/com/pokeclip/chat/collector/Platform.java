package com.pokeclip.chat.collector;

import java.util.Optional;

/**
 * 채팅이 어느 방송 플랫폼에서 왔나. 수집기 표 넷의 {@code platform} 칸(V313)에 이 이름 그대로 들어간다.
 *
 * <p>채널 아이디는 플랫폼마다 따로 매겨서 치지직과 SOOP에 같은 아이디가 있을 수 있다. 그래서 채널로 찾거나 지우는 자리는
 * 전부 (플랫폼, 채널)로 본다.
 */
public enum Platform {
    CHZZK,
    SOOP;

    /**
     * 바깥에서 온 이름을 읽는다. 값이 없거나 비면 치지직이다: 플랫폼 칸이 생기기 전의 auth는 이름을 안 보낸다.
     *
     * <p>대문자와 정확히 같을 때만 받는다. 표의 CHECK가 대문자만 받으므로 여기서 소문자를 고쳐 주면 「soop」을 보낸 쪽이
     * 다른 자리에서 그 값을 그대로 쓸 때 갈린다. 모르는 이름은 {@link Optional#empty()}이고 부른 쪽이 거절한다.
     */
    public static Optional<Platform> parse(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of(CHZZK);
        }
        for (Platform each : values()) {
            if (each.name().equals(name)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }
}
