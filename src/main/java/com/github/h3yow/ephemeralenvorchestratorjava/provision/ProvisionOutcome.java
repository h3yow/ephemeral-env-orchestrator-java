package com.github.h3yow.ephemeralenvorchestratorjava.provision;

/**
 * 单次供给尝试的结果。成功携带资源 ID；失败携带可解释的原因码与细节，
 * 调用方必须保留为失败记录，禁止静默吞掉。
 */
public sealed interface ProvisionOutcome permits ProvisionOutcome.Success, ProvisionOutcome.Failure {

    record Success(String resourceId) implements ProvisionOutcome {
    }

    record Failure(String reasonCode, String detail) implements ProvisionOutcome {
    }
}
