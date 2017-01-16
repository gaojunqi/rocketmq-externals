/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.flume.ng.sink;

import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.alibaba.rocketmq.client.producer.SendCallback;
import com.alibaba.rocketmq.client.producer.SendResult;
import com.alibaba.rocketmq.common.message.Message;
import com.google.common.base.Throwables;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.BATCH_SIZE_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.BATCH_SIZE_DEFAULT;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.MAX_PROCESS_TIME_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.MAX_PROCESS_TIME_DEFAULT;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.NAME_SERVER_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.PRODUCER_GROUP_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.PRODUCER_GROUP_DEFAULT;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.TAG_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.TAG_DEFAULT;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.TOPIC_CONFIG;
import static org.apache.rocketmq.flume.ng.sink.RocketMQSinkConstants.TOPIC_DEFAULT;

/**
 *
 */
public class RocketMQSink extends AbstractSink implements Configurable {

    private static final Logger log = LoggerFactory.getLogger(RocketMQSink.class);

    private String nameServer;
    private String topic;
    private String tag;
    private String producerGroup;
    private int batchSize;
    private long maxProcessTime;

    private DefaultMQProducer producer;

    @Override
    public void configure(Context context) {

        nameServer = context.getString(NAME_SERVER_CONFIG);
        if (nameServer == null) {
            throw new ConfigurationException("NameServer must not be null");
        }

        topic = context.getString(TOPIC_CONFIG, TOPIC_DEFAULT);
        tag = context.getString(TAG_CONFIG, TAG_DEFAULT);
        producerGroup = context.getString(PRODUCER_GROUP_CONFIG, PRODUCER_GROUP_DEFAULT);
        batchSize = context.getInteger(BATCH_SIZE_CONFIG, BATCH_SIZE_DEFAULT);
        maxProcessTime = context.getLong(MAX_PROCESS_TIME_CONFIG, MAX_PROCESS_TIME_DEFAULT);
    }

    @Override
    public synchronized void start() {

        producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        try {
            producer.start();
        } catch (MQClientException e) {
            log.error("RocketMQ producer start failed", e);
            throw Throwables.propagate(e);
        }

        super.start();
    }

    @Override
    public Status process() throws EventDeliveryException {

        Channel channel = getChannel();
        Transaction transaction = null;

        try {
            transaction = channel.getTransaction();
            transaction.begin();

            /*
            batch take
             */
            List<Event> events = new ArrayList<Event>();
            long beginTime = System.currentTimeMillis();
            while (true) {
                Event event = channel.take();
                if (event != null) {
                    events.add(event);
                }

                if (events.size() == batchSize
                    || System.currentTimeMillis() - beginTime > maxProcessTime) {
                    break;
                }
            }

            if (events.size() == 0) {
                transaction.rollback();
                return Status.BACKOFF;
            }
            /*
            async send
             */
            CountDownLatch latch = new CountDownLatch(events.size());
            AtomicInteger errorNum = new AtomicInteger();

            for (Event event : events) {
                byte[] body = event.getBody();
                Message message = new Message(topic, tag, body);

                if (log.isDebugEnabled()) {
                    log.debug("Processing event,body={}", new String(body, "UTF-8"));
                }
                producer.send(message, new SendCallBackHandler(message, latch, errorNum));
            }
            latch.await();


            if (errorNum.get() > 0) {
                log.error("errorNum=" + errorNum + ",transaction will rollback");
                transaction.rollback();
                return Status.BACKOFF;
            } else {
                transaction.commit();
                return Status.READY;
            }


        } catch (Exception e) {
            log.error("Failed to processing event", e);

            if (transaction != null) {
                try {
                    transaction.rollback();
                } catch (Exception ex) {
                    log.error("Failed to rollback transaction", ex);
                    throw Throwables.propagate(e);
                }
            }

            return Status.BACKOFF;

        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
    }

    @Override public synchronized void stop() {
        producer.shutdown();
        super.stop();
    }


    public class SendCallBackHandler implements SendCallback {

        private Message message;
        private CountDownLatch latch;
        private AtomicInteger errorNum;

        SendCallBackHandler(Message message, CountDownLatch latch, AtomicInteger errorNum) {
            this.message = message;
            this.latch = latch;
            this.errorNum = errorNum;
        }

        @Override
        public void onSuccess(SendResult sendResult) {

            latch.countDown();

            if (log.isDebugEnabled()) {
                try {
                    log.debug("Sended event,body={},sendResult={}", new String(message.getBody(), "UTF-8"), sendResult);
                } catch (UnsupportedEncodingException e) {
                    log.error("Encoding error", e);
                }
            }
        }

        @Override
        public void onException(Throwable e) {
            latch.countDown();
            errorNum.incrementAndGet();

            try {
                log.error("Message publish failed,body=" + new String(message.getBody(), "UTF-8"), e);
            } catch (UnsupportedEncodingException e1) {
                log.error("Encoding error", e);
            }
        }
    }
}