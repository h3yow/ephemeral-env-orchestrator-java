package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 临时环境生命周期状态。
 *
 * <pre>
 *                  +------------- 供给成功 -------------+
 *                  v                                    |
 *   PENDING --> PROVISIONING --> READY --续租--> READY  |
 *                  |                  |                 |
 *               供给失败            到期回收            主动终止
 *                  v                  v                 v
 *               FAILED ---------> EXPIRED / TERMINATED (终态)
 * </pre>
 *
 * <p>FAILED 仅在重试上限以内且尚未达到上限时为非终态；达到上限后视为终态。</p>
 */
public enum EnvironmentStatus {
    /** 已受理、尚未开始供给。 */
    PENDING(false),
    /** 供给进行中（同步供给路径下仅瞬时可见）。 */
    PROVISIONING(false),
    /** 供给成功，租约生效。 */
    READY(false),
    /** 供给失败，未超过重试上限时可重试，超过上限后为终态。 */
    FAILED(false),
    /** 租约到期被系统回收（终态）。 */
    EXPIRED(true),
    /** 被所有者主动终止（终态）。 */
    TERMINATED(true);

    private final boolean terminal;

    EnvironmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminalState() {
        return terminal;
    }
}
