package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QqbotProperties} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring</b> —— 直接 {@code new} 出来断言，
 * 与 {@code PaymentPropertiesTests} 同一个写法。
 *
 * <p>守的是两条<b>配错了不会报错</b>的规则：
 * <ol>
 *   <li><b>两个群列表的并集语义</b> —— 只写进店主群、忘了写白名单，
 *       后果是「加了群却一条都收不到」，而犯这个错的人只会以为机器人坏了</li>
 *   <li><b>金额可见性只认店主群</b> —— 判据若不小心写成「非空即可见」，
 *       顾客群也会收到「张三这单花了 40」，而且照样能跑通演示</li>
 * </ol>
 */
class QqbotPropertiesTests {

    @Test
    @DisplayName("默认值：关闭、无群、令牌为空")
    void defaults_areOnTheSafeSide() {
        QqbotProperties properties = new QqbotProperties();

        assertFalse(properties.isEnabled(), "默认不接入 —— 不打算用它的环境不该凭空多出一个对外端点");
        assertEquals(List.of(), properties.getAllowedGroups());
        assertEquals(List.of(), properties.getAdminGroups());
        assertEquals("", properties.getAccessToken());
        assertEquals("/onebot/v11/ws", properties.getWsPath());
    }

    @Test
    @DisplayName("令牌判定：空、纯空白都算没有")
    void hasUsableToken_rejectsBlank() {
        QqbotProperties properties = new QqbotProperties();

        properties.setAccessToken("");
        assertFalse(properties.hasUsableToken());

        properties.setAccessToken("   ");
        assertFalse(properties.hasUsableToken(),
                "全是空格的令牌与空串一样等于没有鉴权 —— 用 isEmpty 而不是 isBlank 会放过它");

        properties.setAccessToken("uspace-qqbot-test-token-20261001");
        assertTrue(properties.hasUsableToken());
    }

    @Test
    @DisplayName("生效群是白名单与店主群的并集")
    void effectiveGroups_isUnionOfBothLists() {
        QqbotProperties properties = new QqbotProperties();
        properties.setAllowedGroups(List.of(1062204057L));
        properties.setAdminGroups(List.of(2000000001L));

        assertEquals(2, properties.effectiveGroups().size());
        assertTrue(properties.effectiveGroups().contains(1062204057L));
        assertTrue(properties.effectiveGroups().contains(2000000001L));
    }

    @Test
    @DisplayName("只写进店主群的群同样生效 —— 它一样能发指令、一样收播报")
    void isGroupAllowed_includesAdminGroups() {
        QqbotProperties properties = new QqbotProperties();
        properties.setAllowedGroups(List.of(1062204057L));
        properties.setAdminGroups(List.of(2000000001L));

        assertTrue(properties.isGroupAllowed(1062204057L));
        assertTrue(properties.isGroupAllowed(2000000001L),
                "只写进 admin-groups 就一条都收不到，是个没人能自己发现的坑 —— "
                        + "并集让心智模型变成「admin 严格更强」");
        assertFalse(properties.isGroupAllowed(999999L));
        assertFalse(properties.isGroupAllowed(null), "null 群号不该被判为允许");
    }

    @Test
    @DisplayName("金额可见性只认店主群，白名单里的群看不到")
    void isAmountVisible_onlyForAdminGroups() {
        QqbotProperties properties = new QqbotProperties();
        properties.setAllowedGroups(List.of(1062204057L));
        properties.setAdminGroups(List.of(2000000001L));

        assertTrue(properties.isAmountVisible(2000000001L));
        assertFalse(properties.isAmountVisible(1062204057L),
                "顾客群不该看到「张三这单花了 40」—— 群消息对所有人可见");
        assertFalse(properties.isAmountVisible(999999L));
        assertFalse(properties.isAmountVisible(null));
    }

    @Test
    @DisplayName("一个群都没配时，金额对谁都不开放")
    void isAmountVisible_closedByDefault() {
        QqbotProperties properties = new QqbotProperties();
        properties.setAllowedGroups(List.of(1062204057L));

        assertFalse(properties.isAmountVisible(1062204057L),
                "默认值是安全的那一侧：配漏了只会「看不到金额」，而不是「对所有人公开」");
    }

    @Test
    @DisplayName("启动自检：关闭时不打扰，开着且没配群时只提醒不抛")
    void logStartupSummary_neverThrows() {
        QqbotProperties disabled = new QqbotProperties();
        disabled.logStartupSummary();   // 关闭状态：早返回，什么都不做

        QqbotProperties enabled = new QqbotProperties();
        enabled.setEnabled(true);
        enabled.setAccessToken("token-value");
        enabled.logStartupSummary();    // 群白名单与店主群都空：只 WARN，不抛

        assertTrue(enabled.isEnabled(), "提醒归提醒，配置本身仍然可用");
    }
}
