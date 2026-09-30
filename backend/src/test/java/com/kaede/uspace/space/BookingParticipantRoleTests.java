package com.kaede.uspace.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookingParticipantRole} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>本枚举的每个静态判断都<b>会把数据库里的 {@code VARCHAR} 值原样收进来</b> ——
 * 手工改库、旧数据、将来新增取值而代码没跟上，都会走到「认不出」那条路径。
 * 所以契约必须是「认不出就返回 false / null」，而不是抛异常：
 * 让整个参与者名单因为一个认不出的角色而 500，代价远大于少显示一个标签。
 *
 * <p>这几条与 {@code BookingStatusTests} / {@code OrderStatusTests} /
 * {@code PaymentChannelTests} 是同一套 —— 四个枚举的判空写法同源，
 * 改动时一起看。
 */
class BookingParticipantRoleTests {

    @Test
    @DisplayName("判空安全：null 与认不出的取值都返回 false / null，而不是抛 NPE")
    void nullSafe() {
        assertFalse(BookingParticipantRole.isValid(null),
                "角色名来自库里的 VARCHAR 列，为 null 完全可能 —— 不该让校验直接崩掉");
        assertFalse(BookingParticipantRole.isValid("NOT_A_ROLE"), "认不出的取值不算合法");
        assertFalse(BookingParticipantRole.isValid(""), "空串不算合法");

        assertFalse(BookingParticipantRole.isHost(null),
                "认不出就返回 false，这是本方法的契约 —— 不是抛异常");
        assertFalse(BookingParticipantRole.isHost("PARTICIPANT"), "被邀请者不是包场人");
        assertFalse(BookingParticipantRole.isHost("not-a-role"), "认不出的取值不是包场人");

        assertNull(BookingParticipantRole.textOf(null), "翻译不出就返回 null，让调用方留空");
        assertNull(BookingParticipantRole.textOf("NOT_A_ROLE"), "认不出的取值翻译为 null");
        assertNull(BookingParticipantRole.textOf(""), "空串翻译为 null");
    }

    @Test
    @DisplayName("合法取值：两个角色都认得出来，isHost 只认 HOST")
    void validValues() {
        assertTrue(BookingParticipantRole.isValid("HOST"), "HOST 是合法角色");
        assertTrue(BookingParticipantRole.isValid("PARTICIPANT"), "PARTICIPANT 是合法角色");

        assertTrue(BookingParticipantRole.isHost("HOST"), "HOST 就是包场人");
        assertFalse(BookingParticipantRole.isHost("PARTICIPANT"),
                "被邀请者不该被当成包场人 —— 名单排序与「我创建的」列表都靠这个区分");
    }

    @Test
    @DisplayName("文案：两个角色的中文文案互不相同，且都不是空")
    void textsAreDistinct() {
        String host = BookingParticipantRole.textOf("HOST");
        String participant = BookingParticipantRole.textOf("PARTICIPANT");

        assertNotEquals(host, participant,
                "两个角色若显示成同一句话，邀请页上就分不出谁是发起人了");
        assertFalse(host.isBlank() || participant.isBlank(),
                "文案直接给前端展示，不该是空的");
    }

    @Test
    @DisplayName("每个枚举常量都能翻译出文案")
    void everyConstantHasText() {
        for (BookingParticipantRole role : BookingParticipantRole.values()) {
            assertEquals(role.getText(), BookingParticipantRole.textOf(role.name()),
                    "新增角色时忘了配文案，这里会红 —— 而不是等到前端收到一个 null");
        }
    }
}
