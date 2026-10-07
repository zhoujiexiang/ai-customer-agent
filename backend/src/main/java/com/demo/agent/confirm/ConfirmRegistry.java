package com.demo.agent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 挂起中的确认请求注册表。
 * <p>
 * 为什么需要它：SSE 流在遇到写操作时要「暂停在原地」等用户点击，
 * 而这个点击来自另一个 HTTP 请求。两个请求之间没有任何天然关联，
 * 只能靠一个全局注册表按 confirmId 找到那个正在等待的对象。
 * <p>
 * 用 ConcurrentHashMap 而不是 Redis：本项目单实例部署，一个进程内共享内存就够。
 * 如果要多实例部署，这里必须换成 Redis + 发布订阅，否则用户点确认的请求
 * 可能落到另一台机器上找不到挂起的流——这是当前方案明确的单机边界。
 */
@Component
public class ConfirmRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfirmRegistry.class);

    /** 等待用户确认的超时时间（秒），超时后按「取消」处理 */
    public static final long TIMEOUT_SECONDS = 60L;

    private final Map<String, PendingConfirm> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /** 登记一个新的等待项，返回可用的 confirmId */
    public PendingConfirm register() {
        String id = "cf-" + System.currentTimeMillis() + "-" + sequence.incrementAndGet();
        PendingConfirm item = new PendingConfirm(id);
        pending.put(id, item);
        log.debug("登记写操作确认：{}，当前挂起 {} 个", id, pending.size());
        return item;
    }

    /**
     * 处理用户回执。
     *
     * @return true 表示回执被接受；false 表示找不到或已过期
     */
    public boolean resolve(String confirmId, boolean approved) {
        PendingConfirm item = confirmId == null ? null : pending.get(confirmId);
        if (item == null) {
            log.debug("收到无效或已过期的确认回执：{}", confirmId);
            return false;
        }
        return item.resolve(approved);
    }

    /** 等待结束后清理，避免长期运行下 Map 无限增长 */
    public void remove(String confirmId) {
        pending.remove(confirmId);
    }

    public int pendingCount() {
        return pending.size();
    }
}
