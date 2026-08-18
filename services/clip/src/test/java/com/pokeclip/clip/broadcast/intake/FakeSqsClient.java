package com.pokeclip.clip.broadcast.intake;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 가짜 큐. {@code receiveMessage}·{@code deleteMessage} 둘만 동작한다 —
 * {@code SqsClient}의 나머지 메서드는 SDK가 default로 {@code UnsupportedOperationException}을
 * 던지게 해 뒀으므로 여기서 다시 안 쓴다. 러너가 그 밖의 무언가를 부르면 그대로 터져서
 * 드러난다.
 *
 * <p>receiptHandle은 순번대로 {@code rh-0}·{@code rh-1}… 이다. 어느 편지를 지웠는지
 * 단언에서 이름으로 짚을 수 있어야 "지우긴 지웠다"가 아니라 "그 편지를 지웠다"를 잰다.
 */
final class FakeSqsClient implements SqsClient {

    private final List<Message> messages;
    private final boolean failOnReceive;
    private boolean failOnDelete;
    private final java.util.Deque<Boolean> script = new java.util.ArrayDeque<>();
    private boolean scripted;
    private final List<String> deleted = new ArrayList<>();

    private FakeSqsClient(List<Message> messages, boolean failOnReceive) {
        this.messages = messages;
        this.failOnReceive = failOnReceive;
    }

    static FakeSqsClient withMessages(String... bodies) {
        List<Message> messages = IntStream.range(0, bodies.length)
                .mapToObj(i -> Message.builder()
                        .messageId("msg-" + i)
                        .receiptHandle("rh-" + i)
                        .body(bodies[i])
                        .build())
                .toList();
        return new FakeSqsClient(messages, false);
    }

    /** 큐에 못 닿는 상황. 러너가 이 예외를 밖으로 흘리면 폴링 루프가 죽는다. */
    static FakeSqsClient thatFails() {
        return new FakeSqsClient(List.of(), true);
    }

    /**
     * 회차마다 닿을지 말지를 대본대로 정한다 — {@code true}면 빈 응답(성공),
     * {@code false}면 던진다. <b>대본이 끝나면 계속 실패한다</b>: 성공으로 끝나면
     * 루프가 쉬지 않고 도는 상태가 되어 검사가 안 끝난다.
     */
    static FakeSqsClient scripted(boolean... reachable) {
        FakeSqsClient client = new FakeSqsClient(List.of(), false);
        client.scripted = true;
        for (boolean ok : reachable) {
            client.script.add(ok);
        }
        return client;
    }

    /** 꺼내기는 되는데 지우기만 실패한다 — 처리 실패와 갈라 보기 위한 갈래. */
    static FakeSqsClient thatFailsOnDelete(String... bodies) {
        FakeSqsClient client = withMessages(bodies);
        client.failOnDelete = true;
        return client;
    }

    List<String> deletedReceiptHandles() {
        return List.copyOf(deleted);
    }

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        if (failOnReceive) {
            throw new IllegalStateException("큐에 못 닿는다");
        }
        if (scripted) {
            // 대본이 끝났으면(null) 계속 실패한다 — 성공으로 끝나면 루프가 쉬지 않고
            // 돌아 검사가 안 끝난다.
            Boolean reachable = script.poll();
            if (reachable == null || !reachable) {
                throw new IllegalStateException("큐에 못 닿는다");
            }
        }
        return ReceiveMessageResponse.builder().messages(messages).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
        if (failOnDelete) {
            throw new IllegalStateException("큐에 못 닿는다 — 삭제 실패");
        }
        deleted.add(request.receiptHandle());
        return DeleteMessageResponse.builder().build();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
    }
}
