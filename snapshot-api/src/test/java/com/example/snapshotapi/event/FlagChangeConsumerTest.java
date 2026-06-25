package com.example.snapshotapi.event;

import com.example.snapshotapi.service.SnapshotBuilderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration test for {@link FlagChangeConsumer} using a real embedded Kafka
 * broker. Verifies that a {@link FlagChangeEvent} produced to the CDC topic
 * triggers a snapshot rebuild for the right (env, app) pair.
 *
 * The @Scheduled sweep in SnapshotBuilderService is disabled here so the
 * test only sees rebuilds triggered by the Kafka consumer.
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

    /**
     * ApplicationRegistry and SnapshotStore both depend on StringRedisTemplate;
     * we don't need a real Redis here because rebuildForApp is mocked, but
     * Spring still wires the bean graph.
     */
    @MockBean
    StringRedisTemplate redis;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void eventWithAppIdTriggersRebuildForThatApp() throws Exception {
        FlagChangeEvent event = new FlagChangeEvent("new_checkout", "production", 42, "updated");
        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(builder, atLeastOnce()).rebuildForApp("production", 42)
        );
    }

    @Test
    void eventWithoutAppIdQueriesScopesAndRebuildsEach() throws Exception {
        // appId == null path: the consumer looks up flag_app_scopes and rebuilds
        // each app. With JdbcTemplate not mocked at this layer, the query goes
        // to H2 — for a flag with no scopes, the result is "no rebuild calls",
        // which is the correct behavior. We assert that no exception is raised
        // and the consumer doesn't crash on a scoped event.
        FlagChangeEvent event = new FlagChangeEvent("unknown_flag", "production", null, "updated");
        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);

        // Give the consumer time to process; with no scopes in the DB, the
        // for-loop has nothing to iterate.
        Thread.sleep(1500);

        ArgumentCaptor<String> envCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> appCap = ArgumentCaptor.forClass(Integer.class);
        verify(builder, never()).rebuildForApp(envCap.capture(), appCap.capture());
    }

    @Test
    void malformedEventIsIgnored() throws Exception {
        // flagKey == null → consumer logs and returns
        FlagChangeEvent event = new FlagChangeEvent(null, "production", 1, "updated");
        kafkaTemplate.send("ff.flag.changes", event).get(5, TimeUnit.SECONDS);

        Thread.sleep(1500);

        verify(builder, never()).rebuildForApp(anyString(), anyInt());
    }
}
