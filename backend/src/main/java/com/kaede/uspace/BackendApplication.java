package com.kaede.uspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>{@code @EnableScheduling} 供模块 8 的包场清场调度使用
 * （见 {@code com.kaede.uspace.order.BookingClearScheduler}）——
 * 包场开始时要把仍在店内的非参与者订单结算掉。
 * 调度本身可以用 {@code uspace.order.clear-scheduler.enabled=false} 关闭，
 * 关掉时 {@code @Scheduled} 方法不会被注册，开在这儿是无害的。
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
