package com.kaede.uspace.order;

import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.dto.BookingInviteVo;
import com.kaede.uspace.space.dto.InviteLinkVo;
import com.kaede.uspace.space.dto.JoinResultVo;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 包场邀请令牌（模块 8）。
 *
 * <p><b>它承担邀请这条链路上的三件事</b>，都由同一个令牌驱动：
 * <ol>
 *   <li><b>生成</b> —— 包场付款成功时生成，与「转已付款」写在同一条 UPDATE 里</li>
 *   <li><b>查看</b> —— 被邀请者点开链接，看这是谁包的场、几点的、都有谁在
 *       （{@link #findByToken}）</li>
 *   <li><b>加入</b> —— 落地页加载后自动调，把这个人记进参与者表
 *       （{@link #join}）。加入之后他下单就不必再带令牌了</li>
 * </ol>
 * 另有 {@link #getInviteLink}，是<b>包场人</b>取链接用的入口 ——
 * 令牌在付款时生成后一直躺在库里，在此之前没有任何接口能把它交给包场人。
 *
 * <p><b>令牌的定位已经变了</b>：它曾经是被邀请者的唯一凭证，现在<b>降级为兜底</b> ——
 * 常规路径是「点开链接 → 加入参与者表 → 之后一切判定看表」。令牌仍然有用，
 * 因为落地页那次自动加入可能失败（网络抖动、用户在请求发出前就点了开门），
 * 那时下单带上令牌仍能进场。
 *
 * <p><b>令牌不单独设过期时间</b>：它的有效性完全由包场时段界定 ——
 * 时段一过，那场包场的排他性自然消失，令牌也就没有意义了。
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

    /**
     * 邀请落地页的前端路由前缀。
     *
     * <p>后端只拼出这一段路径，前端路由怎么定义是前端的事 ——
     * 但两边必须一致，所以它是一处约定，前端改路由时这里也要改。
     */
    private static final String INVITE_PATH_PREFIX = "/invite/";

    private final BookingMapper bookingMapper;
    private final BookingService bookingService;
    private final WebProperties webProperties;

    public InviteTokenService(BookingMapper bookingMapper,
                              BookingService bookingService,
                              WebProperties webProperties) {
        this.bookingMapper = bookingMapper;
        this.bookingService = bookingService;
        this.webProperties = webProperties;
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
     * 凭邀请令牌查看包场信息与参与者名单。
     *
     * <p>供被邀请者点开分享链接时使用：他需要先知道「这是谁包的场、几点的、
     * 都有谁在」，才决定要不要去。<b>只读，不产生任何副作用</b> ——
     * 这一点是刻意的：GET 会被浏览器预取、被刷新、被爬虫重复请求，
     * 让它顺手把人记进名单，等于「谁点开过链接」由这些无关的动作决定。
     * 真正的加入是落地页加载后单独发的那次 POST（见 {@link #join}）。
     *
     * <p>查得到就说明该场已付款 —— 令牌是付款成功那一刻才生成的，
     * 未付款的包场令牌为空，不会被命中。
     *
     * @param token 邀请令牌
     * @return 成功时返回包场视图与名单；令牌无效或为空时返回 {@link ErrorCode#NOT_FOUND}
     */
    public BizResult<BookingInviteVo> findByToken(String token) {
        Booking booking = findBookingByToken(token);
        if (booking == null) {
            // 刻意不区分「令牌不存在」与「包场已取消」—— 两者对调用方的处置相同
            // （找包场人要新链接），分开反而会泄露「这个令牌曾经存在过」
            return BizResult.fail(ErrorCode.NOT_FOUND, "邀请链接无效或已失效");
        }
        return BizResult.ok(BookingInviteVo.of(booking,
                bookingService.listParticipants(booking.getId())));
    }

    /**
     * 凭邀请令牌加入包场（落地页加载后自动调用）。
     *
     * <p>重复调用不是错误，返回的是「你已经进入了」而不是报错 ——
     * 刷新页面、从聊天记录里再点一次都会走到这里。详见
     * {@code BookingService#joinByBooking}。
     *
     * <p><b>为什么是 POST 而不是让 GET 顺手做了</b>：GET 会被预取与重复请求，
     * 把它变成写操作，等于让浏览器的自主行为决定谁进了名单；
     * 而且 HTTP 语义上 GET 本就该是安全的。用户感受是一样的 ——
     * 落地页加载完就自动发这个请求，点开链接即视为进入。
     *
     * @param token  邀请令牌
     * @param userId 加入者用户 ID
     * @return 加入结果；令牌无效时返回 {@link ErrorCode#NOT_FOUND}
     */
    public BizResult<JoinResultVo> join(String token, Long userId) {
        Booking booking = findBookingByToken(token);
        if (booking == null) {
            return BizResult.fail(ErrorCode.NOT_FOUND, "邀请链接无效或已失效");
        }
        return bookingService.joinByBooking(booking, userId);
    }

    /**
     * 取某场包场的邀请链接（<b>只有包场人本人</b>）。
     *
     * <p>这是包场人把链接分享出去的唯一入口：令牌在付款成功时就生成了，
     * 但在本方法之前，没有任何接口能把它交给包场人。
     *
     * <p><b>不是本人的场次一律返回 404，而不是 403</b> —— 沿用项目既有约定：
     * 403 等于承认「这场包场存在」，可以靠状态码的差异枚举出别人的包场单号。
     * 这与 {@code OrderVo}、{@code CARD_NOT_FOUND} 是同一条边界。
     *
     * @param bookingId 包场 ID
     * @param userId    当前登录用户 ID
     * @return 成功时返回链接信息；不是本人的返回 {@link ErrorCode#BOOKING_NOT_FOUND}，
     *         尚未付款的返回 {@link ErrorCode#BOOKING_NOT_PAID}
     */
    public BizResult<InviteLinkVo> getInviteLink(Long bookingId, Long userId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getHostUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
        }
        // 状态与令牌都要看：令牌是付款那一条 UPDATE 里写的，理论上「PAID 但没有令牌」
        // 不会出现，但真出现了（比如历史上手工改过库），这里挡住比发出去一条
        // 打不开的链接要好 —— 后者用户只会以为是自己手机的问题
        if (!BookingStatus.PAID.name().equals(booking.getStatus())
                || booking.getInviteToken() == null) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_PAID);
        }
        return BizResult.ok(toInviteLink(booking));
    }

    /**
     * 凭令牌取出包场实体。
     *
     * <p>用私有方法而不是 public 的「先查实体再判断」，是为了让下游拿不到
     * 「查得到但已经取消」的中间状态 —— 本类对外只暴露三种完整的结果。
     *
     * @param token 邀请令牌，可为 null
     * @return 包场实体；令牌为空、空白或查不到时返回 null
     */
    private Booking findBookingByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return bookingMapper.selectByInviteToken(token);
    }

    /**
     * 把包场实体翻译成链接视图。
     *
     * @param booking 包场实体（调用方已确认已付款且令牌非空）
     * @return 链接视图
     */
    private InviteLinkVo toInviteLink(Booking booking) {
        String path = INVITE_PATH_PREFIX + booking.getInviteToken();

        InviteLinkVo vo = new InviteLinkVo();
        vo.setBookingId(booking.getId());
        vo.setBookingNo(booking.getBookingNo());
        vo.setInviteToken(booking.getInviteToken());
        vo.setPath(path);
        vo.setUrl(joinUrl(webProperties.getBaseUrl(), path));
        vo.setStartAt(booking.getStartAt());
        vo.setEndAt(booking.getEndAt());
        return vo;
    }

    /**
     * 把站点地址与路径拼成完整链接。
     *
     * <p>配置里多写一个结尾斜杠是很常见的（复制粘贴时带上），
     * 不处理就会拼出 {@code https://a.com//invite/xxx} —— 多数服务器能容忍，
     * 但有的会 404，而这类问题只在生产环境才被发现。
     *
     * @param baseUrl 站点地址，可为 null 或空
     * @param path    以斜杠开头的路径
     * @return 完整链接；{@code baseUrl} 为空时退化为只返回路径
     */
    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return path;
        }
        String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return trimmed + path;
    }
}
