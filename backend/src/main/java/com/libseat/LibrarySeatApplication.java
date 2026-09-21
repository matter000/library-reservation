package com.libseat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 图书馆座位预约系统 —— 后端启动类。
 *
 * <p>{@code @EnableScheduling} 开启单机定时任务：
 * 每分钟扫描超时未签到预约（NO_SHOW）、每 5 分钟归档已结束预约（COMPLETED）。
 */
@EnableScheduling
@SpringBootApplication
public class LibrarySeatApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibrarySeatApplication.class, args);
    }
}
