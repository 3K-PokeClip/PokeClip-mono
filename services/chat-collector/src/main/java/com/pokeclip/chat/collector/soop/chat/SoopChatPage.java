package com.pokeclip.chat.collector.soop.chat;

/**
 * 탭에 띄우는 빈 페이지(POK-397 PR4). 공식 SDK 스크립트를 싣고, 시작 함수 하나({@code __pokeclipStart})를 둔다. 문서 예시 순서 그대로다:
 * {@code new SOOP.ChatSDK(앱 키, 비밀값)} → {@code init()} → {@code setAuth(토큰)} → {@code connect()}.
 *
 * <p>받은 것 중 채팅({@code MESSAGE} · {@code MANAGER_MESSAGE})만 자바로 넘긴다. 입장 · 퇴장 · 공지 등은 버린다(사용자 결정 Q2).
 * 연결이 닫히면 {@code closed}, 오류면 {@code error}와 코드만 넘긴다(메시지 글은 안 넘긴다: 무엇이 섞일지 모른다).
 *
 * <p>🔴 앱 키 · 비밀값 · 토큰은 HTML 글자에 박지 않는다. 자바가 {@code evaluate}의 인자로 넘긴다(페이지 내용이 어디 찍혀도 안 새게).
 * SDK 주소는 설정값이라 박는다.
 */
final class SoopChatPage {

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
                  sdk.init();
                  sdk.setAuth(cfg.token);
                  sdk.handleMessageReceived(function (action, message) {
                    if (action !== 'MESSAGE' && action !== 'MANAGER_MESSAGE') {
                      return;
                    }
                    var m = message || {};
                    window.%s('message', action, m.userId || '', m.userNickname || '', m.message || '', JSON.stringify(m));
                  });
                  sdk.handleChatClosed(function () {
                    window.%s('closed');
                  });
                  sdk.handleError(function (code) {
                    window.%s('error', String(code));
                  });
                  Promise.resolve(sdk.connect()).catch(function () {
                    window.%s('error', 'CONNECT');
                  });
                };
                </script></body></html>
                """.formatted(escapeAttr(sdkUrl), b, b, b, b, b);
    }

    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
