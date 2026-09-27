package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 入口端到端集成测试：默认配置真实装配，验证状态码与稳定 errorCode、
 * 幂等、配额与归属三类拒绝原因可区分。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LeaseControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseControllerIntegrationTest.class);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper om;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    private String applyJson(String requestId, String owner, String team) {
        return """
                {"requestId":"%s","owner":"%s","team":"%s","ttl":"PT2M"}
                """.formatted(requestId, owner, team);
    }

    @Test
    @DisplayName("HTTP 全流程：申请 201、查询 200、终止 200；重复提交返回同一 id 且仍 201")
    void httpLifecycleAndIdempotency() throws Exception {
        MvcResult first = mvc().perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("http-1", "alice", "payments")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.owner").value("alice"))
                .andReturn();
        JsonNode body1 = om.readTree(first.getResponse().getContentAsString());
        String id = body1.get("id").asText();
        log.info("HTTP 申请成功 id={} endpoint={}", id, body1.get("endpoint").asText());

        // 重复提交同一 requestId：同一 id，不重复分配
        MvcResult dup = mvc().perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("http-1", "alice", "payments")))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(id, om.readTree(dup.getResponse().getContentAsString()).get("id").asText());
        log.info("HTTP 重复提交复用 id={}", id);

        mvc().perform(get("/api/leases/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        mvc().perform(post("/api/leases/{id}/terminate", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"alice"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // 已终止再终止：409 + 可区分 errorCode
        mvc().perform(post("/api/leases/{id}/terminate", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"alice"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_ALREADY_TERMINATED"));
    }

    @Test
    @DisplayName("HTTP 拒绝原因可区分：校验 400、不存在 404、跨所有者 409/NOT_OWNER")
    void httpErrorCodesDistinguishable() throws Exception {
        // 缺字段 -> 400
        mvc().perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"alice","team":"payments"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(r -> log.info("缺 requestId -> 400: {}", r.getResponse().getContentAsString()));

        // 不存在 -> 404
        mvc().perform(get("/api/leases/missing-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEASE_NOT_FOUND"));

        MvcResult created = mvc().perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("http-owner", "alice", "payments")))
                .andExpect(status().isCreated()).andReturn();
        String id = om.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // 跨所有者 -> 409 NOT_OWNER（区别于状态/配额/资源原因）
        mvc().perform(post("/api/leases/{id}/renew", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"bob"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"))
                .andDo(r -> log.info("跨所有者续租 -> 409: {}", r.getResponse().getContentAsString()));
    }

    @Test
    @DisplayName("HTTP 转让：成功易主、重复提交同一结论、冲突键 409/TRANSFER_REQUEST_CONFLICT、原主人被挡")
    void httpTransferIdempotencyAndConflict() throws Exception {
        MvcResult created = mvc().perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("http-tx-1", "alice", "payments")))
                .andExpect(status().isCreated()).andReturn();
        String id = om.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String transferBody = """
                {"transferRequestId":"tx-http-1","operator":"alice","targetOwner":"bob","targetTeam":"settlements"}
                """;
        mvc().perform(post("/api/leases/{id}/transfer", id)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.owner").value("bob"))
                .andExpect(jsonPath("$.team").value("settlements"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andDo(r -> log.info("HTTP 转让成功: {}", r.getResponse().getContentAsString()));

        // 重复提交：同一结论（仍是 bob，资源不重建）
        mvc().perform(post("/api/leases/{id}/transfer", id)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("bob"));

        // 同一键改用于另一个目标 -> 409 且 errorCode 可区分，消息里带此前用途
        String conflictBody = """
                {"transferRequestId":"tx-http-1","operator":"bob","targetOwner":"carol","targetTeam":"infra"}
                """;
        mvc().perform(post("/api/leases/{id}/transfer", id)
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(id)))
                .andDo(r -> log.info("HTTP 转让冲突 -> 409: {}", r.getResponse().getContentAsString()));

        // 原主人再操作被挡
        mvc().perform(post("/api/leases/{id}/renew", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"alice","extension":"PT5M"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }
}
