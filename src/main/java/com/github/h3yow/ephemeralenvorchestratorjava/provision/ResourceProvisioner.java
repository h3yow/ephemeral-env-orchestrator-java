package com.github.h3yow.ephemeralenvorchestratorjava.provision;

/**
 * 资源供给器抽象。{@code attempt} 从 1 开始，与失败记录的序号一致，
 * 便于测试按次数注入失败（如前两次失败、第三次成功）。
 */
public interface ResourceProvisioner {

    ProvisionOutcome provision(String envId, int attempt);

    /** 释放已分配资源（终止/回收时幂等调用）。 */
    void release(String resourceId);
}
