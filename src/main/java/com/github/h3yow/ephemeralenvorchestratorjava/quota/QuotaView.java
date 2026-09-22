package com.github.h3yow.ephemeralenvorchestratorjava.quota;

/**
 * 配额使用快照（只读），用于接口返回与拒绝原因的解释。
 *
 * @param used  当前占用数（PENDING/PROVISIONING/READY/可重试 FAILED 均占用）
 * @param limit 配额上限，-1 表示不限
 */
public record QuotaView(long used, long limit) {
    public boolean isUnlimited() {
        return limit < 0;
    }
}
