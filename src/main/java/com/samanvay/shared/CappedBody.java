package com.samanvay.shared;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body handler that never buffers more than {@code maxBytes + 1} bytes and stops the download there: the extra byte is
 * how a caller tells "exactly the cap" from "over it" ({@code body.length > maxBytes}) without holding a huge or endless answer in
 * memory. (The JDK's own limiting handler arrived after the Java release this builds for.)
 */
public final class CappedBody {

    private CappedBody() {}

    public static HttpResponse.BodyHandler<byte[]> upTo(long maxBytes) {
        return info -> new Capped(maxBytes);
    }

    private static final class Capped implements HttpResponse.BodySubscriber<byte[]> {

        private final long max;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        Capped(long max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer b : items) {
                int take = (int) Math.min(b.remaining(), max + 1 - buffer.size());
                byte[] chunk = new byte[take];
                b.get(chunk);
                buffer.write(chunk, 0, take);
            }
            if (buffer.size() > max) {
                done = true;
                subscription.cancel();
                result.complete(buffer.toByteArray());
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!done) {
                done = true;
                result.completeExceptionally(t);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                result.complete(buffer.toByteArray());
            }
        }
    }
}
