package com.demo.agent.controller;

import com.demo.agent.common.Result;
import com.demo.agent.dto.LoginRequest;
import com.demo.agent.dto.LoginResponse;
import com.demo.agent.security.AuthInterceptor;
import com.demo.agent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鉴权接口
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        data.put("username", request.getAttribute(AuthInterceptor.ATTR_USERNAME));
        return Result.ok(data);
    }
}
