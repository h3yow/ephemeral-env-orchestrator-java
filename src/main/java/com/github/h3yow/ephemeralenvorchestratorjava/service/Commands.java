package com.github.h3yow.ephemeralenvorchestratorjava.service;

/**
 * 各入口的命令对象（仅含类型定义）。
 */
public final class Commands {

    private Commands() {
    }

    /**
     * 申请环境。requestId 为幂等键：重复提交同一 requestId 必须返回同一环境，不得重复分配。
     */
    public record ApplyCommand(String requestId, String owner, String team) {
    }

    /** 续租。operator 必须为环境所有者。 */
    public record RenewCommand(String envId, String operator) {
    }

    /** 主动终止。operator 必须为环境所有者。 */
    public record TerminateCommand(String envId, String operator) {
    }

    /** 失败后重试供给。operator 必须为环境所有者。 */
    public record RetryCommand(String envId, String operator) {
    }

    /**
     * 申请结果。duplicate=true 表示命中幂等返回，未发生任何资源/配额变动。
     */
    public record ApplyResult(String envId, boolean duplicate, com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseView view) {
    }
}
