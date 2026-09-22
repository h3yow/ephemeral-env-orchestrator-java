package com.github.h3yow.ephemeralenvorchestratorjava.provision;

/**
 * 一次供给尝试的结果。成功携带访问入口与资源槽位；失败时区分“资源池耗尽”与其它失败，
 * 使拒绝原因可与状态非法、配额不足相互区分。
 */
public record ProvisionResult(
        boolean success,
        String endpoint,
        String resourceSlot,
        boolean resourceExhausted,
        String message
) {
    public static ProvisionResult ok(String endpoint, String resourceSlot) {
        return new ProvisionResult(true, endpoint, resourceSlot, false, null);
    }

    public static ProvisionResult exhausted(String message) {
        return new ProvisionResult(false, null, null, true, message);
    }

    public static ProvisionResult failure(String message) {
        return new ProvisionResult(false, null, null, false, message);
    }
}
