package com.pokeclip.auth.config;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우리 코드가 아니라 톰캣 {@code RemoteIpValve}의 성질을 못박는 <b>카나리아</b>다 — {@code src/main} 어디를 바꿔도
 * 빨간불이 안 나고, <b>톰캣이나 Boot 판을 올릴 때</b> 난다(앞 셋은 톰캣의 규칙, 마지막 하나는 Boot가 주는 기본
 * 신뢰 대역). README·yml 주석의 「대역 밖 소켓의 헤더는 통째로 무시」가 기대는 규칙이 그것이라, 그 규칙이 바뀌면
 * 문서와 한도 우회 판단이 같이 낡는다.
 *
 * <p><b>대역은 CIDR로 준다.</b> 톰캣 11의 {@code setInternalProxies}는 {@code '/'}가 있으면 CIDR
 * ({@code NetMaskSet.parse}), 없으면 정규식으로 가른다(11.0.22 소스 828~839줄). 정규식만 받던 것은
 * 톰캣 10 이전이다. Boot가 주는 기본값 자체가 CIDR 표기라({@code 10.0.0.0/8, 127.0.0.0/8, …}) 여기서만
 * 정규식을 쓰면 아래 마지막 갈래와 형식이 갈린다. PR #169에서 리뷰 봇이 이 자리를 「정규식이어야 한다」고
 * 지적했으나 위 셋으로 반증했다.
 *
 * <p>진짜 톰캣으로는 소켓을 공인 IP로 못 만들어 Valve를 직접 돌린다(실물 Valve·실물 Request. 가짜는 다음 Valve 하나뿐이다).
 */
class RemoteIpValveTrustTest {

    @Test
    void 대역_밖_소켓의_헤더는_무시된다() {
        assertThat(remoteAddrSeenBy("10.0.0.0/8", "203.0.113.50", "203.0.113.10")).isEqualTo("203.0.113.50");
    }

    @Test
    void 대역_안_소켓의_헤더는_채택된다() {
        assertThat(remoteAddrSeenBy("10.0.0.0/8", "10.0.0.5", "203.0.113.10")).isEqualTo("203.0.113.10");
    }

    /**
     * 「대역 밖 소켓의 헤더는 통째로 무시」의 반례 — 소켓이 대역 <b>안</b>이면 헤더를 오른쪽부터 읽되 대역 안 값은 건너뛰고
     * 처음 만나는 밖 값을 채택한다. 그래서 사설망에서 교환 창구를 부르는 호출자가 생기면 그 호출자가 왼쪽에 적은 값이
     * IP당 한도의 열쇠가 된다(톰캣 11.0.22 {@code RemoteIpValve} 544~576줄, 리뷰 라운드 1 스크래치 재현).
     */
    @Test
    void 대역_안_소켓이_보낸_헤더의_오른쪽_값도_대역_안이면_왼쪽_값을_채택한다() {
        assertThat(remoteAddrSeenBy("10.0.0.0/8", "10.0.0.7", "9.9.9.9, 10.0.1.5")).isEqualTo("9.9.9.9");
    }

    /**
     * 위 셋은 신뢰 대역을 <b>손으로</b> 준다 — 그래서 운영에서 실제로 쓰이는 대역(설정을 안 하면 Boot 기본값)이
     * 넓어지는 것은 하나도 못 잰다. 여기서는 그 기본값을 그대로 넣고 <b>공인 소켓</b>이 보낸 헤더가 무시되는지 본다:
     * 기본 대역에 공인 IP가 끼는 날 빨간불이 나고, 그날 교환 창구의 IP당 한도는 헤더 한 줄로 우회된다.
     *
     * <p>기본값이 비면 아무 대역도 안 믿어 <b>단언이 저절로 참</b>이 된다. 그래서 값이 비지 않았는지를 먼저 본다.
     * ({@code server.tomcat.remoteip.*}는 Boot 4에서 {@code TomcatServerProperties}로 옮겨 갔다 — 그 클래스가
     * 사라지면 컴파일이 먼저 깨져 이 카나리아가 조용히 죽지 않는다.)
     *
     * <p><b>두 방향을 같이 잰다</b>(리뷰 라운드 3). 위 단언만 두면 대역이 <b>넓어지는</b> 쪽만 잡힌다 —
     * {@code isNotBlank()}는 「비지 않았다」만 보므로, 기본값에서 사설 대역이 빠지는(좁아지는) 날에도 네 갈래가
     * 전부 초록이다. 그날 운영 native에서는 ALB(10.x)가 붙인 헤더를 Valve가 무시해 <b>IP당 한도가 다시
     * 전역 한도가 된다</b>(이 카드가 막으려던 상태). 그래서 같은 기본값으로 사설 소켓의 헤더가 채택되는지도 본다.
     */
    @Test
    void 운영_기본_대역이_공인은_안_믿고_사설망은_믿는다() {
        String bootDefault = new TomcatServerProperties().getRemoteip().getInternalProxies();

        assertThat(bootDefault)
                .as("Boot 기본 신뢰 대역이 비었다 — 아래 단언이 아무것도 안 재게 된다")
                .isNotBlank();
        assertThat(remoteAddrSeenBy(bootDefault, "203.0.113.50", "203.0.113.10"))
                .as("Boot 기본 대역이 공인 IP까지 믿는다 — 헤더 한 줄로 IP당 한도가 우회된다")
                .isEqualTo("203.0.113.50");
        assertThat(remoteAddrSeenBy(bootDefault, "10.0.0.5", "203.0.113.10"))
                .as("Boot 기본 대역에 사설망이 없다 — 운영 native에서 ALB가 붙인 헤더를 무시해 한도가 전역으로 돌아간다")
                .isEqualTo("203.0.113.10");
    }

    private static String remoteAddrSeenBy(String internalProxies, String socketAddr, String forwardedFor) {
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(internalProxies);
        AtomicReference<String> seen = new AtomicReference<>();
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                seen.set(request.getRemoteAddr());
            }
        });

        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.remoteAddr().setString(socketAddr);
        coyote.getMimeHeaders().addValue("x-forwarded-for").setString(forwardedFor);
        Request request = new Request(new Connector(), coyote);
        Response response = new Response(new org.apache.coyote.Response());

        try {
            valve.invoke(request, response);
        } catch (Exception e) {
            throw new IllegalStateException("Valve 호출 실패", e);
        }
        return seen.get();
    }
}
