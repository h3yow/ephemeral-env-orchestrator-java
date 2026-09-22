package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 配额账户快照（按所有者或团队维度）。
 *
 * @param scope  OWNER 或 TEAM
 * @param name   所有者/团队名
 * @param used   已占用配额（仅统计尚未释放的环境：READY / 供给中 / 可重试失败）
 * @param limit  配额上限
 */
public record QuotaView(String scope, String name, int used, int limit) {

    public boolean isFull() {
        return used >= limit;
    }
}
