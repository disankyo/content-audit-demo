package com.example.audit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 分布式 ID 生成器（雪花算法）。
 *
 * <p>结构（64 bit）：
 * <pre>
 *   1 bit  符号位（固定 0）
 *   41 bit 时间戳（毫秒，相对起始时间）
 *   10 bit 机器 ID（dataCenterId 5 + workerId 5）
 *   12 bit 序列号（同一毫秒内的自增序号）
 * </pre>
 * 单机每毫秒可生成 4096 个 ID。
 *
 * <p><b>时钟回拨处理（面试必考）</b>：
 * <ul>
 *   <li>回拨幅度小（&lt; 100ms）：自旋等待时钟追上</li>
 *   <li>回拨幅度大：拒绝服务并抛异常告警，避免生成重复 ID</li>
 * </ul>
 * 生产环境应使用美团 Leaf / 百度 UidGenerator 等成熟组件，
 * workerId 由 ZK 或 DB 自动分配，不要手工配置（手工配置容易冲突）。
 */
@Slf4j
@Component
public class IdGenerator {

    /** 起始时间戳：2024-01-01 00:00:00 */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATA_CENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;

    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /** 允许的最大时钟回拨毫秒数，超过则拒绝服务 */
    private static final long MAX_BACKWARD_MS = 100L;

    private final long workerId;
    private final long dataCenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public IdGenerator() {
        // 练手项目：简化为由主机名推导，避免手工配置冲突
        this.workerId = Math.abs(System.getProperty("user.name", "default").hashCode()) % (MAX_WORKER_ID + 1);
        this.dataCenterId = 0L;
        log.info("IdGenerator 初始化, workerId={}, dataCenterId={}", workerId, dataCenterId);
    }

    public static long nextId() {
        return Holder.INSTANCE.next();
    }

    private synchronized long next() {
        long timestamp = System.currentTimeMillis();

        // ---- 时钟回拨处理 ----
        if (timestamp < lastTimestamp) {
            long backward = lastTimestamp - timestamp;
            if (backward <= MAX_BACKWARD_MS) {
                // 小幅回拨：自旋等待时钟追上
                try {
                    Thread.sleep(backward);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待时钟时被中断", e);
                }
                timestamp = System.currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException("时钟回拨未恢复，拒绝生成 ID");
                }
            } else {
                log.error("检测到严重时钟回拨，backwardMs={}，拒绝生成 ID", backward);
                throw new IllegalStateException("Clock moved backwards, refusing to generate id: " + backward + "ms");
            }
        }

        // ---- 同一毫秒内：序列号自增 ----
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 当前毫秒的序列号用完，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }

    private static class Holder {
        private static final IdGenerator INSTANCE = new IdGenerator();
    }
}
