package dev.affan.agentopsgate.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalService;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import dev.affan.agentopsgate.domain.ApprovalStore;
import dev.affan.agentopsgate.domain.AuditRecord;
import dev.affan.agentopsgate.domain.AuditService;
import dev.affan.agentopsgate.domain.AuditStore;
import dev.affan.agentopsgate.sqs.ApprovalMessageValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class ApprovalControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeApprovalStore approvals = new FakeApprovalStore();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApprovalService service = new ApprovalService(
                approvals,
                new AuditService(new NoOpAuditStore(), objectMapper, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalMessageValidator());
        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getsAnApprovalById() throws Exception {
        Approval approval = pending(1, NOW.minusSeconds(30));
        approvals.add(approval);

        mockMvc.perform(get("/approvals/{id}", approval.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(approval.getId().toString()))
                .andExpect(jsonPath("$.decisionId").value(approval.getDecisionId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-29T11:59:30Z"));
    }

    @Test
    void returnsProblemDetailWhenApprovalDoesNotExist() throws Exception {
        UUID missingId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(get("/approvals/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("approval not found: " + missingId));
    }

    @Test
    void filtersApprovalPagesByStatusAndOrdersNewestFirst() throws Exception {
        Approval olderPending = pending(1, NOW.minusSeconds(30));
        Approval approved = pending(2, NOW.minusSeconds(20));
        approved.approve("reviewer", NOW.minusSeconds(10));
        Approval newerPending = pending(3, NOW.minusSeconds(5));
        approvals.add(olderPending, approved, newerPending);

        mockMvc.perform(get("/approvals")
                        .param("status", "PENDING")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newerPending.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(olderPending.getId().toString()))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].status").value("PENDING"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void followsTheCursorWithoutRepeatingApprovals() throws Exception {
        Approval oldest = pending(1, NOW.minusSeconds(30));
        Approval middle = pending(2, NOW.minusSeconds(10));
        Approval newest = pending(3, NOW.minusSeconds(10));
        approvals.add(oldest, middle, newest);

        MvcResult firstPage = mockMvc.perform(get("/approvals")
                        .param("status", "PENDING")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(middle.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString())
                .get("nextCursor")
                .asString();

        mockMvc.perform(get("/approvals")
                        .param("status", "PENDING")
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldest.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    private static Approval pending(int suffix, Instant createdAt) {
        return Approval.pending(
                uuid(suffix),
                uuid(100 + suffix),
                createdAt,
                NOW.plusSeconds(600));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private static final class FakeApprovalStore implements ApprovalStore {

        private final List<Approval> approvals = new ArrayList<>();

        void add(Approval... values) {
            approvals.addAll(List.of(values));
        }

        @Override
        public Approval storeApproval(Approval approval) {
            approvals.add(approval);
            return approval;
        }

        @Override
        public Optional<Approval> findApprovalById(UUID id) {
            return approvals.stream().filter(approval -> approval.getId().equals(id)).findFirst();
        }

        @Override
        public List<Approval> findApprovals(
                ApprovalStatus status,
                Instant cursorCreatedAt,
                UUID cursorId,
                int limit) {
            return approvals.stream()
                    .filter(approval -> approval.getStatus() == status)
                    .filter(approval -> cursorCreatedAt == null
                            || approval.getCreatedAt().isBefore(cursorCreatedAt)
                            || (approval.getCreatedAt().equals(cursorCreatedAt)
                                    && approval.getId().compareTo(cursorId) < 0))
                    .sorted(Comparator.comparing(Approval::getCreatedAt)
                            .thenComparing(Approval::getId)
                            .reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Approval> findStaleApprovals(ApprovalStatus status, Instant expiresAt) {
            return approvals.stream()
                    .filter(approval -> approval.getStatus() == status)
                    .filter(approval -> !approval.getExpiresAt().isAfter(expiresAt))
                    .sorted(Comparator.comparing(Approval::getExpiresAt).thenComparing(Approval::getId))
                    .toList();
        }
    }

    private static final class NoOpAuditStore implements AuditStore {

        @Override
        public AuditRecord storeAuditRecord(AuditRecord record) {
            return record;
        }

        @Override
        public Optional<AuditRecord> findAuditRecordById(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<AuditRecord> findAuditRecords(Instant from, Instant to) {
            return List.of();
        }
    }
}
