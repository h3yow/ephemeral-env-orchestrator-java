package com.github.h3yow.ephemeralenvorchestratorjava.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口一致性集成测试：相同业务结论在 HTTP 入口下有确定的状态码与 reason，
 * 非法状态(409)、跨所有者(403)、配额超限(429)、资源不足/失败等互不混淆。
 */
@SpringBootTest(properties = {
        "orchestrator.defaults.owner-quota=1",
        "orchestrator.defaults.team-quota=10",
        "orchestrator.lease.ttl-seconds=300",
        "orchestrator.provision.max-attempts=2",
        "orchestrator.resources.pool-size=5"
})
@AutoConfigureMockMvc
class LeaseControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applyThenDuplicateThenQuotaThenCrossOwnerEachHaveStableHttpOutcome() throws Exception {
        String body = """
                {"requestId":"web-1","owner":"alice","team":"team-a"}
                """;

        // 首次申请 201 由 POST 语义并非必须；这里返回 200 + duplicate=false 即可解释
        mockMvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.view.status").value("READY"));
        log.info("[HTTP] 输入: POST apply web-1, 判定: 200 READY duplicate=false");

        // 重复提交：同一对象 duplicate=true
        mockMvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        log.info("[HTTP] 输入: 重复 POST web-1, 判定: 200 duplicate=true");

        // 所有者配额超限 -> 429 OWNER_QUOTA_EXCEEDED
        String body2 = """
                {"requestId":"web-2","owner":"alice","team":"team-a"}
                """;
        mockMvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("OWNER_QUOTA_EXCEEDED"));
        log.info("[HTTP] 输入: alice 第2个环境(ownerQuota=1), 判定: 429 OWNER_QUOTA_EXCEEDED");

        // 另一人跨所有者终止 alice 的环境 -> 403 NOT_OWNER
        String envId = mockMvc.perform(get("/api/leases").param("owner", "alice"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"envId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/leases/" + envId + "/terminate").param("operator", "bob"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_OWNER"));
        log.info("[HTTP] 输入: bob 终止 alice 环境, 判定: 403 NOT_OWNER");

        // 参数非法 -> 400 INVALID_REQUEST
        String bad = """
                {"requestId":"web-x","owner":"","team":"team-a"}
                """;
        mockMvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
        log.info("[HTTP] 输入: owner 为空, 判定: 400 INVALID_REQUEST");

        // 不存在的环境 -> 404 ENV_NOT_FOUND
        mockMvc.perform(get("/api/leases/missing-env"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("ENV_NOT_FOUND"));
        log.info("[HTTP] 输入: GET 不存在 envId, 判定: 404 ENV_NOT_FOUND");
    }
}
