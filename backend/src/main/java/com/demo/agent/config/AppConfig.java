package com.demo.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 通用 Bean 配置
 */
@Configuration
public class AppConfig {

    /**
     * 密码加密器
     * <p>
     * 只引入 spring-security-crypto 而非完整的 Spring Security Starter，
     * 因为本项目只需要 BCrypt 加密能力，鉴权走轻量 JWT 拦截器。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
