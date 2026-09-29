package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 包场邀请令牌的生成与校验（模块 8）。
 *
 * <p><b>它是做什么的</b>：包场人付款成功后，系统生成一串随机令牌并落库。
 * 包场人把带令牌的链接分享给朋友，朋友凭链接被认作「被邀请者」，
 * 在包场时段内可以下单、拿到开门密码 —— 而其他人下不了单。
 *
 * <p><b>准入的落地方式是「不下发密码」而非门锁黑名单</b>：被邀请者与路人的区别，
 * 就是前者拿得到密码、后者拿不到。门锁上不设任何常驻密码。
 *
 * <p><b>令牌不单独设过期时间</b>：它的有效性完全由包场时段界定 ——
 * 校验链是「当前时刻落在某个已付款包场区间内」+「令牌与该场的令牌相等」。
 * 时段一过，令牌自动失效，不必再维护一个过期字段（多一个字段就多一处
 * 可能与时段判断不一致的地方）。
 *
 * <p><b>令牌只在下单请求体里传</b>（见 {@code CreateOrderRequest}），
 * 不进 URL 路径 —— 路径会进 access log、浏览器历史、Referer 头。
 * 只有「查看包场信息」这一个只读入口走路径参数，因为那时用户是主动点链接进来的。
 */
@Slf4j
@Service
public class InviteTokenService {

    /**
     * 令牌的随机字节数。
     *
     * <p>32 字节 = 256 位，Base64 URL 编码（去填充）后是 43 个字符，
     * 恰好填满 {@code biz_booking.invite_token} 的 {@code VARCHAR(64)}。
     * 这个长度的暴力猜测概率低到可以忽略，所以不需要额外的限流。
     */
    private static final int TOKEN_BYTES = 32;

    /** 随机源。用 SecureRandom 而非 Random —— 令牌即凭证，可预测的凭证等于没有凭证 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingMapper bookingMapper;

    public InviteTokenService(BookingMapper bookingMapper) {
        this.bookingMapper = bookingMapper;
    }

    /**
     * 生成一个邀请令牌（不落库）。
     *
     * <p>用 URL-safe 的 Base64 变体且去掉填充：令牌要塞进分享链接，
     * 标准 Base64 的 {@code +} {@code /} {@code =} 在 URL 里需要转义，
     * 多一层转义就多一处可能出错的地方（尤其是被聊天软件自动加空格、
     * 抑或被用户手动复制时截断）。
     *
     * <p>调用方负责落库，因为落库要与「包场转已付款」在<b>同一条 UPDATE</b> 里完成
     * （见 {@code BookingMapper#markPaid}）—— 分成两步就可能出现
     * 「已付款但没令牌」的包场：排他性生效了，被邀请者却进不来。
     * 生成与落库分开，是为了让撞唯一索引时能重新生成再试。
     *
     * @return 43 字符的 URL-safe 随机令牌
     */
    public String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 判断请求携带的令牌是否与本包场匹配。
     *
     * <p>用 {@link MessageDigest#isEqual} 而非 {@code String.equals} 做比对 ——
     * 后者一旦发现不同就立刻返回，比较耗时随「前多少个字符相同」变化，
     * 理论上可以被用来逐位猜出令牌（时序攻击）。前者的耗时与内容无关。
     *
     * <p>不过要如实说明：本方法的比对对象是<b>数据库里的固定值</b>，
     * 而每次请求都要先经过「当前时刻有已付款包场」这一关，
     * 攻击者即便逐位猜也需要先让系统处在包场时段内。防时序攻击在这里
     * 属于「顺手做对」，不是决定性的防线。
     *
     * @param booking 当前生效的包场，可为 null
     * @param token   请求携带的令牌，可为 null
     * @return 匹配返回 true；任一侧为空、或包场尚未生成令牌时返回 false
     */
    public boolean matches(Booking booking, String token) {
        if (booking == null || booking.getInviteToken() == null
                || token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                booking.getInviteToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 凭邀请令牌查看包场信息。
     *
     * <p>供被邀请者点开分享链接时使用：他需要先知道「这是谁包的场、几点的、
     * 在哪个店」，才决定要不要去。这是只读入口，不产生任何副作用。
     *
     * <p>查得到就说明该场已付款 —— 令牌是付款成功那一刻才生成的，
     * 未付款的包场令牌为空，不会被命中。
     *
     * @param token 邀请令牌
     * @return 成功时返回包场视图；令牌无效或为空时返回 {@link ErrorCode#NOT_FOUND}
     */
    public BizResult<BookingVo> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return BizResult.fail(ErrorCode.NOT_FOUND, "邀请链接无效");
        }

        Booking booking = bookingMapper.selectByInviteToken(token);
        if (booking == null) {
            // 刻意不区分「令牌不存在」与「包场已取消」—— 两者对调用方的处置相同（找包场人要新链接），
            // 分开反而会泄露「这个令牌曾经存在过」
            return BizResult.fail(ErrorCode.NOT_FOUND, "邀请链接无效或已失效");
        }
        return BizResult.ok(BookingVo.from(booking));
    }
}
