package com.demo.agent.dto;

import lombok.Data;

/**
 * 写操作二次确认回执。
 * <p>
 * 前端收到 SSE 的 {@code tool_confirm} 事件后弹出确认框，
 * 用户点击「确认/取消」时用这个对象回执，唤醒被挂起的推流线程。
 */
@Data
public class ConfirmRequest {

    /** 事件里下发的 confirmId */
    private String confirmId;

    /** 用户是否同意执行 */
    private Boolean approved = Boolean.FALSE;
}
