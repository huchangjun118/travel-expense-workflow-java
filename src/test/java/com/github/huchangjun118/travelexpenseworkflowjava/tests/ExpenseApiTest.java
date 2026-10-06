package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetIntentRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ExpenseApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    BudgetIntentRepository intentRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;

    Long budgetId;

    @BeforeEach
    void setUp() throws Exception {
        reportRepository.deleteAll();
        intentRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        MvcResult result = mockMvc.perform(post("/api/admin/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"IT-2026\",\"totalLimit\":10000}"))
                .andExpect(status().isOk())
                .andReturn();
        budgetId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String submitBody(String key, String amount) {
        return "{\"idempotencyKey\":\"" + key + "\",\"department\":\"IT\",\"expenseType\":\"TRAVEL\","
                + "\"amount\":" + amount + ",\"currency\":\"CNY\",\"budgetAccountId\":" + budgetId + "}";
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 提交
        MvcResult submit = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("api-1", "500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.report.status").value("SUBMITTED"))
                .andReturn();
        long id = objectMapper.readTree(submit.getResponse().getContentAsString())
                .get("report").get("id").asLong();

        // 重复提交 -> duplicated=true，同一单据
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("api-1", "500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andExpect(jsonPath("$.report.id").value(id));

        // 越权审批 -> 403 + 可区分错误码
        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "mallory")
                        .header("X-User-Role", "INTERN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        // 非法流转：未审批先撤回他人单据 -> 403
        mockMvc.perform(post("/api/expenses/{id}/withdraw", id)
                        .header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());

        // 正常审批 -> APPROVED
        mockMvc.perform(post("/api/expenses/{id}/approve", id)
                        .header("X-User-Id", "mgr")
                        .header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 已审批后再撤回 -> 409 非法流转
        mockMvc.perform(post("/api/expenses/{id}/withdraw", id)
                        .header("X-User-Id", "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void budgetExceededReturnsDistinctError() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("api-2", "99999")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
    }

    @Test
    void missingFxRateReturnsDistinctError() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"api-3\",\"department\":\"IT\",\"expenseType\":\"TRAVEL\","
                                + "\"amount\":100,\"currency\":\"EUR\",\"budgetAccountId\":" + budgetId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FX_RATE_UNAVAILABLE"));
    }

    @Test
    void withdrawAndResubmitOverHttp() throws Exception {
        MvcResult submit = mockMvc.perform(post("/api/expenses")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("api-4", "500")))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(submit.getResponse().getContentAsString())
                .get("report").get("id").asLong();

        mockMvc.perform(post("/api/expenses/{id}/withdraw", id)
                        .header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        mockMvc.perform(post("/api/expenses/{id}/resubmit", id)
                        .header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        JsonNode report = objectMapper.readTree(mockMvc.perform(get("/api/expenses/{id}", id))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertEquals("SUBMITTED", report.get("status").asText());
        assertEquals(2, report.get("submitCycle").asInt());
    }
}
