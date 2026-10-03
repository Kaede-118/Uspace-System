package com.kaede.uspace.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * QQ 验证的框架配置（模块 1）。
 *
 * <p>只做一件事：把系统时钟做成 Bean。<b>这不是为了好看</b> ——
 * {@link QqVerifyService} 用它取「现在」，而不是直接调 {@code LocalDateTime.now()}，
 * 于是过期、续期、错猜次数这些用例可以<b>拨着钟测</b>。
 * 不注入的话，测 TTL 只剩 {@code Thread.sleep} 一条路：慢、飘，
 * 而且会诱导后面的人干脆不测 TTL。
 */
@Configuration
public class QqVerifyConfig {

    /**
     * 系统时钟。
     *
     * @return 使用系统默认时区的时钟，与项目各处 {@code LocalDateTime.now()} 等价
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
