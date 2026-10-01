package com.demo.agent.dto;

/**
 * 登录响应
 */
public record LoginResponse(
        String token,
        String username,
        String nickname
) {
}
