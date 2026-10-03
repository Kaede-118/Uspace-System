package com.kaede.uspace.user;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.user.dto.QqVerifyStatusVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QqVerifyService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b> —— 这个服务的状态全在内存里，天生适合这样测。
 *
 * <p>最要紧的一条是 {@link #confirm_byAnotherQq_doesNotVerifyTheOriginal}：
 * B 拿着 A 的验证码去确认，绝不能把 A 标记成已验证。那是整套设计的基石
 * （按发送者 QQ 查表），而它写错了<b>一样能跑通全部演示</b> —— 只有这条用例挡得住。
 */
class QqVerifyServiceTests {

    /** 基准时刻，拨钟从这里开始 */
    private static final Instant BASE = Instant.parse("2026-09-30T04:00:00Z");

    private static final String QQ_A = "10001";
    private static final String QQ_B = "20002";

    private QqVerifyProperties properties;

    private MutableClock clock;

    private QqVerifyService service;

    @BeforeEach
    void setUp() {
        properties = new QqVerifyProperties();
        clock = new MutableClock(BASE);
        service = new QqVerifyService(properties, clock);
    }

    // ==================================================================
    // 签发与查询
    // ==================================================================

    @Test
    @DisplayName("签发：拿到验证码与凭证，此时还没确认")
    void issue_thenStatusIsPending() {
        QqVerifyIssueVo issued = issue(QQ_A);

        assertEquals(6, issued.getCode().length(), "验证码是 6 位");
        assertTrue(issued.getCode().matches("\\d{6}"), "含前导零，所以是 6 个数字字符");
        assertEquals(43, issued.getChallengeId().length(),
                "32 字节随机数的 URL-safe Base64（去填充）恰好 43 个字符");
        assertTrue(issued.getTtlSeconds() > 0, "应当给出剩余秒数给前端做倒计时");

        assertFalse(verified(QQ_A, issued.getChallengeId()), "刚签发时还没确认");
    }

    @Test
    @DisplayName("签发：两次得到的验证码与凭证都不同")
    void issue_isNotRepeatable() {
        QqVerifyIssueVo first = issue(QQ_A);
        QqVerifyIssueVo second = issue(QQ_A);

        assertNotEquals(first.getChallengeId(), second.getChallengeId(), "凭证必须随机");
        // 验证码理论上可能撞（100 万空间），所以不硬断言不同；只断言凭证不同
    }

    @Test
    @DisplayName("签发：QQ 格式不对直接拒绝")
    void issue_rejectsInvalidQq() {
        assertEquals(ErrorCode.PARAM_INVALID, service.issue(null).getError());
        assertEquals(ErrorCode.PARAM_INVALID, service.issue("").getError());
        assertEquals(ErrorCode.PARAM_INVALID, service.issue("abc").getError());
        assertEquals(ErrorCode.PARAM_INVALID, service.issue("0123").getError(), "首位不能是 0");
        assertEquals(ErrorCode.PARAM_INVALID, service.issue("1234").getError(), "至少 5 位");
    }

    @Test
    @DisplayName("签发：同一个 QQ 再取一次，旧凭证立即作废（覆盖语义）")
    void issue_overwritesThePreviousChallenge() {
        QqVerifyIssueVo first = issue(QQ_A);
        QqVerifyIssueVo second = issue(QQ_A);

        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND, service.status(QQ_A, first.getChallengeId()).getError(),
                "旧凭证作废 —— 这条是防「攻击者攥着 challengeId 等受害者确认」的关键");
        assertTrue(service.status(QQ_A, second.getChallengeId()).isSuccess(), "新凭证有效");
    }

    @Test
    @DisplayName("查询：凭证对不上或不存在时返回 404，而不是「未确认」")
    void status_returns404ForUnknownChallenge() {
        QqVerifyIssueVo issued = issue(QQ_A);

        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND,
                service.status(QQ_A, "not-a-real-challenge").getError());
        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND, service.status(QQ_B, issued.getChallengeId()).getError(),
                "拿 A 的凭证去查 B 的 QQ，也查不到");
        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND, service.status(QQ_A, null).getError());
    }

    // ==================================================================
    // 群内确认
    // ==================================================================

    @Test
    @DisplayName("确认：群里报上来正确的验证码，状态变成已验证")
    void confirm_thenStatusIsVerified() {
        QqVerifyIssueVo issued = issue(QQ_A);

        QqVerifyService.ConfirmResult result = service.confirm(QQ_A, issued.getCode());

        assertTrue(result.verified(), "确认应当通过");
        assertTrue(result.reply().contains("成功"), "要有一句给群里看的话：" + result.reply());
        assertTrue(verified(QQ_A, issued.getChallengeId()), "此后状态是已验证");
    }

    @Test
    @DisplayName("确认：★ B 拿着 A 的验证码去发，A 不会被标记为已验证")
    void confirm_byAnotherQq_doesNotVerifyTheOriginal() {
        QqVerifyIssueVo issued = issue(QQ_A);

        // B 在群里看到了 A 的验证码，照着发了一遍 —— 但群里那条消息的发送者是 B
        QqVerifyService.ConfirmResult result = service.confirm(QQ_B, issued.getCode());

        assertFalse(result.verified(), "B 发 A 的码不该通过");
        assertFalse(verified(QQ_A, issued.getChallengeId()),
                "★ 这是整套设计的基石：按发送者 QQ 查表，所以别人拿到码也没用。"
                        + "若改成遍历所有记录找匹配的验证码，这条就会红");
    }

    @Test
    @DisplayName("确认：群里的无关消息一律静默忽略（机器人不刷屏）")
    void confirm_ignoresUnknownSender() {
        QqVerifyService.ConfirmResult result = service.confirm(QQ_B, "123456");

        assertFalse(result.verified());
        assertEquals("", result.reply(), "没有待验证请求时不要回复 —— 否则群里每条消息都会被机器人回一句");
    }

    @Test
    @DisplayName("确认：验证码错误时回复提示，但不超过上限前不作废")
    void confirm_wrongCode_reportsButKeepsChallenge() {
        QqVerifyIssueVo issued = issue(QQ_A);

        QqVerifyService.ConfirmResult result = service.confirm(QQ_A, "000000");

        assertFalse(result.verified());
        assertNotEquals("", result.reply(), "自己发的码打错了，该告诉他一声");
        assertTrue(service.status(QQ_A, issued.getChallengeId()).isSuccess(), "还没到上限，条目仍在");
        assertFalse(verified(QQ_A, issued.getChallengeId()), "但状态仍是未确认");
    }

    @Test
    @DisplayName("确认：连续错够次数后整条作废")
    void confirm_tooManyWrongAttempts_dropsTheChallenge() {
        QqVerifyIssueVo issued = issue(QQ_A);
        properties.setMaxAttempts(3);

        for (int i = 0; i < 3; i++) {
            service.confirm(QQ_A, "000000");
        }

        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND,
                service.status(QQ_A, issued.getChallengeId()).getError(),
                "错够次数就整条删掉，不留中间态");
        assertFalse(service.confirm(QQ_A, issued.getCode()).verified(),
                "正确验证码也救不回来 —— 条目已经没了");
    }

    // ==================================================================
    // 有效期
    // ==================================================================

    @Test
    @DisplayName("有效期：过了 codeTtl 就查不到（拨钟不跑清理也要判过期）")
    void status_returns404AfterCodeExpiry() {
        QqVerifyIssueVo issued = issue(QQ_A);

        // ⚠️ 只拨钟、不触发任何写入 —— 清理只在签发路径上跑，所以这条钉的是
        // 「每次读取都要自己比 expiresAt」，而不是「清理够不够快」
        clock.advance(Duration.ofMinutes(11));

        assertEquals(ErrorCode.QQ_VERIFY_NOT_FOUND,
                service.status(QQ_A, issued.getChallengeId()).getError(),
                "过期之后即便条目还躺在 map 里，也不能当成有效");
    }

    @Test
    @DisplayName("有效期：确认通过后重新计时，不是沿用签发时刻")
    void confirm_extendsExpiryFromConfirmationTime() {
        QqVerifyIssueVo issued = issue(QQ_A);

        // 第 9 分钟才在群里确认（差一点就过期）
        clock.advance(Duration.ofMinutes(9));
        assertTrue(service.confirm(QQ_A, issued.getCode()).verified());

        // 再过 5 分钟：按签发时刻算已经过期，按确认时刻算还剩 5 分钟
        clock.advance(Duration.ofMinutes(5));
        assertTrue(service.status(QQ_A, issued.getChallengeId()).isSuccess(),
                "确认之后要重新计时 —— 否则第 9 分钟确认成功的人只剩 1 分钟注册");
    }

    // ==================================================================
    // 注册时消费
    // ==================================================================

    @Test
    @DisplayName("消费：确认过的可以消费一次，返回它绑定的 QQ")
    void consume_returnsTheBoundQq() {
        QqVerifyIssueVo issued = issue(QQ_A);
        service.confirm(QQ_A, issued.getCode());

        BizResult<String> result = service.consume(QQ_A, issued.getChallengeId());

        assertTrue(result.isSuccess());
        assertEquals(QQ_A, result.getData(), "返回的是记录里那个 QQ —— 调用方拿它写库");
    }

    @Test
    @DisplayName("消费：用完即焚，同一个凭证不能注册第二个账号")
    void consume_isOneShot() {
        QqVerifyIssueVo issued = issue(QQ_A);
        service.confirm(QQ_A, issued.getCode());
        service.consume(QQ_A, issued.getChallengeId());

        assertEquals(ErrorCode.QQ_VERIFY_REQUIRED,
                service.consume(QQ_A, issued.getChallengeId()).getError(),
                "第二次必须失败 —— 少了这条，一条验证可以注册出无数个账号");
    }

    @Test
    @DisplayName("消费：没确认过的不给过")
    void consume_requiresVerified() {
        QqVerifyIssueVo issued = issue(QQ_A);

        assertEquals(ErrorCode.QQ_VERIFY_REQUIRED,
                service.consume(QQ_A, issued.getChallengeId()).getError(),
                "光取了码、没在群里确认，不能注册");

        assertEquals(ErrorCode.QQ_VERIFY_REQUIRED, service.consume(QQ_A, null).getError(),
                "没带凭证一律拒绝 —— 不能当成「无需验证」放行");
        assertEquals(ErrorCode.QQ_VERIFY_REQUIRED, service.consume(QQ_A, "").getError());
    }

    // ==================================================================
    // 容量
    // ==================================================================

    @Test
    @DisplayName("容量：待验证请求到达上限后拒绝签发")
    void issue_rejectsWhenTooManyPending() {
        properties.setMaxPending(3);
        for (int i = 0; i < 3; i++) {
            issue("1000" + i);
        }

        assertEquals(ErrorCode.QQ_VERIFY_TOO_MANY, service.issue("99999").getError(),
                "匿名接口 + 无上限的内存表 = 内存 DoS，这个上限是唯一的兜底");
    }

    @Test
    @DisplayName("容量：过期条目会在下次签发时被清掉，不占额度")
    void issue_sweepsExpiredEntries() {
        properties.setMaxPending(2);
        issue("10001");
        issue("10002");
        assertEquals(ErrorCode.QQ_VERIFY_TOO_MANY, service.issue("10003").getError());

        clock.advance(Duration.ofMinutes(11));   // 两条都过期了

        assertTrue(service.issue("10003").isSuccess(),
                "清理只在签发路径上跑，所以这次签发顺手把过期的两条扫掉了");
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 签发并断言成功。
     *
     * @param qq QQ 号
     * @return 签发结果
     */
    private QqVerifyIssueVo issue(String qq) {
        BizResult<QqVerifyIssueVo> result = service.issue(qq);
        assertTrue(result.isSuccess(), "签发应当成功：" + result.resolveMessage());
        return result.getData();
    }

    /**
     * 查一次状态并回答「确认了没有」。查不到时返回 false。
     *
     * @param qq          QQ 号
     * @param challengeId 凭证
     * @return 已确认返回 true
     */
    private boolean verified(String qq, String challengeId) {
        BizResult<QqVerifyStatusVo> result = service.status(qq, challengeId);
        return result.isSuccess() && result.getData().isVerified();
    }

    /**
     * 可以拨动的时钟。
     *
     * <p>测 TTL 不能靠 {@code Thread.sleep} —— 慢、飘，而且会诱导后面的人干脆不测 TTL。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 把时钟往前拨。
         *
         * @param duration 拨动的时长
         */
        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
