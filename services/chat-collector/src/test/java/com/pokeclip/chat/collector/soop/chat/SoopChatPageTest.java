package com.pokeclip.chat.collector.soop.chat;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 페이지가 부르는 SDK 메서드는 배포된 SDK에 실제로 있는 것뿐이다(없는 {@code init()}이 실방송에서 탭마다 던졌다). */
class SoopChatPageTest {

    @Test
    void 페이지는_배포된_SDK에_있는_메서드만_부른다() {
        Matcher calls = Pattern.compile("\\bsdk\\.(\\w+)\\(").matcher(SoopChatPage.html("http://127.0.0.1/sdk.js"));
        java.util.List<String> called = new java.util.ArrayList<>();
        while (calls.find()) {
            called.add(calls.group(1));
        }

        assertThat(called).as("부르는 메서드가 하나도 안 잡히면 이 시험은 아무것도 안 잰다").contains("setAuth", "connect");
        assertThat(FakeSoopSdkServer.REAL_METHODS).containsAll(called);
    }
}
