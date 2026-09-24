package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.liveinfo.BroadcastInfoController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 범위 창구들의 400. <b>이 서버의 첫 {@code @ControllerAdvice}다.</b>
 *
 * <p>🔴 <b>{@code assignableTypes}로 범위를 좁힌 이유</b> — 안 좁히면 이미 있는 창구
 * ({@code VideoPositionController}·{@code ChatCollectionStatusController})의 오류 경로까지
 * 바뀐다. 그 둘은 500을 <b>스프링 기본 본문</b>으로 내는 것이 계약이고, 여기 조언이 전역으로
 * 걸리면 그 계약이 조용히 갈린다.
 *
 * <p>🔴 <b>이 목록은 손으로 늘린다.</b> 빠뜨린 창구는 400이 <b>500으로</b> 나가고, 부르는 쪽은
 * 자기 입력 오류를 「수집 서버 장애」로 읽는다(계획 검증 F13).
 * <ul>
 *   <li>{@code liveinfo.BroadcastInfoController} — {@code since}를 {@code WindowRequest}의
 *       그물로 보므로 여기 들어 있다. 빼면 {@code since} 오타가 500으로 나가고
 *       {@code BroadcastInfoEndpointTest.모르는_since는_400이지_500이_아니다}가 빨간불이다</li>
 * </ul>
 *
 * <p>본문은 <b>사유 낱말 하나</b>다. 받은 값을 되비추지 않는다 — 반사된 값이 그대로 로그와
 * 화면으로 흐른다(영상 위치 창구의 같은 결정).
 */
@RestControllerAdvice(assignableTypes = {ChatWindowController.class, ChatChartController.class,
        BroadcastInfoController.class})
public class QueryErrors {

    @ExceptionHandler(InvalidWindowException.class)
    ResponseEntity<Error> invalidWindow(InvalidWindowException e) {
        return ResponseEntity.badRequest().body(new Error(e.reason()));
    }

    /**
     * 사유를 {@code cursor} 하나로 접는다 — 커서 안이 왜 틀렸는지는 부르는 쪽이 고칠 수 있는
     * 정보가 아니고(그 값은 우리가 만든다), 자세히 적으면 우리 모양을 알려 주는 셈이다.
     */
    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<Error> invalidCursor(InvalidCursorException e) {
        return ResponseEntity.badRequest().body(new Error("cursor"));
    }

    /** 400 본문. <b>우리가 정한다</b> — 스프링 기본 본문은 무엇이 틀렸는지를 안 알려 준다. */
    record Error(String error) {
    }
}
