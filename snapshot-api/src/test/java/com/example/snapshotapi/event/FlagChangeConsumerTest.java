package com.example.snapshotapi.event;

import com.example.snapshotapi.service.SnapshotBuilderService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration test for {@link FlagChangeConsumer} with embedded Kafka.
 *
 * <p>Design B — every event carries definition + scopedAppIds.
 * The Consumer calls {@code mergeFlagForApp} for each scoped app.</p>
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = "ff.flag.changes",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.example.snapshotapi.event",
        "spring.kafka.consumer.properties.spring.json.use.type.headers=false",
        "spring.kafka.consumer.properties.spring.json.value.default.type=com.example.snapshotapi.event.FlagChangeEvent",
        "spring.kafka.consumer.group-id=snapshot-builder-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "ff.kafka.topic-flag-changes=ff.flag.changes",
        "spring.task.scheduling.pool.size=1"
})
@DirtiesContext
class FlagChangeConsumerTest {

    @MockBean
    SnapshotBuilderService builder;

    @MockBean
    StringRedisTemplate redis;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void eventWithDefinitionAndScopedAppsTriggersMergeForEachApp() throws Exception {
        var event = new FlagChangeEvent("checkout_v2", "production", "updated",
                JsonNodeFactory.instance.objectNode().put("type", "boolean"),
                List.of(42, 7));

        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(builder, atLeastOnce()).mergeFlagForApp(
                    eq("production"), eq(42), eq("checkout_v2"), any(), eq("updated"));
            verify(builder, atLeastOnce()).mergeFlagForApp(
                    eq("production"), eq(7), eq("checkout_v2"), any(), eq("updated"));
        });
    }

    @Test
    void eventWithArchivedOpCallsMergeFlagForApp() throws Exception {
        // Archive: definition=null (Kafka will deserialise as NullNode, but that's
        // normalised inside mergeFlagForApp — the important thing is it's called)
        var event = new FlagChangeEvent("old_flag", "production", "archived",
                null, List.of(99));

        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(builder, atLeastOnce()).mergeFlagForApp(
                        eq("production"), eq(99), eq("old_flag"), any(), eq("archived"))
        );
    }

    @Test
    void malformedEventWithNullFlagKeyIsIgnored() throws Exception {
        var event = new FlagChangeEvent(null, "production", "updated",
                JsonNodeFactory.instance.objectNode().put("type", "boolean"),
                List.of(1));

        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);
        Thread.sleep(1500);

        verify(builder, never()).mergeFlagForApp(anyString(), anyInt(),
                anyString(), any(), anyString());
    }
}
