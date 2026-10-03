package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * QQ 机器人的<b>装配测试</b>。
 *
 * <p>它守的是一个在默认配置下测不到的盲区：{@code uspace.qqbot.enabled} 默认是
 * <b>false</b>，所以 {@code QqbotConfig} 与底下那一串 {@code @ConditionalOnProperty}
 * 的服务<b>平时根本不装配</b> —— 其余的集成测试都跑在「关掉」的状态下，
 * 于是「打开之后能不能起来」这件事没有任何测试覆盖。
 *
 * <p>而它失败的典型方式是<b>启动即崩</b>：少一个 bean、某个依赖在容器里不存在、
 * 条件注解写错……这些都不会被单元测试发现，只会在演示前把 enabled 改成 true 时炸出来。
 * 本类就是给那一刻兜底的 —— 它用真实的 Spring 上下文跑一遍完整装配。
 *
 * <p>⚠️ <b>放在这里而不是别处</b>：qqbot 的装配依赖好几个别的包的 bean
 * （{@code Clock} 来自 {@code QqVerifyConfig}、各 Service 来自它们各自的包），
 * 只有整上下文加载才测得出「这些拼在一起行不行」。
 */
@SpringBootTest(
        // ⚠️ 必须起真实的 Servlet 容器：QqbotConfig 那个 createWebSocketContainer
        // 要往 ServletContext 里取 ServerContainer，而默认的 MOCK 环境里没有它 ——
        // 表现为启动就崩在这一句上。这不是实现的问题，是「测装配就得有真容器」
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uspace.qqbot.enabled=true",
                "uspace.qqbot.access-token=test-token-only-for-wiring",
                "uspace.qqbot.allowed-groups=10001"
        })
class QqbotWiringTests {

    @Autowired
    private QqCommandService commandService;

    @Autowired
    private QqWriteCommandService writeCommandService;

    @Autowired
    private QqBroadcastListener broadcastListener;

    @Autowired
    private OneBotClient oneBotClient;

    @Autowired
    private MessageDedup messageDedup;

    @Autowired
    private QqbotProperties qqbotProperties;

    @Test
    @DisplayName("启用 qqbot 时，全部 bean 都能装配起来（含依赖 Clock 的写指令服务）")
    void contextLoadsWithQqbotEnabled() {
        assertNotNull(commandService, "指令分发服务要能装配 —— 它现在依赖十余个别的包的 Service");
        assertNotNull(writeCommandService,
                "写指令服务要能装配 —— 它依赖 Clock（来自 QqVerifyConfig），"
                        + "少了那个 bean 的话应用会【启动失败】，而默认配置下根本测不到");
        assertNotNull(broadcastListener);
        assertNotNull(oneBotClient);
        assertNotNull(messageDedup);
        assertNotNull(qqbotProperties);
    }

    @Test
    @DisplayName("启用 qqbot 时，写指令与播报的开关状态如实反映配置")
    void propertiesReflectConfiguration() {
        // 只断言「读得到」，不断言具体取值 —— 取值由 application.properties 决定，
        // 而本类的 properties 只覆盖了 enabled / token / allowed-groups 三项
        assertNotNull(qqbotProperties.getWriteCommandCooldown(), "冷却时长要能绑定 Duration");
        assertNotNull(qqbotProperties.getBroadcast());
    }
}
