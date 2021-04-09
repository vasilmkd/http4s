/*
 * Copyright 2016 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.asynchttpclient.netty.request.body;

import com.typesafe.netty.HandlerSubscriber;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class NettyReactiveStreamsBody implements NettyBody {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyReactiveStreamsBody.class);
  private static final String NAME_IN_CHANNEL_PIPELINE = "request-body-streamer";

  private final Publisher<ByteBuf> publisher;

  private final long contentLength;

  public NettyReactiveStreamsBody(Publisher<ByteBuf> publisher, long contentLength) {
    this.publisher = publisher;
    this.contentLength = contentLength;
  }

  @Override
  public long getContentLength() {
    return contentLength;
  }

  @Override
  public void write(Channel channel, NettyResponseFuture<?> future) {
    if (future.isStreamConsumed()) {
      LOGGER.warn("Stream has already been consumed and cannot be reset");
    } else {
      future.setStreamConsumed(true);
      NettySubscriber subscriber = new NettySubscriber(channel, future, contentLength != 0);

      channel.eventLoop.execute(() -> {
        channel.pipeline().addLast(NAME_IN_CHANNEL_PIPELINE, subscriber);
        publisher.subscribe(new SubscriberAdapter(subscriber));
      });
        subscriber.delayedStart();
    }
  }

  private static class SubscriberAdapter implements Subscriber<ByteBuf> {
    private final Subscriber<HttpContent> subscriber;

    SubscriberAdapter(Subscriber<HttpContent> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
      subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(ByteBuf buffer) {
      HttpContent content = new DefaultHttpContent(buffer);
      subscriber.onNext(content);
    }

    @Override
    public void onError(Throwable t) {
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      subscriber.onComplete();
    }
  }

  private static class NettySubscriber extends HandlerSubscriber<HttpContent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettySubscriber.class);

    private static final Subscription DO_NOT_DELAY = new Subscription() {
      public void cancel() {}
      public void request(long l) {}
    };

    private final Channel channel;
    private final NettyResponseFuture<?> future;
    private final boolean needsLastHttpContent;
    private AtomicReference<Subscription> deferredSubscription = new AtomicReference<>();

    NettySubscriber(Channel channel, NettyResponseFuture<?> future, boolean needsLastHttpContent) {
      super(channel.eventLoop());
      this.channel = channel;
      this.future = future;
      this.needsLastHttpContent = needsLastHttpContent;
    }

    @Override
    protected void complete() {
      if (channel.isActive()) {
        //Always remove, as we are no longer caring about this subscriber
        removeFromPipeline();
        if (needsLastHttpContent) {
          if (channel.eventLoop().inEventLoop()) {
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
          } else {
            channel.eventLoop().execute(() -> channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));
          }
        }
      }
    }    

    @Override
    public void onSubscribe(Subscription subscription) {
      if (!deferredSubscription.compareAndSet(null, subscription)) {
        super.onSubscribe(subscription);
      }
    }

    void delayedStart() {
      // If we won the race against onSubscribe, we need to tell it
      // know not to delay, because we won't be called again.
      Subscription subscription = deferredSubscription.getAndSet(DO_NOT_DELAY);
      if (subscription != null) {
        super.onSubscribe(subscription);
      }
    }

    @Override
    protected void error(Throwable error) {
      assertNotNull(error, "error");
      removeFromPipeline();
      future.abort(error);
    }

    private void removeFromPipeline() {
      try {
        channel.pipeline().remove(this);
        LOGGER.debug(String.format("Removed handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE));
      } catch (NoSuchElementException e) {
        LOGGER.debug(String.format("Failed to remove handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE), e);
      }
    }
  }
}