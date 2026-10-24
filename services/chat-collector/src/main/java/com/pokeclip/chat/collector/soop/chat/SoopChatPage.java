package com.pokeclip.chat.collector.soop.chat;

/**
 * 탭에 띄우는 빈 페이지(POK-397 PR4). 공식 SDK 스크립트를 싣고, 시작 함수 하나({@code __pokeclipStart})를 둔다:
 * {@code new SOOP.ChatSDK(앱 키, 비밀값)} → {@code setAuth(토큰)} → {@code connect()}.
 *
 * <p>🔴 공식 문서 예시의 {@code init()}은 부르지 않는다. 배포된 SDK에 없어 {@code TypeError: s.init is not a function}으로 시작이
 * 던지고, 탭마다 다시 붙기만 돌았다(실방송 2026-10-24). {@code connect()}는 채팅 정보 조회와 소켓 열기를 스스로 하고, 준비 신호
 * ({@code handleReady})는 입장(JOIN)을 마친 뒤 오는 것이라 {@code connect()} 전에 기다릴 것이 없다(배포본 코드로 확인).
 *
 * <p>받은 것 중 채팅({@code MESSAGE} · {@code MANAGER_MESSAGE})과 후원 여섯({@link #DONATIONS}, POK-431)만 자바로 넘긴다. 입장 · 퇴장 ·
 * 공지 · 구독 · 미션 정산 · 퀵뷰 등은 버린다(사용자 결정 Q2 · POK-431 결정 1). 후원은 원본 파일에 안 넣으므로 원문(JSON)은 안 넘긴다.
 * 연결이 닫히면 {@code closed}, 오류면 {@code error}와 코드만 넘긴다(메시지 글은 안 넘긴다: 무엇이 섞일지 모른다).
 *
 * <p>🔴 앱 키 · 비밀값 · 토큰은 HTML 글자에 박지 않는다. 자바가 {@code evaluate}의 인자로 넘긴다(페이지 내용이 어디 찍혀도 안 새게).
 * SDK 주소는 설정값이라 박는다.
 */
final class SoopChatPage {

    /** 받는 후원 action. 이 밖의 후원 비슷한 것(구독 · 정산 · 퀵뷰 · OGQ 선물 등)은 버린다. */
    static final java.util.List<String> DONATIONS = java.util.List.of("BALLOON_GIFTED", "ADBALLOON_GIFTED",
            "VIDEOBALLOON_GIFTED", "STICKER_GIFTED", "BATTLE_MISSION_GIFTED", "CHALLENGE_MISSION_GIFTED");

    private SoopChatPage() {
    }

    static String html(String sdkUrl) {
        String b = SoopChatBrowser.BINDING;
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><script src="%s"></script></head><body><script>
                window.__pokeclipStart = function (cfg) {
                  if (!window.SOOP || !window.SOOP.ChatSDK) {
                    window.%s('error', 'SDK_MISSING');
                    return;
                  }
                  var sdk = new window.SOOP.ChatSDK(cfg.clientId, cfg.clientSecret);
                  sdk.setAuth(cfg.token);
                  var donations = %s;
                  sdk.handleMessageReceived(function (action, message) {
                    var m = message || {};
                    if (donations.indexOf(action) >= 0) {
                      window.%s('donation', action, m.userId || '', m.userNickname || '', String(m.count),
                          String(m.fromVod), String(m.fromStation), String(m.relaysBroad));
                      return;
                    }
                    if (action !== 'MESSAGE' && action !== 'MANAGER_MESSAGE') {
                      return;
                    }
                    window.%s('message', action, m.userId || '', m.userNickname || '', m.message || '', JSON.stringify(m));
                  });
                  sdk.handleChatClosed(function () {
                    window.%s('closed');
                  });
                  sdk.handleError(function (code) {
                    window.%s('error', String(code));
                  });
                  Promise.resolve(sdk.connect()).catch(function (e) {
                    // SDK 오류 코드(예: invalid-chat-info)만 붙인다. 오류 글은 안 넘긴다(토큰이 섞일 수 있다)
                    window.%s('error', 'CONNECT_' + String((e && (e.code || e.name)) || ''));
                  });
                };
                </script></body></html>
                """.formatted(escapeAttr(sdkUrl), b, jsList(DONATIONS), b, b, b, b, b);
    }

    private static String jsList(java.util.List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
