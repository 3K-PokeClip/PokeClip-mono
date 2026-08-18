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

    List<String> deletedReceiptHandles() {
        return List.copyOf(deleted);
    }

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        if (failOnReceive) {
            throw new IllegalStateException("큐에 못 닿는다");
        }
        return ReceiveMessageResponse.builder().messages(messages).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
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
