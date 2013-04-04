/* Copyright © 2013 VMware, Inc. All rights reserved. */
package com.rabbitmq.jms.client;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.MessageConsumer;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.jms.util.Abortable;
import com.rabbitmq.jms.util.RJMSLogger;
import com.rabbitmq.jms.util.RJMSLogger.LogTemplate;
import com.rabbitmq.jms.util.TimeTracker;

/**
 * A {@link Consumer} to feed messages into a receive buffer. It has control methods <code>register()</code>,
 * <code>cancel()</code>, <code>abort()</code>, <code>start()</code> and <code>stop()</code>.
 * <p>
 * This is used to support the JMS semantics described in {@link MessageConsumer#receive()} and
 * {@link MessageConsumer#receive(long)}.
 * </p>
 */
class ReceiveConsumer implements Consumer, Abortable {
    private final RJMSLogger LOGGER = new RJMSLogger(new LogTemplate(){
        @Override
        public String template() {
            return "ReceiveConsumer("+ReceiveConsumer.this.consTag+")";
        }
    });

    private static final GetResponse EOF_RESPONSE = new GetResponse(null, null, null, 0);

    private static final long CANCELLATION_TIMEOUT = 1000; // milliseconds

//    private final int batchingSize; // currently ignored
    private final Channel channel;
    private final String rmqQueueName;
    private final boolean noLocal;
    private final BlockingQueue<GetResponse> buffer;

    private final Completion completion = new Completion(); // completed when cancelled.
    private final String consTag;

    private final Object lock = new Object(); // synchronising lock
      private boolean aborted = false; // @GuardedBy("lock")

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    ReceiveConsumer(Channel channel, String rmqQueueName, boolean noLocal, BlockingQueue<GetResponse> buffer, int batchingSize) {
//      this.batchingSize = Math.max(batchingSize, 1); // must be at least 1 // currently ignored
      this.rmqQueueName = rmqQueueName;
      this.buffer = buffer;
      this.channel = channel;
      this.noLocal = noLocal;
      this.consTag = RMQMessageConsumer.newConsumerTag(); // generate unique consumer tag for our private use
  }

    @Override
    public void handleConsumeOk(String consumerTag) {
        synchronized (this.lock) {
            LOGGER.log("handleConsumeOK");
        }
    }

    @Override
    public void handleCancelOk(String consumerTag) {
        synchronized (this.lock) {
            LOGGER.log("handleCancelOk");
            this.completion.setComplete();
        }
    }

    @Override
    public void handleCancel(String consumerTag) throws IOException {
        LOGGER.log("handleCancel");
        if (this.isCancelled.compareAndSet(false, true)) {
            this.completion.setComplete();
            this.abort();
        }
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
        LOGGER.log("handleDelivery");
        GetResponse response = new GetResponse(envelope, properties, body, 1);
        this.handleDelivery(consumerTag, response);
    }

    private final void handleDelivery(String consumerTag, GetResponse response) throws IOException {
        this.cancel(false); // cancel and don't wait for completion

        synchronized (this.lock) {
            if (!this.aborted /* && this.buffer.size() < this.batchingSize */ ) { // room in buffer
                try {
                    LOGGER.log("handleDelivery", "put messsage");
                    this.buffer.put(response);
                    return;
                } catch (InterruptedException e) {
                    LOGGER.log("handleDelivery",e,"buffer.put");
                    Thread.currentThread().interrupt(); // reinstate interrupt status
                    this.abort();                       // we abort if interrupted
                }
            }
            /* Drop through if we do not put message in buffer. */
            /* We never ACK any message, that is the responsibility of the caller. */
            try {
                long dtag = response.getEnvelope().getDeliveryTag();
                LOGGER.log("handleDelivery", "basicNack:", dtag);
                this.channel.basicNack(dtag,
                                       false, // single message
                                       true); // requeue this message
            } catch (IOException e) {
                LOGGER.log("handleDelivery",e,response.getEnvelope());
                this.abort();
                throw e; // RabbitMQ should close the channel
            }
        }
    }

    @Override
    public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
        LOGGER.log("handleShutdownSignal");
        if (this.isCancelled.compareAndSet(false, true)) {
            this.completion.setComplete();  // in case anyone is waiting for this
            this.abort(); // ensure the receivers know
        }
    }

    @Override
    public void handleRecoverOk(String consumerTag) {
        LOGGER.log("handleRecoverOk");
        // noop
    }

    /**
     * Issue Consumer cancellation, and wait for the confirmation from the server.
     */
    public void cancel() {
        this.cancel(true); // wait for completion
    }

    private final void cancel(boolean wait) {
        LOGGER.log("cancel", wait);
        if (this.isCancelled.compareAndSet(false, true)) {
            try {
                LOGGER.log("cancel", "basicCancel");
                this.channel.basicCancel(this.consTag); // potential alien call
            } catch (ShutdownSignalException x) {
                LOGGER.log("cancel", x, "basicCancel");
                this.abort();
            } catch (IOException x) {
                if (!(x.getCause() instanceof ShutdownSignalException)) {
                    LOGGER.log("cancel", x, "basicCancel");
                }
                this.abort();
            }
        }
        if (wait) { // don't wait holding the lock
            try {
                this.completion.waitUntilComplete(new TimeTracker(CANCELLATION_TIMEOUT, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                LOGGER.log("cancel", e, "waitUntilComplete");
            } catch (InterruptedException e) {
                this.abort();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void abort() {
        synchronized (this.lock) {
            LOGGER.log("abort");
            if (this.aborted)
                return;
            this.aborted = true;
            this.completion.setComplete();
            try {
                this.buffer.put(EOF_RESPONSE);
            } catch (InterruptedException _) {
                // we are aborting anyway
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop() {
    }

    @Override
    public void start() {
    }

    void register() {
        try {
            LOGGER.log("register", "basicConsume");
            this.channel.basicConsume(this.rmqQueueName, // queue we are listening on
                                      false, // no autoAck - caller does all ACKs
                                      this.consTag, // generated on construction
                                      this.noLocal, // noLocal option
                                      false, // not exclusive
                                      null, // no arguments
                                      this); // drive this consumer
        } catch (IOException e) {
            LOGGER.log("register", e, "basicConsume");
        }
    }

    public static boolean isEOFMessage(GetResponse response) {
        return response==EOF_RESPONSE;
    }
}