package com.demo.agent.confirm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个等待用户确认的写操作。
 * <p>
 * 这是整个项目里并发最微妙的一处：SSE 推流线程会在这里 <b>阻塞等待</b>，
 * 而 HTTP 请求线程（{@code POST /api/chat/confirm}）负责唤醒它。
 * 两边经由这个对象交接，不共享任何其他状态。
 * <p>
 * 三个必须处理的边界：
 * <ol>
 *     <li><b>超时</b>：用户长时间不点，不能把推流线程永久占住，到点自动置为 TIMEOUT 让流程继续</li>
 *     <li><b>幂等</b>：用户可能连点两下「确认」，或用两个标签页各点一次。
 *         用 CAS 保证只有第一个回执生效，后面的直接丢弃</li>
 *     <li><b>迟到回执</b>：已经超时之后用户才点确认，此时 CAS 会失败，
 *         返回 false 让接口层回一个「已过期」，而不是偷偷改状态</li>
 * </ol>
 */
public class PendingConfirm {

    public enum Status {
        /** 等待用户操作 */
        PENDING,
        /** 用户点了确认 */
        CONFIRMED,
        /** 用户点了取消 */
        CANCELLED,
        /** 超时未操作 */
        TIMEOUT
    }

    private final String id;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

    public PendingConfirm(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Status getStatus() {
        return status.get();
    }

    /**
     * 接收用户回执。
     *
     * @return true 表示这次回执生效；false 表示已经超时或已被处理过（重复点击）
     */
    public boolean resolve(boolean approved) {
        Status target = approved ? Status.CONFIRMED : Status.CANCELLED;
        // CAS 是这里唯一的同步手段：抢到的那个线程负责数到 0，其余全部失败
        if (!status.compareAndSet(Status.PENDING, target)) {
            return false;
        }
        latch.countDown();
        return true;
    }

    /**
     * 阻塞等待用户回执。由推流线程调用。
     *
     * @param timeoutSeconds 超时秒数
     * @return 最终状态，可能是 CONFIRMED / CANCELLED / TIMEOUT
     */
    public Status await(long timeoutSeconds) throws InterruptedException {
        boolean finished = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            // 超时也要走 CAS：万一正好和用户点击撞在一起，以先到的为准
            status.compareAndSet(Status.PENDING, Status.TIMEOUT);
        }
        return status.get();
    }
}
