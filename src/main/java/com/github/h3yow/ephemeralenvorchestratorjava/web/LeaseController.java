package com.github.h3yow.ephemeralenvorchestratorjava.web;

import com.github.h3yow.ephemeralenvorchestratorjava.quota.QuotaView;
import com.github.h3yow.ephemeralenvorchestratorjava.service.OrchestratorService;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.OperationRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 临时环境租约 REST 入口。所有并发、幂等、配额、归属与过期语义均在服务层保证。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final OrchestratorService service;

    public LeaseController(OrchestratorService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaseView apply(@Valid @RequestBody ApplyLeaseRequest request) {
        return service.apply(request);
    }

    @GetMapping("/{id}")
    public LeaseView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping(params = "requestId")
    public LeaseView byRequestId(@RequestParam String requestId) {
        return service.getByRequestId(requestId);
    }

    @PostMapping("/{id}/terminate")
    public LeaseView terminate(@PathVariable String id, @Valid @RequestBody OperationRequest request) {
        return service.terminate(id, request.getOperator());
    }

    @PostMapping("/{id}/renew")
    public LeaseView renew(@PathVariable String id, @Valid @RequestBody RenewRequest request) {
        return service.renew(id, request);
    }

    @PostMapping("/{id}/retry")
    public LeaseView retry(@PathVariable String id, @Valid @RequestBody OperationRequest request) {
        return service.retry(id, request.getOperator());
    }

    @GetMapping("/quotas/owners/{owner}")
    public QuotaView ownerQuota(@PathVariable String owner) {
        return service.ownerQuota(owner);
    }

    @GetMapping("/quotas/teams/{team}")
    public QuotaView teamQuota(@PathVariable String team) {
        return service.teamQuota(team);
    }
}
