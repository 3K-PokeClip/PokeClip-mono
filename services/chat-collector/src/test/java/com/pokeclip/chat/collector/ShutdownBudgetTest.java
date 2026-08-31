package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.broadcast.intake.SqsIntakeLoop;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>종료 예산 네 항의 합이 운영에 넘긴 유예를 안 넘는가</b> (POK-219 감사 G5).
 *
 * <p><b>왜 필요한가</b>: 넘치면 <b>세션 닫기가 잘려 구독이 반납 안 되고 계정 자리가 남는다.</b>
 * 치지직은 스트리머당 자리가 3개뿐이라, 느린 날 재시작을 반복하면 그대로 마른다 —
 * 유예를 15초에서 20초로 올린 이유가 그것이다.
 *
 * <p><b>원래 검사는 이름과 재는 것이 갈려 있었다.</b> {@code 종료_예산이_유예를_안_넘긴다}가
 * {@code JOIN_WAIT + DRAIN_WAIT}만 봤다 — 그 둘을 고정해 두면
 * {@code CLOSE_ALL_BUDGET}(8초)이나 {@code ARCHIVE_CLOSE_WAIT}(5초)가 <b>얼마로 커지든 초록</b>이다.
 * 이름은 유예 전체를 주장하는데 단언은 두 항만 쟀다.
 *
 * <p><b>이 검사는 문서 드리프트 감시다.</b> 어느 항이든 바꾸면 여기가 깨지고, 그때
 * {@code services/README.md}의 「종료 유예를 20초 준다」 표와
 * {@code SessionRegistry.CLOSE_ALL_BUDGET} javadoc을 같이 고치게 된다 —
 * POK-219 전에는 그 두 곳이 <b>15초에 멈춰 있었다.</b>
 *
 * <p><b>왜 리플렉션인가</b>: 네 상수가 서로 다른 패키지의 package-private이다
 * ({@code session} · 루트 · {@code broadcast.intake}). 넷을 다 보는 패키지가 없다.
 * 검사 하나를 위해 운영 코드의 가시성을 넓히지 않는다 — 대신 이름이 바뀌면
 * <b>{@code NoSuchFieldException}으로 시끄럽게</b> 깨진다. 조용히 통과하지 않는다.
 */
class ShutdownBudgetTest {

    /**
     * 운영에 넘긴 제약. 우리 설정이 아니라 배포 쪽 값이다
     * ({@code terminationGracePeriodSeconds} / compose {@code stop_grace_period}).
     *
     * <p>🔴 <b>손으로 베낀 숫자였는데 이제 compose에서 읽는다</b>(POK-219 감사 라운드 3 H7).
     * 그전에는 이 상수와 {@code services/README.md}만 20초를 말하고 <b>compose에는 그 줄이
     * 아예 없었다</b> — 도커 기본 10초가 그대로 먹어 예산 17초가 잘리는데, 여기는 초록이었다.
     * <b>「compose를 보는 검사가 저장소에 0개」가 POK-128부터 적혀 있던 구멍이고 이 값에
     * 한해 메웠다.</b> k8s 쪽({@code infra/})은 1번 폴더라 여기서 못 본다.
     */
    private static final Duration GRACE = composeStopGracePeriod();

    @Test
    void 종료_예산_네_항의_합이_유예를_안_넘는다() {
        Duration join = duration(SqsIntakeLoop.class, "JOIN_WAIT");
        Duration drain = duration(SqsIntakeLoop.class, "DRAIN_WAIT");
        Duration closeSessions = duration(SessionRegistry.class, "CLOSE_ALL_BUDGET");
        Duration flush = duration(CollectorRunner.class, "ARCHIVE_CLOSE_WAIT");

        Duration total = join.plus(drain).plus(closeSessions).plus(flush);

        assertThat(total)
                .as("join %s + 줄 비우기 %s + 세션 닫기 %s + flush %s", join, drain, closeSessions, flush)
                .isEqualTo(Duration.ofSeconds(17))
                .isLessThan(GRACE);
    }

    /**
     * 🔴 <b>여유가 3초뿐이라는 것을 글자로 못박는다.</b> 이 숫자가 조용히 0이 되는 것이
     * 정확히 그 사고다 — {@code DRAIN_WAIT}를 6초로 잡으면 합이 21초가 되어 유예를 넘긴다.
     */
    @Test
    void 남은_여유가_3초임을_못박는다() {
        Duration total = duration(SqsIntakeLoop.class, "JOIN_WAIT")
                .plus(duration(SqsIntakeLoop.class, "DRAIN_WAIT"))
                .plus(duration(SessionRegistry.class, "CLOSE_ALL_BUDGET"))
                .plus(duration(CollectorRunner.class, "ARCHIVE_CLOSE_WAIT"));

        assertThat(GRACE.minus(total))
                .as("여유를 줄이려면 README의 종료 예산 표를 같이 고쳐라")
                .isEqualTo(Duration.ofSeconds(3));
    }

    /**
     * <b>{@code chat-collector} 블록만 본다.</b> 파일 전체에 정규식을 던지면 남의 서비스가
     * 적은 값을 우리 것으로 읽는다. 블록 경계는 「들여쓰기 2칸 + 콜론」인 다음 줄이다 —
     * YAML 파서를 새로 물지 않으려고 이 폭으로 좁혔다(이 파일의 서비스 키가 전부 그 모양이다).
     *
     * <p>못 찾으면 <b>시끄럽게 터진다.</b> 조용히 20초로 떨어지면 이 검사는 아무것도 안 잰다.
     */
    private static Duration composeStopGracePeriod() {
        Path compose = Path.of("..", "docker-compose.dev.yml");
        List<String> lines;
        try {
            lines = Files.readAllLines(compose, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("services/docker-compose.dev.yml을 못 읽었다 — "
                    + "작업 디렉터리가 바뀌었나", e);
        }
        boolean inBlock = false;
        for (String line : lines) {
            if (line.matches("^ {2}\\S.*:\\s*$")) {
                inBlock = line.strip().equals("chat-collector:");
                continue;
            }
            if (inBlock && line.strip().startsWith("stop_grace_period:")) {
                String value = line.substring(line.indexOf(':') + 1).strip();
                return Duration.ofSeconds(Long.parseLong(value.replace("s", "")));
            }
        }
        throw new AssertionError("docker-compose.dev.yml의 chat-collector 블록에 "
                + "stop_grace_period가 없다 — 도커 기본 10초라 종료 예산 17초가 잘린다. "
                + "구독이 반납 안 되고 계정 자리가 남는다(services/README.md 「종료 유예를 20초 준다」)");
    }

    private static Duration duration(Class<?> owner, String field) {
        try {
            Field found = owner.getDeclaredField(field);
            found.setAccessible(true);
            return (Duration) found.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(
                    owner.getSimpleName() + "." + field + "을(를) 못 읽는다 — 이름이 바뀌었으면 "
                            + "종료 예산 표(services/README.md)도 같이 봐야 한다", e);
        }
    }
}
