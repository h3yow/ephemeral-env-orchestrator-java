package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 租约转让请求：当前所有者把一条已就绪、未到期的环境原地转给新的所有者/团队。
 *
 * <p>requestId 为本次转让操作的调用方幂等键（与申请的 requestId 相互独立）：
 * 同键重复提交（含并发）只真正转让一次；同键但换了环境或换了目标归属则明确冲突拒绝。</p>
 */
public class TransferRequest {

    /** 本次转让操作的幂等键，调用方生成且全局唯一。 */
    @NotBlank
    private String requestId;

    /** 发起人，必须等于租约当前 owner。 */
    @NotBlank
    private String operator;

    /** 目标所有者；为空表示只换团队（owner 保持不变）。 */
    private String targetOwner;

    /** 目标团队；为空表示只换所有者（team 保持不变）。 */
    private String targetTeam;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getTargetOwner() { return targetOwner; }
    public void setTargetOwner(String targetOwner) { this.targetOwner = targetOwner; }
    public String getTargetTeam() { return targetTeam; }
    public void setTargetTeam(String targetTeam) { this.targetTeam = targetTeam; }
}
