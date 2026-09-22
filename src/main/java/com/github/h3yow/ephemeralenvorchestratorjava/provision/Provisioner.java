package com.github.h3yow.ephemeralenvorchestratorjava.provision;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;

/**
 * 环境供给器抽象。模拟实现使用有限资源池复现“资源不足”；测试可替换为可控实现。
 */
public interface Provisioner {

    /**
     * 执行一次供给。
     *
     * @param lease 目标租约
     * @param attempt 第几次尝试（从 1 开始）
     */
    ProvisionResult provision(Lease lease, int attempt);

    /**
     * 回收供给出的资源（终止/过期时调用），幂等。
     */
    void release(String resourceSlot);
}
