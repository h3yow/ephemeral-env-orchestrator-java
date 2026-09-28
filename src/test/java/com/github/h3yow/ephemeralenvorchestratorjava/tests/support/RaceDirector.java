package com.github.h3yow.ephemeralenvorchestratorjava.tests.support;

import java.util.List;

/**
 * 确定性竞态导演：按给定顺序逐步放行参与者，每一步在<b>独立线程</b>上执行并在该线程完全结束后
 * 才放行下一步。由于被测服务的每个入口都以“检查 + 写入”整体处于同一把全局锁内，
 * 逐线程串行调用等价于精确指定了并发操作的线性化顺序——A 先于 B 的两种裁决结论
 * 都能被稳定、可复现地分别构造，不再依赖线程调度“碰运气”。
 *
 * <p>与“万箭齐发”式压测互补：压测验证任意顺序下不变量恒成立（无论谁赢），
 * 导演验证“特定一方赢”时结论的每一个细节。</p>
 */
public final class RaceDirector {

    /** 一步操作，允许向调用方抛出任意异常（由调用方在步骤内自行捕获并归类）。 */
    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    private RaceDirector() {
    }

    /**
     * 按列表顺序依次在独立线程上执行每一步并等待其结束，再开始下一步。
     * 任一步抛出非预期的 {@link AssertionError} 时立即透传，使测试失败信息精确指向该步。
     */
    public static void runInOrder(List<Step> steps) throws Exception {
        for (int i = 0; i < steps.size(); i++) {
            final int index = i;
            final Step step = steps.get(index);
            Throwable[] failure = new Throwable[1];
            Thread worker = new Thread(() -> {
                try {
                    step.run();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }, "race-director-step-" + i);
            worker.setDaemon(true);
            worker.start();
            worker.join(30_000);
            if (worker.isAlive()) {
                throw new AssertionError("第 " + index + " 步在 30s 内未结束，疑似死锁");
            }
            if (failure[0] != null) {
                if (failure[0] instanceof Exception e) {
                    throw e;
                }
                if (failure[0] instanceof Error e) {
                    throw e;
                }
            }
        }
    }
}
