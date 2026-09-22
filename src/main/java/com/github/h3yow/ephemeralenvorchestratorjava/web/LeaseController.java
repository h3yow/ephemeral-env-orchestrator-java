package com.github.h3yow.ephemeralenvorchestratorjava.web;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.QuotaView;
import com.github.h3yow.ephemeralenvorchestratorjava.service.Commands;
import com.github.h3yow.ephemeralenvorchestratorjava.service.EnvironmentOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 租约编排 REST 入口。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final EnvironmentOrchestrator orchestrator;

    public LeaseController(EnvironmentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 申请环境（requestId 幂等）。 */
    @PostMapping
    public Commands.ApplyResult apply(@RequestBody ApplyRequest request) {
        return orchestrator.apply(new Commands.ApplyCommand(request.requestId(), request.owner(), request.team()));
    }

    /** 续租。 */
    @PostMapping("/{envId}/renew")
    public LeaseView renew(@PathVariable String envId, @RequestParam String operator) {
        return orchestrator.renew(new Commands.RenewCommand(envId, operator));
    }

    /** 主动终止。 */
    @PostMapping("/{envId}/terminate")
    public LeaseView terminate(@PathVariable String envId, @RequestParam String operator) {
        return orchestrator.terminate(new Commands.TerminateCommand(envId, operator));
    }

    /** 供给失败后重试。 */
    @PostMapping("/{envId}/retry")
    public LeaseView retry(@PathVariable String envId, @RequestParam String operator) {
        return orchestrator.retry(new Commands.RetryCommand(envId, operator));
    }

    /** 查询单个环境。 */
    @GetMapping("/{envId}")
    public LeaseView get(@PathVariable String envId) {
        return orchestrator.get(envId);
    }

    /** 列表（可按 owner / team 过滤）。 */
    @GetMapping
    public List<LeaseView> list(@RequestParam(required = false) String owner,
                                @RequestParam(required = false) String team) {
        return orchestrator.list(owner, team);
    }

    /** 手动触发到期回收扫描。 */
    @PostMapping("/sweep")
    public Map<String, Integer> sweep() {
        return Map.of("reclaimed", orchestrator.sweepExpired());
    }

    /** 查询所有者配额。 */
    @GetMapping("/quotas/owners/{owner}")
    public QuotaView ownerQuota(@PathVariable String owner) {
        return orchestrator.ownerQuota(owner);
    }

    /** 查询团队配额。 */
    @GetMapping("/quotas/teams/{team}")
    public QuotaView teamQuota(@PathVariable String team) {
        return orchestrator.teamQuota(team);
    }

    /** 申请请求体。 */
    public record ApplyRequest(String requestId, String owner, String team) {
    }
}
