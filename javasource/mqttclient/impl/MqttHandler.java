package mqtt.impl;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.ISession;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by ako on 1/9/2016.
 */
public class MqttHandler {
    private static Map<String, MqttConnection> mqttHandlers;
    private ILogNode logger;

    public MqttHandler(ILogNode logger) {
        this.logger = logger;
        if (mqttHandlers == null) {
            mqttHandlers = new HashMap();
        }
    }

    public void subscribe(String brokerHost, Long brokerPort, String topicName, String onMessageMicroflow) throws Exception {
        logger.info("MqttHandler.subscribe");
        MqttConnection connection = getMqttConnection(brokerHost, brokerPort);
        connection.subscribe(topicName, onMessageMicroflow);
    }

    public void publish(String brokerHost, Long brokerPort, String topicName, String message) throws Exception {
        logger.info("MqttHandler.publish");
        MqttConnection connection = getMqttConnection(brokerHost, brokerPort);
        connection.publish(topicName, message);
    }

    private MqttConnection getMqttConnection(String brokerHost, Long brokerPort) throws Exception {
        String key = brokerHost + ":" + brokerPort;
        MqttConnection handler;
        synchronized (mqttHandlers) {
            logger.info("NUmber of objects in mqttHandlers map: " + mqttHandlers.size());

            if (!mqttHandlers.containsKey(key)) {
                logger.info("creating new MqttConnection");
                try {
                    handler = new MqttConnection(logger, brokerHost, brokerPort);
                    mqttHandlers.put(key, handler);
                } catch (Exception e) {
                    logger.error(e);
                    throw e;
                }

            } else {
                logger.info("Found existing MqttConnection");
                handler = mqttHandlers.get(key);
            }
            logger.info("NUmber of objects in mqttHandlers map: " + mqttHandlers.size());
        }

        return handler;
    }

    public void unsubscribe(String brokerHost, Long brokerPort, String topicName) throws Exception {
        MqttConnection connection = getMqttConnection(brokerHost, brokerPort);
        connection.unsubscribe(topicName);
    }


    private class MqttConnection {
        private ILogNode logger;
        private MqttClient client;
        private HashMap<String, Subscription> subscriptions = new HashMap<>();

        public MqttConnection(ILogNode logger, String brokerHost, Long brokerPort) throws Exception {
            logger.info("new MqttConnection");
            this.logger = logger;
            boolean useSsl = true;
            String broker = String.format("tcp://%s:%d", brokerHost, brokerPort);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            if (useSsl) {
                broker = String.format("ssl://%s:%d", brokerHost, brokerPort);
                connOpts = new MqttConnectOptions();
                connOpts.setConnectionTimeout(60);
                connOpts.setKeepAliveInterval(60);
                try {
                    String resourcesPath = Core.getConfiguration().getResourcesPath() + File.separator ;
                    connOpts.setSocketFactory(SslUtil2.getSslSocketFactory(
                            resourcesPath + "VeriSign-Class 3-Public-Primary-Certification-Authority-G5.pem",
                            resourcesPath + "abffd122b1-certificate.pem.crt",
                            resourcesPath + "abffd122b1-private.pem.key",
                            "password"
                    ));
                } catch (Exception e) {
                    logger.error(e);
                    throw e;
                }
            }
            String clientId = "JavaSample";
            MemoryPersistence persistence = new MemoryPersistence();

            try {
                this.client = new MqttClient(broker, clientId, persistence);
                logger.info("Connecting to broker: " + broker);
                client.connect(connOpts);
                logger.info("Connected");
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }
        }

        public void finalize() {
            logger.info("finalize MqttConnection");
        }

        public boolean isSubscribed(String topic) {
            return subscriptions.containsKey(topic);

        }

        public void subscribe(String topic, String onMessageMicroflow) throws MqttException {
            logger.info("MqttConnection.subscribe");
            try {
                client.subscribe(topic);

                //final IContext ctx = this.createContext();
                final String microflow = onMessageMicroflow;

                client.setCallback(new MqttCallback() {

                    @Override
                    public void connectionLost(Throwable throwable) {
                        logger.info("connectionLost: " + throwable.getMessage());
                        logger.warn(throwable);
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
                        try {
                            logger.info(String.format("messageArrived: %s, %s", s, new String(mqttMessage.getPayload())));
                            IContext ctx = Core.createSystemContext();

                            ISession session = ctx.getSession();
                            logger.info(String.format("Calling onMessage microflow: %s", microflow));
                            //Core.executeAsync(ctx, microflow, true, ImmutableMap.of("Topic", s, "Payload", new String(mqttMessage.getPayload())));
                            final ImmutableMap map =  ImmutableMap.of("Topic", s, "Payload", new String(mqttMessage.getPayload()));
                            logger.info("Parameter map: " + map);
                            Core.execute(ctx, microflow, true,map);
                        } catch (Exception e) {
                            logger.error(e);
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                        logger.info("deliveryComplete");
                    }
                });

                subscriptions.put(topic, new Subscription(topic, onMessageMicroflow));
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }

        }

        public void publish(String topic, String message) throws MqttException {
            logger.info("MqttConnection.publish");
            try {
                MqttMessage payload = new MqttMessage(message.getBytes());
                int qos = 2;
                payload.setQos(qos);
                client.publish(topic, payload);
                logger.info("Message published");
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }
        }

        public void unsubscribe(String topicName) throws MqttException {
            logger.info(String.format("unsubscribe: %s", topicName));
            try {
                client.unsubscribe(topicName);
            } catch (MqttException e) {
                logger.error(e);
                throw e;
            }
        }
    }
}
