package com.kaede.uspace.user;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.user.dto.QqVerifyStatusVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * QQ 号验证（模块 1）。
 *
 * <p><b>它解决的问题</b>：模块 11 靠 {@code sys_user.qq} 把群消息的发送者对应到系统用户，
 * 而「用户在注册页填的那个 QQ 号」本身没有任何可信度 —— 他随手填别人的也行，
 * 那样群里来的人是谁就永远对不上。这个服务用一条<b>群内的往返</b>把两者绑起来：
 *
 * <ol>
 *   <li>用户在注册页填 QQ，取走一个 6 位验证码（{@link #issue}）</li>
 *   <li>他把验证码发到 QQ 群</li>
 *   <li>群里的机器人看到消息，把「发消息那个人的真实 QQ」与「消息里的验证码」
 *       一起回执给 {@link #confirm}</li>
 *   <li>{@link #confirm} <b>按发送者 QQ 查表</b>，核对验证码 —— 对上了才标记为已验证</li>
 *   <li>注册接口再调一次 {@link #consume}，确认无误后放行</li>
 * </ol>
 *
 * <p>第 4 步那个「<b>按发送者 QQ 查表</b>」是整套设计的基石：<b>验证码本身不是凭证</b>，
 * 群里所有人都看得见它，但只有持有该 QQ 的人才查得到自己那条记录。
 * 若改成「遍历所有记录找匹配的验证码」，就变成「谁拿着码就验证谁」，
 * 验证等于没做 —— 而那种写法一样能跑通全部演示。
 *
 * <p><b>存储：一张以 QQ 为键的内存表</b>。这是刻意的取舍，代价要如实说：
 * <b>进程一重启，正在验证的人全部作废</b>，他只能重新取一次码
 * （前端的 404 分支会提示他重来）。换来的是不必为一份一次性的临时状态建表。
 * 将来若要换数据库，改动全部在本类内部 —— 下面四个 public 方法就是它的全部契约。
 *
 * <p><b>为什么 6 位数字不需要限流</b>：这条推理与 {@code InviteTokenService} 的
 * 「256 位熵够了，所以不用限流」<b>不是一回事，别照搬</b>。6 位数字的空间确实很小，
 * 但攻击者猜对了也没用 —— 他只能查到自己那条，而唯一能通过验证的人是
 * 「持有那个 QQ 的人」（他发的消息才带得对 QQ 号）。<b>真正的防线是查表键，不是位数。</b>
 * 所以这里不做图形验证码、不做频率限制 —— 写了也只是摆设。
 */
@Slf4j
@Service
public class QqVerifyService {

    /** 验证码的取值范围（6 位，含前导零） */
    private static final int CODE_BOUND = 1_000_000;

    /** challengeId 的随机字节数。与 {@code InviteTokenService} 的令牌同规格 */
    private static final int ID_BYTES = 32;

    /** QQ 号格式。与 {@code RegisterRequest} 上那条 {@code @Pattern} 保持一致 */
    private static final Pattern QQ_PATTERN = Pattern.compile("^[1-9]\\d{4,11}$");

    /** 随机源。与项目里两个 mock 实现同一种安全习惯 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 待验证的请求，key 是 QQ 号 */
    private final Map<String, QqChallenge> pending = new ConcurrentHashMap<>();

    private final QqVerifyProperties properties;

    private final Clock clock;

    /**
     * 构造器注入。
     *
     * @param properties 有效期、次数上限等配置
     * @param clock      系统时钟（注入而非直接取，见 {@link QqVerifyConfig}）
     */
    public QqVerifyService(QqVerifyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // ==================================================================
    // 签发
    // ==================================================================

    /**
     * 签发一个验证码（注册页点「获取验证码」时调用）。
     *
     * <p>⚠️ <b>刻意不查库判「这个 QQ 是否已被绑定」</b>：本接口是匿名的，
     * 一旦查了，它就变成一个「一次请求即可判定任意 QQ 注册没有」的枚举器 ——
     * 想省掉一次群内往返，代价是把全体用户的 QQ 号变成可枚举的。
     *
     * <p><b>覆盖语义</b>：同一个 QQ 再取一次，旧记录（含已验证状态）立即作废。
     * 刻意<b>不</b>复用旧记录 —— 复用的话，攻击者可以先给受害者的 QQ 签发一条、
     * 自己攥着 challengeId；受害者随后自己点「获取验证码」，拿到的是<b>同一条</b>
     * （验证码是攻击者已知的），他发到群里验证通过后，攻击者拿自己那个 challengeId
     * 就能用这个 QQ 注册。覆盖式让攻击者拿不到受害者那一次的 challengeId。
     *
     * @param rawQq 用户填的 QQ 号
     * @return 成功时返回两个凭证（见 {@link QqVerifyIssueVo} 的类注释）
     */
    public BizResult<QqVerifyIssueVo> issue(String rawQq) {
        String qq = trimToNull(rawQq);
        if (qq == null || !QQ_PATTERN.matcher(qq).matches()) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "QQ 号格式不正确");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 惰性清理：只在写入路径上扫一遍，map 的上界因此约等于「TTL 窗口内的签发量」。
        // 刻意不引第三个调度器 —— 项目里那两个都带开关，被关掉之后 map 只增不减
        pending.values().removeIf(challenge -> challenge.isExpired(now));

        if (pending.size() >= properties.getMaxPending()) {
            log.warn("[QQ验证] 待验证请求已达上限 {}，拒绝签发", properties.getMaxPending());
            return BizResult.fail(ErrorCode.QQ_VERIFY_TOO_MANY);
        }

        QqChallenge challenge = new QqChallenge(
                newChallengeId(), qq, newCode(), now,
                now.plus(properties.getCodeTtl()), false, 0);
        pending.put(qq, challenge);

        // 日志里只记 QQ 与有效期，不记验证码 —— 演示期想看的话临时调 debug
        log.info("[QQ验证] 已签发 qq={} 有效期至 {}", qq, challenge.expiresAt());
        return BizResult.ok(QqVerifyIssueVo.of(
                challenge.challengeId(), challenge.code(), challenge.expiresAt(),
                Duration.between(now, challenge.expiresAt()).getSeconds()));
    }

    // ==================================================================
    // 查询与确认
    // ==================================================================

    /**
     * 查一次验证状态（注册页轮询用）。
     *
     * @param rawQq       用户填的 QQ
     * @param challengeId 签发时给出的凭证
     * @return 未确认时返回 {@code verified=false}（200）；挑战不存在 / 已过期 / 凭证对不上时返回 404
     */
    public BizResult<QqVerifyStatusVo> status(String rawQq, String challengeId) {
        QqChallenge challenge = find(trimToNull(rawQq), challengeId, LocalDateTime.now(clock));
        if (challenge == null) {
            return BizResult.fail(ErrorCode.QQ_VERIFY_NOT_FOUND);
        }
        return BizResult.ok(QqVerifyStatusVo.of(challenge.verified(), challenge.expiresAt()));
    }

    /**
     * 群消息回执：机器人告诉我们「QQ 为 {@code senderQq} 的人发了一条含 {@code code} 的消息」。
     *
     * <p>⚠️ <b>必须按发送者 QQ 查表</b>，不遍历找匹配的验证码 —— 理由见类注释。
     *
     * @param senderQq 群里那个人的<b>真实</b> QQ（来自群消息事件，不是他自己填的）
     * @param code     从消息文本里解析出来的验证码
     * @return 确认结果；没有待验证的请求时一律 {@code IGNORED}，机器人据此保持沉默
     */
    public ConfirmResult confirm(String senderQq, String code) {
        String qq = trimToNull(senderQq);
        String input = trimToNull(code);
        if (qq == null || input == null) {
            return ConfirmResult.ignored();
        }
        LocalDateTime now = LocalDateTime.now(clock);

        /*
         * 用 compute 而不是「先 get 再 put」：后者在「用户重新签发」与「机器人确认」
         * 并发时会把刚签发的新记录覆盖回旧的，而那种丢失不报任何错。
         * 结果用 AtomicReference 带出来 —— lambda 里改不了外部的局部变量。
         */
        AtomicReference<ConfirmResult> outcome = new AtomicReference<>(ConfirmResult.ignored());
        pending.compute(qq, (key, current) -> {
            if (current == null || current.isExpired(now)) {
                // 没有待验证的请求 —— 静默忽略。机器人若对每条消息都回一句
                // 「验证码不正确」，群会被刷屏，而绝大多数消息本来就不是验证码
                return null;
            }
            if (!constantTimeEquals(current.code(), input)) {
                int attempts = current.failedAttempts() + 1;
                outcome.set(ConfirmResult.mismatch());
                if (attempts >= properties.getMaxAttempts()) {
                    log.warn("[QQ验证] 验证码连续错误 {} 次，已作废 qq={}", attempts, qq);
                    return null;   // 整条删掉，不留中间态
                }
                return current.withFailedAttempt();
            }
            outcome.set(ConfirmResult.ok());
            // 续期到「确认时刻 + verifiedTtl」，不是沿用签发时刻 ——
            // 否则第 9 分钟才确认成功的人只剩 1 分钟注册
            return current.withVerified(now.plus(properties.getVerifiedTtl()));
        });

        if (outcome.get().verified()) {
            log.info("[QQ验证] 群内确认通过 qq={}", qq);
        }
        return outcome.get();
    }

    /**
     * 注册时校验并消费掉这次验证。
     *
     * <p><b>用完即焚</b>：同一个 challengeId 不能注册第二个账号。
     *
     * @param qq          注册请求里填的 QQ（只用来定位记录）
     * @param challengeId 签发时给出的凭证
     * @return 成功时返回这次验证<b>实际绑定的 QQ</b> ——
     *         调用方应当拿它写库，而不是请求里的那个（两者理论上一致，但以记录为准更安全）
     */
    public BizResult<String> consume(String qq, String challengeId) {
        String trimmed = trimToNull(qq);
        QqChallenge challenge = find(trimmed, challengeId, LocalDateTime.now(clock));
        if (challenge == null || !challenge.verified()) {
            return BizResult.fail(ErrorCode.QQ_VERIFY_REQUIRED);
        }
        // 删的时候带上原值（两参数的 remove 只在值相等时才删），
        // 避免误删掉用户在这期间重新签发的那条
        pending.remove(trimmed, challenge);
        return BizResult.ok(challenge.qq());
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 取一条仍然有效的挑战。
     *
     * <p>⚠️ <b>每次读取都要比 {@code expiresAt}</b>，不能只靠 {@link #issue} 里那次清理 ——
     * 清理只为省内存，<b>不为正确性</b>：已经过期但还没被扫到的条目绝不能当成有效。
     *
     * @param qq          QQ 号，可为 null
     * @param challengeId 凭证，可为 null
     * @param now         当前时刻
     * @return 挑战；不存在、已过期、或凭证对不上时返回 null
     */
    private QqChallenge find(String qq, String challengeId, LocalDateTime now) {
        if (qq == null || challengeId == null || challengeId.isBlank()) {
            return null;
        }
        QqChallenge challenge = pending.get(qq);
        if (challenge == null || challenge.isExpired(now)) {
            return null;
        }
        return constantTimeEquals(challenge.challengeId(), challengeId) ? challenge : null;
    }

    /**
     * 生成一个新的 challengeId。
     *
     * @return 32 字节随机数的 URL-safe Base64（去填充），43 个字符
     */
    private static String newChallengeId() {
        byte[] raw = new byte[ID_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 生成一个新的 6 位验证码（含前导零）。
     *
     * @return 形如 {@code 042317}
     */
    private static String newCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(CODE_BOUND));
    }

    /**
     * 定长比较，避免按字符提前返回。
     *
     * <p>⚠️ 在这里它<b>不是防线</b>（真正的防线是按 QQ 查表，见类注释），
     * 只是成本为零所以顺手做对 —— 别把它当成「已经防住了暴力猜测」。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @return 相同返回 true
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 去空白，空串归一为 null。
     *
     * @param value 原值，可为 null
     * @return 去空白后的值；空或全空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================================================================
    // 内部类型
    // ==================================================================

    /**
     * 一次验证请求的全部状态。
     *
     * <p>用<b>不可变 record</b>：状态变化产生新实例再放回 map，避免多线程下读到
     * 「一半更新」的对象（{@code MockLockServiceImpl.IssuedPasscode} 同款做法）。
     */
    private record QqChallenge(
            String challengeId,
            String qq,
            String code,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            boolean verified,
            int failedAttempts) {

        /**
         * 是否已过期。
         *
         * @param now 当前时刻
         * @return 到点即算过期（半开区间）
         */
        boolean isExpired(LocalDateTime now) {
            return !now.isBefore(expiresAt);
        }

        /**
         * 记一次错猜。
         *
         * @return 次数加一后的新实例
         */
        QqChallenge withFailedAttempt() {
            return new QqChallenge(challengeId, qq, code, issuedAt, expiresAt,
                    verified, failedAttempts + 1);
        }

        /**
         * 标记为已验证，并把有效期续到新时刻。
         *
         * @param newExpiresAt 新的截止时刻（确认时刻 + verifiedTtl）
         * @return 更新后的新实例
         */
        QqChallenge withVerified(LocalDateTime newExpiresAt) {
            return new QqChallenge(challengeId, qq, code, issuedAt, newExpiresAt,
                    true, failedAttempts);
        }
    }

    /**
     * 群消息回执的结果（给机器人那一侧用）。
     *
     * @param verified 是否确认通过
     * @param reply    机器人该往群里回的话；<b>空串表示不要回复</b>
     */
    public record ConfirmResult(boolean verified, String reply) {

        /**
         * 确认通过。
         *
         * @return 结果
         */
        static ConfirmResult ok() {
            return new ConfirmResult(true, "验证成功，可以回注册页继续了");
        }

        /**
         * 验证码对不上。
         *
         * @return 结果
         */
        static ConfirmResult mismatch() {
            return new ConfirmResult(false, "验证码不正确");
        }

        /**
         * 没有待验证的请求 —— <b>机器人应当保持沉默</b>。
         *
         * @return 结果
         */
        static ConfirmResult ignored() {
            return new ConfirmResult(false, "");
        }
    }
}
