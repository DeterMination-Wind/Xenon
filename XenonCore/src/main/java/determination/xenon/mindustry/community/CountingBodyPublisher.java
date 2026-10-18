/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import determination.xenon.mindustry.download.ProgressCallback;
import org.jetbrains.annotations.NotNullByDefault;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;

/// Body publisher that reports how many request bytes have been handed to the
/// HTTP client.
///
/// Used to drive the cloud-save upload progress bar: `java.net.http` offers no
/// upload progress hook, so the publisher wraps the real body publisher and
/// counts each delivered [ByteBuffer] without consuming it.
///
/// The advertised [contentLength] is forwarded unchanged so an unknown-length
/// delegate cannot silently turn a fixed-length upload into a chunked one. The
/// counter is per subscription: a retried request starts counting from zero
/// again, which keeps the reported total monotonic within one attempt.
@NotNullByDefault
public final class CountingBodyPublisher implements HttpRequest.BodyPublisher {
    /// Publisher that actually produces the request bytes.
    private final HttpRequest.BodyPublisher delegate;
    /// Receives cumulative byte counts, once per delivered buffer.
    private final ProgressCallback progress;
    /// Expected body size reported by the delegate.
    private final long contentLength;

    /// Wraps a publisher with byte counting.
    ///
    /// @param delegate publisher that produces the request bytes
    /// @param progress receiver of cumulative byte counts
    public CountingBodyPublisher(HttpRequest.BodyPublisher delegate, ProgressCallback progress) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.contentLength = delegate.contentLength();
    }

    /// Reports the delegate's body size so the request stays fixed-length.
    ///
    /// @return the declared body size, or -1 when the delegate does not know it
    @Override
    public long contentLength() {
        return contentLength;
    }

    /// Subscribes the downstream subscriber through a counting forwarder.
    ///
    /// @param subscriber subscriber installed by the HTTP client
    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        delegate.subscribe(new CountingSubscriber(subscriber));
    }

    /// Forwards every item while accumulating the delivered byte count.
    private final class CountingSubscriber implements Flow.Subscriber<ByteBuffer> {
        /// Downstream subscriber installed by the HTTP client.
        private final Flow.Subscriber<? super ByteBuffer> downstream;
        /// Bytes handed downstream so far in this subscription.
        private long sent;

        /// Creates the per-subscription forwarder.
        private CountingSubscriber(Flow.Subscriber<? super ByteBuffer> downstream) {
            this.downstream = Objects.requireNonNull(downstream, "downstream");
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(subscription);
        }

        @Override
        public void onNext(ByteBuffer item) {
            // Count before handing the buffer over: the downstream transport
            // advances the position, but the wrapper must not consume the item.
            sent += item.remaining();
            progress.onProgress(sent, contentLength);
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }
}
