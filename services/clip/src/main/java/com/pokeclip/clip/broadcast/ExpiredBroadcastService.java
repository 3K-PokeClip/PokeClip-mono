package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.InvalidListParamException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 1번(영상 서버)이 10분마다 묻는 「보관 기한이 지난 방송」 한 장(POK-335, 계약-세그먼트인덱스 6-2). 1번은 받은 구간의 조각을
 * S3 두 벌 → 장부 행 순으로 지운다(계약 5-2).
 *
 * <p><b>자격 판정이 없다</b>: 부르는 쪽이 우리 서버다(방송 중 목록 창구 POK-218과 같다). 판정 시각은 부르는 쪽이 넘긴
 * {@code before} 하나다: 두 서버 시계가 달라도 판정이 안 갈린다.
 *
 * <p>방송 줄은 만료 뒤에도 남아 「만료된 전부」는 끝없이 커진다. 그래서 한 장 상한과 이어받기 표시를 주고, 어디까지 처리했는지는
 * 1번이 자기 쪽에 저장한다(clip은 1번의 진행을 모른다).
 */
@Service
public class ExpiredBroadcastService {

    /** 한 장 상한. 방송 중 목록 창구(POK-218)와 같은 크기다. 값의 정본은 커밋되는 services/README.md */
    static final int MAX_ROWS = 500;

    private final ExpiredBroadcastReader reader;

    ExpiredBroadcastService(ExpiredBroadcastReader reader) {
        this.reader = reader;
    }

    /** @throws InvalidListParamException before가 없거나 시각(ISO-8601)이 아니다 · {@code InvalidCursorException} 표시가 틀렸다 */
    public ExpiredBroadcastPage list(String before, String cursor) {
        Instant at = parseBefore(before);
        List<Long> after = CursorCodec.decodeOrFirstPage(CursorCodec.Kind.EXPIRED, cursor);
        long afterMicros = after == null ? Long.MIN_VALUE : after.get(0);
        long afterId = after == null ? 0 : after.get(1);

        // 하나 더 읽어 다음 장이 있는지 본다(개수를 따로 세면 질의가 하나 더 돈다)
        List<ExpiredBroadcastRow> rows = reader.read(at, afterMicros, afterId, MAX_ROWS + 1);
        if (rows.size() <= MAX_ROWS) {
            return new ExpiredBroadcastPage(rows, null);
        }
        List<ExpiredBroadcastRow> page = rows.subList(0, MAX_ROWS);
        ExpiredBroadcastRow last = page.get(page.size() - 1);
        return new ExpiredBroadcastPage(page,
                CursorCodec.encode(CursorCodec.Kind.EXPIRED, last.expiresMicros(), last.id()));
    }

    private static Instant parseBefore(String before) {
        if (before == null || before.isBlank()) {
            throw new InvalidListParamException("before");
        }
        try {
            return Instant.parse(before);
        } catch (DateTimeParseException e) {
            throw new InvalidListParamException("before");
        }
    }

    /** 한 장 + 다음 장 표시(없으면 null). */
    public record ExpiredBroadcastPage(List<ExpiredBroadcastRow> rows, String nextCursor) {
    }
}
