/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import java.io.File;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.amq.AMQPersistenceAdapter;
import org.apache.activemq.store.amq.AMQPersistenceAdapterFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @version $Revision: 1.5 $
 * A Test case for AMQ-1479
 */
public class DurableConsumerTest extends TestCase {
    private static final Log LOG = LogFactory.getLog(DurableConsumerTest.class);
    private static int COUNT = 1024*10;
    private static String CONSUMER_NAME = "DURABLE_TEST";
    protected BrokerService broker;
   
    protected String bindAddress="tcp://localhost:61616";
    
    protected byte[] payload = new byte[1024*32];
    protected ConnectionFactory factory;
    protected Vector<Exception> exceptions = new Vector<Exception>();
    
    private static final String TOPIC_NAME = "failoverTopic";
    private static final String CONNECTION_URL = "failover:(tcp://localhost:61616,tcp://localhost:61617)";
   
    
    private class SimpleTopicSubscriber implements MessageListener, ExceptionListener {

        private TopicConnection topicConnection = null;
        private String clientId;

        public SimpleTopicSubscriber(String connectionURL, String clientId, String topicName) {

            ActiveMQConnectionFactory topicConnectionFactory = null;
            TopicSession topicSession = null;
            Topic topic = null;
            TopicSubscriber topicSubscriber = null;
            

            topicConnectionFactory = new ActiveMQConnectionFactory(connectionURL);
            try {

                topic = new ActiveMQTopic(topicName);
                topicConnection = topicConnectionFactory.createTopicConnection();
                topicConnection.setClientID((clientId));
                topicConnection.start();

                topicSession = topicConnection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
                topicSubscriber = topicSession.createDurableSubscriber(topic, (clientId));
                this.clientId = clientId;
                topicSubscriber.setMessageListener(this);

            } catch (JMSException e) {
            	e.printStackTrace();
            }
        }

        public void onMessage(Message arg0) {
        }

        public void closeConnection() {
            if (topicConnection != null) {
                try {
                    topicConnection.close();
                } catch (JMSException e) {
                }
            }
        }

		public void onException(JMSException exception) {
			exceptions.add(exception);
		}
    }   
    
    private class MessagePublisher implements Runnable {
        private boolean shouldPublish = true;

        public void run() {
            TopicConnectionFactory topicConnectionFactory = null;
            TopicConnection topicConnection = null;
            TopicSession topicSession = null;
            Topic topic = null;
            TopicPublisher topicPublisher = null;
            Message message = null;


            topicConnectionFactory = new ActiveMQConnectionFactory(CONNECTION_URL);
            try {
            	topic = new ActiveMQTopic(TOPIC_NAME);
            	topicConnection = topicConnectionFactory.createTopicConnection();
            	topicSession = topicConnection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
            	topicPublisher = topicSession.createPublisher(topic);
            	message = topicSession.createMessage();
            } catch( Exception ex ) {
            	exceptions.add(ex);
            }
            while (shouldPublish) {
                try {
                    topicPublisher.publish(message, DeliveryMode.PERSISTENT, 1, 2 * 60 * 60 * 1000);
                } catch (JMSException ex) {
                	exceptions.add(ex);
                }
                try {
                    Thread.sleep(1);
                } catch (Exception ex) {
                }
            }
        }
    }
    
    public void testFailover() throws Exception {
        
    	Thread publisherThread = new Thread( new MessagePublisher() );
        publisherThread.start();
        
        for( int i = 0; i < 100; i++ ) {
            
            final int id = i;
            Thread thread = new Thread( new Runnable() {
                public void run() {
                    
                    SimpleTopicSubscriber sub = new SimpleTopicSubscriber(CONNECTION_URL, System.currentTimeMillis()+"-"+id, TOPIC_NAME);
                }
            } );
            thread.start();
            
        }

        Thread.sleep(5000);
        broker.stop();
        broker = createBroker(false);
        Thread.sleep(10000);
        assertEquals(0, exceptions.size());
    }
  
    public void testConcurrentDurableConsumer() throws Exception {
        factory = createConnectionFactory();
        final String topicName = getName();
        final int numMessages = 500;
        int numConsumers = 20;
        final CountDownLatch counsumerStarted = new CountDownLatch(0);
        final AtomicInteger receivedCount = new AtomicInteger();
        Runnable consumer = new Runnable() {
            public void run() {
                final String consumerName = Thread.currentThread().getName();
                int acked = 0;
                int received = 0;
                

                try {
                    while (acked < numMessages/2) {
                        // take one message and close, ack on occasion
                        Connection consumerConnection = factory.createConnection();
                        ((ActiveMQConnection)consumerConnection).setWatchTopicAdvisories(false);
                        consumerConnection.setClientID(consumerName);
                        Session consumerSession = consumerConnection.createSession(false,
                                        Session.CLIENT_ACKNOWLEDGE);
                        Topic topic = consumerSession.createTopic(topicName);
                        consumerConnection.start();
                        
                        MessageConsumer consumer = consumerSession
                                .createDurableSubscriber(topic, consumerName);
                       
                        counsumerStarted.countDown();
                        Message msg = null;
                        do {
                            msg = consumer.receive(5000);
                            if (msg != null) {
                                receivedCount.incrementAndGet();
                                if (received++ % 2 == 0) {
                                    msg.acknowledge();
                                    acked++;
                                }
                            }
                        } while (msg == null);

                        consumerConnection.close();
                    }
                    assertTrue(received >= acked);
                } catch (Exception e) {
                    e.printStackTrace();
                    exceptions.add(e);
                }
            }
        };
        
        ExecutorService executor = Executors.newCachedThreadPool();

        for (int i=0; i<numConsumers ; i++) {
            executor.execute(consumer);
        }

        assertTrue(counsumerStarted.await(30, TimeUnit.SECONDS));
        
        Connection producerConnection = factory.createConnection();
        ((ActiveMQConnection)producerConnection).setWatchTopicAdvisories(false);
        Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = producerSession.createTopic(topicName);
        MessageProducer producer = producerSession.createProducer(topic);
        producerConnection.start();
        for (int i =0; i < numMessages; i++) {
            BytesMessage msg = producerSession.createBytesMessage();
            msg.writeBytes(payload);
            producer.send(msg);
            if (i != 0 && i%100==0) {
                LOG.info("Sent msg " + i);
            }
        }
        
        Thread.sleep(2000);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        assertTrue("got some messages: " + receivedCount.get(), receivedCount.get() > numMessages);
        assertTrue(exceptions.isEmpty());
    }
    
    public void testConsumer() throws Exception{
        factory = createConnectionFactory();
        Connection consumerConnection = factory.createConnection();
        consumerConnection.setClientID(CONSUMER_NAME);
        Session consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic  = consumerSession.createTopic(getClass().getName());
        MessageConsumer consumer = consumerSession.createDurableSubscriber(topic, CONSUMER_NAME);
        consumerConnection.start();
        consumerConnection.close();
        broker.stop();
        broker =createBroker(false);
        
        Connection producerConnection = factory.createConnection();
       
        Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        
        MessageProducer producer = producerSession.createProducer(topic);
        producerConnection.start();
        for (int i =0; i < COUNT;i++) {
            BytesMessage msg = producerSession.createBytesMessage();
            msg.writeBytes(payload);
            producer.send(msg);
            if (i != 0 && i%1000==0) {
                LOG.info("Sent msg " + i);
            }
        }
        producerConnection.close();
        broker.stop();
        broker =createBroker(false);
        
        consumerConnection = factory.createConnection();
        consumerConnection.setClientID(CONSUMER_NAME);
        consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
       
        consumer = consumerSession.createDurableSubscriber(topic, CONSUMER_NAME);
        consumerConnection.start();
        for (int i =0; i < COUNT;i++) {
            Message msg =  consumer.receive(5000);            
            assertNotNull("Missing message: "+i, msg);
            if (i != 0 && i%1000==0) {
                LOG.info("Received msg " + i);
            }
            
        }
        consumerConnection.close();
        
        
    }
    
    protected void setUp() throws Exception {
        if (broker == null) {
            broker = createBroker(true);
        }
       
        super.setUp();
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        
        if (broker != null) {
            broker.stop();
            broker = null;
        }
    }

    protected Topic creatTopic(Session s, String destinationName) throws JMSException {
        return s.createTopic(destinationName);
    }

    /**
     * Factory method to create a new broker
     * 
     * @throws Exception
     */
    protected BrokerService createBroker(boolean deleteStore) throws Exception {
        BrokerService answer = new BrokerService();
        configureBroker(answer,deleteStore);
        answer.start();
        return answer;
    }
    

    protected void configureBroker(BrokerService answer,boolean deleteStore) throws Exception {
        File dataDirFile = new File("target/"+ getName());
        AMQPersistenceAdapterFactory fact = new AMQPersistenceAdapterFactory();
        fact.setDataDirectory(dataDirFile);
        fact.setForceRecoverReferenceStore(true);
    	answer.setPersistenceAdapter(fact.createPersistenceAdapter());
        answer.setDeleteAllMessagesOnStartup(deleteStore);
        answer.addConnector(bindAddress);
        answer.setUseShutdownHook(false);
        answer.setUseJmx(false);
        answer.setAdvisorySupport(false);
    }

    protected ActiveMQConnectionFactory createConnectionFactory() throws Exception {
        return new ActiveMQConnectionFactory(bindAddress);
    }

   
}
