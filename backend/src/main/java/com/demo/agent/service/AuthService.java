package com.demo.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.agent.common.BizException;
import com.demo.agent.dto.LoginRequest;
import com.demo.agent.dto.LoginResponse;
import com.demo.agent.entity.SysUser;
import com.demo.agent.mapper.SysUserMapper;
import com.demo.agent.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录鉴权服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));

        // 用户不存在与密码错误返回同样的提示，避免账号枚举
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("登录失败，用户名: {}", request.username());
            throw new BizException(401, "用户名或密码错误");
        }

        String token = jwtUtil.generate(user.getId(), user.getUsername());
        log.info("登录成功: {}", user.getUsername());
        return new LoginResponse(token, user.getUsername(), user.getNickname());
    }
}
