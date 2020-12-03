package com.solace.troubleflipper.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.troubleflipper.Publisher;
import com.solace.troubleflipper.Subscriber;
import com.solace.troubleflipper.properties.SolaceCloudProperties;
import com.solace.troubleflipper.properties.TournamentProperties;
import com.solacesystems.jcsmp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Timer;

@Configuration
public class JCSMPConfiguration {

    private Logger log = LoggerFactory.getLogger("game");

    @Bean
    public Timer getTournamentTimer() {
        return new Timer("TournamentTimer");
    }

    @Bean
    public ObjectMapper getObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public JCSMPSession getJCSMPConnector(SolaceCloudProperties solaceCloudProperties) throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, false);
        props.setProperty(JCSMPProperties.VPN_NAME, solaceCloudProperties.getVpn());
        props.setProperty(JCSMPProperties.USERNAME, solaceCloudProperties.getUsername());
        props.setProperty(JCSMPProperties.PASSWORD, solaceCloudProperties.getPassword());
        props.setProperty(JCSMPProperties.HOST, solaceCloudProperties.getUrl());
        props.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        props.setProperty(JCSMPProperties.CLIENT_NAME, "trouble-flipper");
        props.setProperty(JCSMPProperties.APPLICATION_DESCRIPTION, "The Java application running the trouble flipper server");

        // reconnect behaviour
        JCSMPChannelProperties cp = (JCSMPChannelProperties) props
                .getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        cp.setReconnectRetries(12000); // 1 hr for 0.5 sec timeout
        cp.setConnectRetries(1000);
        cp.setReconnectRetryWaitInMillis(500);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props);

        // connect (connect is blocking in JCSMP)
        session.connect();

        // needed for dispatch

        TournamentProperties tournamentProperties = new TournamentProperties();

        final Topic topic = JCSMPFactory.onlyInstance().createTopic("users");
        session.addSubscription(topic);

        final Topic tournamentsTopic = JCSMPFactory.onlyInstance().createTopic("tournaments");
        session.addSubscription(tournamentsTopic);

        final Topic gamesTopic = JCSMPFactory.onlyInstance().createTopic("games/>");
        session.addSubscription(gamesTopic);

        return session;
    }

    @Bean
    public Subscriber getSubscriber(JCSMPSession session, ObjectMapper objectMapper) throws JCSMPException {
        return new Subscriber(session, objectMapper);
    }

    @Bean
    public Publisher getPublisher(JCSMPSession session, ObjectMapper objectMapper) throws JCSMPException {
        return new Publisher(session, objectMapper);
    }

    private String getMessageStr(BytesXMLMessage message) throws IOException {
        if (message == null) {
            return null;
        }
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getText();
        }
        if (message.hasAttachment()) {
            byte[] buf = new byte[message.getAttachmentContentLength()];
            message.readAttachmentBytes(buf);
            String msgStr = new String(buf);
            return msgStr;
        }
        return null;
    }

}
