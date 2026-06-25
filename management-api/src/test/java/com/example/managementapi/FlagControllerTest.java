package com.example.managementapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test using H2 (PostgreSQL mode) for schema + JdbcTemplate.
 * Redis is intentionally excluded — control plane doesn't need it for Phase 1.
 *
 * Auth: both JwtAuthFilter and HmacAuthFilter are disabled in test config
 * (ff.auth.jwt-enabled=false, hmac-enabled=false). Tests set the X-Actor
 * request attribute directly via .requestAttr() so audit rows still carry
 * the right actor — this mirrors what JwtAuthFilter does in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration"
})
class FlagControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void createAndRetrieveFlag() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"new_checkout","env":"production","definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("new_checkout"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.state").value("active"));

        mvc.perform(get("/api/v1/flags/new_checkout")
                        .header("X-Env", "production"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag.definition.type").value("boolean"));
    }

    @Test
    void createFlagWithInvalidPctReturns400() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"bad_flag","env":"production",
                                 "definition":{"type":"pct_rollout","pct":150,"salt":"a"},"critical":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createFlagWithUnknownTypeReturns400() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"weird_flag","env":"production",
                                 "definition":{"type":"banana"},"critical":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateFlagRequiresIfMatchHeader() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"lock_test","env":"production",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/flags/lock_test")
                        .header("X-Env", "production")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"type":"targeting","rules":[]}}
                                """))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void updateWithCorrectVersionSucceedsAndBumpsVersion() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"ver_test","env":"production",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/flags/ver_test")
                        .header("X-Env", "production")
                        .header("If-Match", "\"1\"")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"type":"targeting","rules":[]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.definition.type").value("targeting"))
                .andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void updateWithStaleVersionReturns412() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"stale_test","env":"production",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/flags/stale_test")
                        .header("X-Env", "production")
                        .header("If-Match", "\"99\"")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"type":"boolean"}}
                                """))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void softDeleteArchivesTheFlag() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"doomed","env":"production",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/flags/doomed")
                        .header("X-Env", "production")
                        .header("If-Match", "\"1\"")
                        .requestAttr("X-Actor", "alice"))
                .andExpect(status().isNoContent());

        // After archive, update should fail (state != 'active')
        mvc.perform(put("/api/v1/flags/doomed")
                        .header("X-Env", "production")
                        .header("If-Match", "\"2\"")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"type":"boolean"}}
                                """))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void searchReturnsFlagsByEnv() throws Exception {
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"prod_only","env":"production",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/flags")
                        .header("X-Env", "production")
                        .requestAttr("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"stg_only","env":"staging",
                                 "definition":{"type":"boolean"},"critical":false}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/flags")
                        .header("X-Env", "production"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='prod_only')]").exists())
                .andExpect(jsonPath("$[?(@.key=='stg_only')]").doesNotExist());
    }
}
