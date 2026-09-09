package com.example.audit.mq;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后再执行的小工具。
 *
 * <p><b>这是「本地事务 + 发消息」最经典的一致性坑</b>：
 * 如果在事务里直接把消息发出去，消费端可能立刻消费、回查动态，
 * 但此时本地事务还没提交，消费端查不到数据，任务就废了。
 *
 * <p>处理办法：事务内先攒着，等 commit 之后再发。
 * 生产上要做到「消息不丢」，这一步还应该配
 * <b>事务消息</b>（半消息 + 本地事务回查）或 <b>本地消息表</b>，
 * 这里用 afterCommit 回调，依赖更少、逻辑更直白。
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 没有事务（比如被自调用绕过代理、或定时任务里单独执行）：直接发
            task.run();
        }
    }
}
