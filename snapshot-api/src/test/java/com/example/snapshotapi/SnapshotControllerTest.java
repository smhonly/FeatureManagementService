package com.example.snapshotapi;

import com.example.snapshotapi.auth.ApiKeyHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SnapshotControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void seed() {
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    private String hash(String apiKey) {
        return ApiKeyHasher.hmacSha256Hex("test-server-secret-32-bytes-of-pad!", apiKey);
    }

    private void wireApp(String apiKey, int appId) throws Exception {
        ValueOperations<String, String> ops = redis.opsForValue();
        when(ops.get(eq("app:" + hash(apiKey))))
                .thenReturn("{\"app_id\":" + appId + ",\"team\":\"checkout\",\"name\":\"checkout-svc\",\"env_scope\":\"production\"}");
    }

    private void wireSnapshot(int appId, int version, JsonNode data) throws Exception {
        ValueOperations<String, String> ops = redis.opsForValue();
        when(ops.get("snapshot:production:" + appId + ":version")).thenReturn(Integer.toString(version));
        when(ops.get("snapshot:production:" + appId + ":data")).thenReturn(data.toString());
    }

    @Test
    void unknownApiKeyReturns401() throws Exception {
        mvc.perform(get("/api/v1/snapshot").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAuthHeaderReturns401() throws Exception {
        mvc.perform(get("/api/v1/snapshot"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void knownAppWithNoSnapshotReturns404() throws Exception {
        wireApp("good-key", 42);
        mvc.perform(get("/api/v1/snapshot").header("Authorization", "Bearer good-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void happyPathReturns200WithEtagAndBody() throws Exception {
        wireApp("good-key", 7);
        JsonNode body = json.readTree("{\"flags\":[{\"key\":\"new_checkout\",\"value\":true}]}");
        wireSnapshot(7, 3, body);

        mvc.perform(get("/api/v1/snapshot").header("Authorization", "Bearer good-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.flags[0].key").value("new_checkout"))
                .andExpect(jsonPath("$.flags[0].value").value(true));
    }

    @Test
    void matchingEtagReturns304() throws Exception {
        wireApp("good-key", 7);
        JsonNode body = json.readTree("{\"flags\":[]}");
        wireSnapshot(7, 3, body);

        mvc.perform(get("/api/v1/snapshot")
                        .header("Authorization", "Bearer good-key")
                        .header("If-None-Match", "\"3\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"3\""));
    }

    @Test
    void staleEtagReturns200WithNewBody() throws Exception {
        wireApp("good-key", 7);
        JsonNode body = json.readTree("{\"flags\":[]}");
        wireSnapshot(7, 5, body);

        mvc.perform(get("/api/v1/snapshot")
                        .header("Authorization", "Bearer good-key")
                        .header("If-None-Match", "\"3\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""));
    }
}