package com.example.order.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import org.apache.kafka.clients.admin.NewTopic;

@Configuration
public class KafkaTopicConfig {

    /**
     * KafkaAdmin creates the topic on startup if it does not exist. Three
     * partitions allow the instrument-service consumer group to scale.
     */
    @Bean
    public NewTopic orderEventsTopic(@Value("${app.kafka.order-events-topic:order-events}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
