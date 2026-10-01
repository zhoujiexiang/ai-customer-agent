package com.demo.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.agent.entity.SysUser;
import com.demo.agent.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时初始化默认管理员账号
 * <p>
 * 刻意不把 BCrypt 密文写死在 SQL 种子脚本里，而是启动时动态加密写入，
 * 一是避免密文硬编码，二是换密码时不用重新生成哈希。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.default-username}")
    private String defaultUsername;

    @Value("${app.admin.default-password}")
    private String defaultPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, defaultUsername));

        if (exists != null && exists > 0) {
            log.info("管理员账号 [{}] 已存在，跳过初始化", defaultUsername);
            return;
        }

        SysUser user = new SysUser();
        user.setUsername(defaultUsername);
        user.setPassword(passwordEncoder.encode(defaultPassword));
        user.setNickname("管理员");
        user.setCreatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);

        log.info("默认管理员账号已创建: {} / {} （生产环境请立即修改）", defaultUsername, defaultPassword);
    }
}
