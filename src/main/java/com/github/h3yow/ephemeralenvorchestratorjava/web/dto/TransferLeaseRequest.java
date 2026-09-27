package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 租约转让请求：当前所有者把一条 READY 环境原地转给新的所有者/团队。
 *
 * <p>{@code transferRequestId} 为调用方提供的转让幂等键：同一标识的重复提交（含并发）
 * 只生效一次；同一标识用于另一条环境或另一个目标归属时以明确冲突拒绝。
 */
public class TransferLeaseRequest {

    /** 转让操作的幂等键（调用方生成，区别于申请阶段的 requestId）。 */
    @NotBlank
    private String transferRequestId;

    /** 发起人，必须等于租约当前 owner。 */
    @NotBlank
    private String operator;

    @NotBlank
    private String targetOwner;

    @NotBlank
    private String targetTeam;

    public String getTransferRequestId() { return transferRequestId; }
    public void setTransferRequestId(String transferRequestId) { this.transferRequestId = transferRequestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getTargetOwner() { return targetOwner; }
    public void setTargetOwner(String targetOwner) { this.targetOwner = targetOwner; }
    public String getTargetTeam() { return targetTeam; }
    public void setTargetTeam(String targetTeam) { this.targetTeam = targetTeam; }
}
