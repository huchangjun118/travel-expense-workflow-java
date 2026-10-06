package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 端到端 API 测试：独立启动应用上下文，走真实 HTTP 层。 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BudgetAccountStore accountStore;

    @BeforeEach
    void seedBudget() {
        accountStore.save(new BudgetAccount("acc-eng", "ENG", new BigDecimal("100000")));
    }

    private String submitBody(String amount) {
        return "{\"amount\":" + amount
                + ",\"currency\":\"CNY\",\"expenseType\":\"TRAVEL\""
                + ",\"department\":\"ENG\",\"description\":\"出差\"}";
    }

    @Test
    void submitIsIdempotentOverHttp() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .header("X-Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        String reportId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.reportId");

        // 客户端重试：同一幂等键返回同一单据，可识别为重复
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .header("X-Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.reportId").value(reportId));

        // 预算只占用一次
        mockMvc.perform(get("/api/budgets/acc-eng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(100));
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .header("X-Idempotency-Key", "http-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("20000"))) // > 10000 -> MANAGER+FINANCE+CFO
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.reportId");

        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "m1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "f1").header("X-User-Role", "FINANCE"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "c1").header("X-User-Role", "CFO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 非法流转：已通过后不能再撤回
        mockMvc.perform(post("/api/expenses/{id}/withdraw", id)
                        .header("X-User-Id", "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void unauthorizedApproveAndAuditAccessAreRejected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .header("X-Idempotency-Key", "http-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("100")))
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.reportId");

        // 越权审批 -> 403 + 可区分错误码
        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "mallory").header("X-User-Role", "INTERN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        // 非 ADMIN 查审计 -> 403
        mockMvc.perform(get("/api/audit")
                        .header("X-User-Id", "mallory").header("X-User-Role", "INTERN"))
                .andExpect(status().isForbidden());

        // ADMIN 可查，且包含 DENIED 审计记录
        MvcResult audit = mockMvc.perform(get("/api/audit")
                        .header("X-User-Id", "root").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(audit.getResponse().getContentAsString().contains("DENIED"));
    }

    @Test
    void missingRateIsRejectedWithDistinctCode() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .header("X-Idempotency-Key", "http-key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"currency\":\"GBP\",\"expenseType\":\"TRAVEL\""
                                + ",\"department\":\"ENG\",\"description\":\"出差\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_MISSING"));
    }
}
