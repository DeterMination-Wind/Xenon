/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package determination.xenon.mindustry.community;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that [CountingBodyPublisher] counts delivered bytes without
/// changing the advertised body length.
@NotNullByDefault
public final class CountingBodyPublisherTest {

    /// A file publisher reports every chunk and keeps its exact content length.
    @Test
    public void countsFilePublisherAndKeepsContentLength(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("save.msav");
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Files.write(file, payload);

        List<long[]> reports = new ArrayList<>();
        HttpRequest.BodyPublisher inner = HttpRequest.BodyPublishers.ofFile(file);
        CountingBodyPublisher publisher = new CountingBodyPublisher(inner,
                (read, total) -> reports.add(new long[]{read, total}));

        assertEquals(payload.length, publisher.contentLength(),
                "the fixed content length must survive the wrapper");

        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.await();

        assertEquals(payload.length, subscriber.received);
        assertFalse(reports.isEmpty(), "the publisher must report progress");
        long previous = -1;
        for (long[] report : reports) {
            assertTrue(report[0] > previous, "byte counts must increase monotonically");
            previous = report[0];
            assertEquals(payload.length, report[1], "every report must carry the total size");
        }
        assertEquals(payload.length, previous, "the final report must equal the body size");
    }

    /// A delegate without a known length keeps reporting -1 instead of guessing.
    @Test
    public void forwardsUnknownContentLength() {
        HttpRequest.BodyPublisher inner = new FixedPublisher(-1,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        List<long[]> reports = new ArrayList<>();
        CountingBodyPublisher publisher = new CountingBodyPublisher(inner,
                (read, total) -> reports.add(new long[]{read, total}));

        assertEquals(-1, publisher.contentLength());

        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.awaitQuietly();

        assertEquals(3, subscriber.received);
        assertEquals(1, reports.size());
        assertEquals(3, reports.get(0)[0]);
        assertEquals(-1, reports.get(0)[1]);
    }

    /// Collects delivered bytes until the publisher completes.
    private static final class CollectingSubscriber implements Flow.Subscriber<ByteBuffer> {
        /// Completion signal of the stream.
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        /// Bytes delivered downstream so far.
        private long received;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            received += item.remaining();
            // Consume like the real HTTP transport does.
            item.position(item.limit());
        }

        @Override
        public void onError(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.complete(null);
        }

        /// Waits for completion and keeps checked exceptions in the signature.
        private void await() throws Exception {
            completed.get(10, TimeUnit.SECONDS);
        }

        /// Waits for completion, failing the test on timeout or error.
        private void awaitQuietly() {
            try {
                completed.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("publisher did not complete", e);
            }
        }
    }

    /// Minimal publisher that emits one buffer and a fixed content length.
    private static final class FixedPublisher implements HttpRequest.BodyPublisher {
        /// Advertised body length.
        private final long length;
        /// Single emitted chunk.
        private final ByteBuffer chunk;

        private FixedPublisher(long length, ByteBuffer chunk) {
            this.length = length;
            this.chunk = chunk;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                /// Whether the buffered chunk was already delivered.
                private boolean delivered;

                @Override
                public void request(long n) {
                    if (delivered) {
                        return;
                    }
                    delivered = true;
                    subscriber.onNext(chunk.duplicate());
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    // Nothing to release; the single chunk is already buffered.
                }
            });
        }
    }
}
