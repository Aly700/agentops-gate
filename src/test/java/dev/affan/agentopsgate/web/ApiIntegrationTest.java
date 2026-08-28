package dev.affan.agentopsgate.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "agentops.api-key=integration-key",
        "agentops.aws.enabled=false"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requiresAnApiKey() throws Exception {
        mockMvc.perform(get("/audit")
                        .param("from", "2026-08-28T00:00:00Z")
                        .param("to", "2026-08-29T00:00:00Z"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void returnsAProblemForInvalidInput() throws Exception {
        mockMvc.perform(post("/policies")
                        .header("X-API-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void evaluatesFirstMatchingRuleAndCreatesAnApproval() throws Exception {
        String policyId = createPolicy("review-policy");
        addRule(policyId, 10, "fs.*", "REQUIRE_APPROVAL");
        addRule(policyId, 20, "*", "ALLOW");

        String decisionBody = mockMvc.perform(post("/decisions")
                        .header("X-API-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyId":"%s","agentId":"agent-1","toolName":"fs.write",
                                 "arguments":{"path":"/sandbox/report.txt"},"riskTier":"MEDIUM"}
                                """.formatted(policyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effect").value("REQUIRE_APPROVAL"))
                .andExpect(jsonPath("$.matchedRuleId").isNotEmpty())
                .andExpect(jsonPath("$.approvalId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String approvalId = jsonString(decisionBody, "approvalId");
        String decisionId = jsonString(decisionBody, "id");

        mockMvc.perform(post("/approvals/{id}/approve", approvalId)
                        .header("X-API-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\":\"reviewer-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/decisions/{id}", decisionId)
                        .header("X-API-Key", "integration-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("fs.write"));
    }

    @Test
    void queriesAppendOnlyAuditRecordsByTimeRange() throws Exception {
        createPolicy("audited-policy");

        mockMvc.perform(get("/audit")
                        .header("X-API-Key", "integration-key")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2027-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'POLICY_CREATED')]").exists());
    }

    private String createPolicy(String name) throws Exception {
        String body = mockMvc.perform(post("/policies")
                        .header("X-API-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"version\":1}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonString(body, "id");
    }

    private void addRule(String policyId, int precedence, String glob, String effect) throws Exception {
        mockMvc.perform(post("/policies/{id}/rules", policyId)
                        .header("X-API-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolNameGlob":"%s","effect":"%s","precedence":%d}
                                """.formatted(glob, effect, precedence)))
                .andExpect(status().isCreated());
    }

    private static String jsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
