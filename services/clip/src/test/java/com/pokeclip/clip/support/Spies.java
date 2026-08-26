package com.pokeclip.clip.support;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * 🔴 <b>{@code @MockitoSpyBean}이 인터페이스 빈을 감싸면 {@code invocation.callRealMethod()}가
 * 안 된다.</b> 스프링 데이터 리포지터리가 정확히 그 모양이다.
 *
 * <p><b>스프링이 둘을 다르게 감싼다</b>(spring-test 7.0.8 {@code MockitoSpyBeanOverrideHandler}
 * 바이트코드 확인):
 * <ul>
 *   <li>보통 빈(구상 클래스) → {@code defaultAnswer(CALLS_REAL_METHODS)}.
 *       {@code callRealMethod()}가 된다</li>
 *   <li><b>JDK 프록시</b>(리포지터리) → {@code defaultAnswer(AdditionalAnswers.delegatesTo(실물))}.
 *       목의 타입이 <b>인터페이스</b>라 실물 메서드가 추상 메서드가 되고,
 *       {@code getSpiedInstance()}는 <b>{@code null}</b>이다</li>
 * </ul>
 *
 * <p>2026-08-26 실측 — 네 갈래가 {@code MockitoException: Cannot call abstract real method on
 * java object!}로 <b>500</b>이 됐다. 안 걸어 둔 메서드는 실물로 그대로 흘러가므로
 * ({@code save}·{@code deleteAllInBatch}는 멀쩡했다) <b>답을 다는 순간에만</b> 드러난다.
 * 그 뒤 {@code getSpiedInstance()}로 실물을 꺼내려 한 것도 <b>{@code null}이라 실패했다</b> —
 * 「인터페이스면 spiedInstance일 것이다」가 틀렸다.
 *
 * <p>그래서 <b>그 빈에 실제로 걸린 기본 답</b>을 꺼내 그대로 부른다. 두 감싸기 방식 어느 쪽이든
 * 같은 코드가 통한다 — 스프링이 방식을 바꿔도 여기가 따라간다.
 */
public final class Spies {

    /**
     * 이 호출을 <b>감시하지 않았을 때 일어났을 일</b>을 그대로 일으키고 결과를 준다.
     * {@code doAnswer} 안에서 부작용을 끼워 넣고 실물 결과를 돌려줄 때 쓴다.
     */
    public static Object real(InvocationOnMock invocation) throws Throwable {
        Answer<?> asIfNotStubbed = Mockito.mockingDetails(invocation.getMock())
                .getMockCreationSettings().getDefaultAnswer();
        return asIfNotStubbed.answer(invocation);
    }

    private Spies() {
    }
}
