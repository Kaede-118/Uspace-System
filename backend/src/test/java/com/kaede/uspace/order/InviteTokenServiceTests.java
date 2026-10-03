package com.kaede.uspace.order;

import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.BookingParticipantRole;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.FakeBookingParticipantMapper;
import com.kaede.uspace.space.FakeClosureMapper;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.dto.BookingInviteVo;
import com.kaede.uspace.space.dto.BookingParticipantVo;
import com.kaede.uspace.space.dto.InviteLinkVo;
import com.kaede.uspace.space.dto.JoinResultVo;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InviteTokenService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>令牌是包场排他性的唯一载体 —— 谁能进、谁不能进全看它，
 * 所以这里盯住两件事：<b>令牌本身够不够随机</b>（长度、字符集、两次不重复），
 * 以及<b>比对逻辑有没有留缺口</b>（任一侧为空都必须判不匹配，
 * 否则一个 null 就能让路人变成被邀请者）。
 */
class InviteTokenServiceTests {

    private static final Long HOST_USER = 1001L;

    /** 测试被邀请者 */
    private static final Long GUEST_USER = 1002L;

    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final FakeClosureMapper closureMapper = new FakeClosureMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();
    private final FakeBookingParticipantMapper participantMapper =
            new FakeBookingParticipantMapper(bookingMapper);

    private final ClosureService closureService =
            new ClosureService(closureMapper.asMapper(), storeMapper.asMapper());

    /** 真实实现（不是假货）—— findByToken 要调它的 listParticipants 组装名单 */
    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(), closureService,
            userMapper.asMapper(), participantMapper.asMapper());

    private final InviteTokenService service = new InviteTokenService(
            bookingMapper.asMapper(), bookingService, new WebProperties());

    @Test
    @DisplayName("生成：令牌长度固定为 43 个字符")
    void generate_hasFixedLength() {
        assertEquals(43, service.generate().length(),
                "32 字节随机数的 URL-safe Base64（去填充）恰好是 43 个字符，也恰好放得进 VARCHAR(64)");
    }

    @Test
    @DisplayName("生成：字符集是 URL-safe 的，不含需要转义的符号")
    void generate_isUrlSafe() {
        String token = service.generate();

        assertTrue(token.matches("[A-Za-z0-9_-]+"),
                "标准 Base64 的 + / = 在 URL 里要转义，多一层转义就多一处会被聊天软件截断的地方");
    }

    @Test
    @DisplayName("生成：连续两次不会得到同一个令牌")
    void generate_isNotRepeatable() {
        assertNotEquals(service.generate(), service.generate(),
                "令牌即凭证，可预测或可重复的凭证等于没有凭证");
    }

    @Test
    @DisplayName("比对：与包场上的令牌一致时通过")
    void matches_returnsTrueForIdenticalToken() {
        Booking booking = booking("abc123");

        assertTrue(service.matches(booking, "abc123"), "令牌一致应当放行");
    }

    @Test
    @DisplayName("比对：任一侧为空都必须判不匹配")
    void matches_returnsFalseWhenEitherSideMissing() {
        Booking noToken = booking(null);
        Booking withToken = booking("abc123");

        assertFalse(service.matches(null, "abc123"), "包场为空时不能放行");
        assertFalse(service.matches(noToken, "abc123"),
                "包场还没生成令牌（未付款）时，传什么令牌都不该放行");
        assertFalse(service.matches(withToken, null), "请求没带令牌时不能放行");
        assertFalse(service.matches(withToken, ""), "空串同样不能放行");
        assertFalse(service.matches(withToken, "   "), "全空白也不能放行");
    }

    @Test
    @DisplayName("比对：令牌不相等时不通过")
    void matches_returnsFalseForDifferentToken() {
        Booking booking = booking("abc123");

        assertFalse(service.matches(booking, "abc124"), "差一个字符也不能放行");
        assertFalse(service.matches(booking, "ABC123"), "比对是大小写敏感的");
    }

    @Test
    @DisplayName("查询：令牌有效时返回包场信息")
    void findByToken_returnsBooking() {
        Booking booking = booking("token-xyz");
        booking.setBookingNo("BK202609281200000001");
        booking.setInviteToken("token-xyz");
        booking.setStatus(BookingStatus.PAID.name());
        bookingMapper.seed(booking);

        BizResult<BookingInviteVo> result = service.findByToken("token-xyz");

        assertTrue(result.isSuccess(), "有效令牌应当查得到");
        assertEquals("BK202609281200000001", result.getData().getBookingNo(),
                "被邀请者要能看到这是哪一场");
    }

    @Test
    @DisplayName("查询：令牌无效或为空时返回 404")
    void findByToken_returns404ForInvalidToken() {
        assertEquals(ErrorCode.NOT_FOUND, service.findByToken("not-exist").getError(),
                "查不到就返回 404");
        assertEquals(ErrorCode.NOT_FOUND, service.findByToken(null).getError(), "空令牌返回 404");
        assertEquals(ErrorCode.NOT_FOUND, service.findByToken("").getError(), "空串返回 404");
    }

    @Test
    @DisplayName("查询：未付款的包场不会被任何令牌命中")
    void findByToken_ignoresUnpaidBooking() {
        Booking unpaid = booking(null);
        unpaid.setStatus(BookingStatus.PENDING_PAYMENT.name());
        bookingMapper.seed(unpaid);

        BizResult<BookingInviteVo> result = service.findByToken("any-token");

        assertEquals(ErrorCode.NOT_FOUND, result.getError(),
                "令牌是付款那一刻才生成的，未付款的场根本不该在令牌索引里出现 —— "
                        + "否则被邀请者能在包场人还没付钱时就进店");
    }

    @Test
    @DisplayName("查询：包场已结束时返回 404（时段过了，排他性就没了）")
    void findByToken_returns404ForFinishedBooking() {
        Booking finished = booking("token-old");
        finished.setStartAt(LocalDateTime.now().minusHours(5));
        finished.setEndAt(LocalDateTime.now().minusHours(1));
        bookingMapper.seed(finished);

        assertEquals(ErrorCode.NOT_FOUND, service.findByToken("token-old").getError(),
                "包场结束之后链接不该还能把人放进名单 —— 这一条原先只写在注释里"
                        + "（「令牌的有效性由包场时段界定」），代码一行都没实现");
    }

    @Test
    @DisplayName("查询：包场已撤销退款时返回 404（钱都退了，更不该还能加人）")
    void findByToken_returns404ForRefundedBooking() {
        Booking refunded = booking("token-refund");
        refunded.setStatus(BookingStatus.REFUNDED.name());
        bookingMapper.seed(refunded);

        assertEquals(ErrorCode.NOT_FOUND, service.findByToken("token-refund").getError(),
                "撤销退款之后令牌必须失效 —— 此前 BookingRefundService 根本不碰 invite_token，"
                        + "那条链接照样能打开、照样能把自己加进名单");
    }

    // ==================================================================
    // 邀请链接（包场人取链接）
    // ==================================================================

    @Test
    @DisplayName("取链接：包场人本人能取到，path 与 url 都给")
    void getInviteLink_returnsLinkForHost() {
        Booking booking = booking("token-xyz");
        bookingMapper.seed(booking);

        BizResult<InviteLinkVo> result = service.getInviteLink(booking.getId(), HOST_USER);

        assertTrue(result.isSuccess(), "包场人本人应当能取到邀请链接");
        assertEquals("token-xyz", result.getData().getInviteToken(), "返回的令牌要与库里的一致");
        assertEquals("/invite/token-xyz", result.getData().getPath(),
                "path 是给前端拼 location.origin 用的 —— 开发期后端拼出来的域名在手机上打不开");
        assertTrue(result.getData().getUrl().endsWith("/invite/token-xyz"),
                "url 用配置里的站点地址拼出来，作为兜底与「复制链接」用");
    }

    @Test
    @DisplayName("取链接：不是本人的场次返回 404 而不是 403")
    void getInviteLink_returns404ForOutsider() {
        Booking booking = booking("token-xyz");
        bookingMapper.seed(booking);

        assertEquals(ErrorCode.BOOKING_NOT_FOUND,
                service.getInviteLink(booking.getId(), HOST_USER + 1).getError(),
                "403 等于承认这场包场存在 —— 可以靠状态码的差异枚举出别人的包场单号");
        assertEquals(ErrorCode.BOOKING_NOT_FOUND,
                service.getInviteLink(999L, HOST_USER).getError(),
                "包场不存在与不是本人的，对外是同一种结果，不区分");
    }

    @Test
    @DisplayName("取链接：未付款的包场返回 40930")
    void getInviteLink_rejectsUnpaidBooking() {
        Booking unpaid = booking(null);
        unpaid.setStatus(BookingStatus.PENDING_PAYMENT.name());
        bookingMapper.seed(unpaid);

        assertEquals(ErrorCode.BOOKING_NOT_PAID,
                service.getInviteLink(unpaid.getId(), HOST_USER).getError(),
                "令牌还没生成，硬发一条空令牌的链接出去，用户点开只会看到「链接无效」—— "
                        + "而真正的原因是他自己还没付款，该引导他去付款而不是检查手机");
    }

    // ==================================================================
    // 加入（被邀请者点链接）
    // ==================================================================

    @Test
    @DisplayName("加入：凭令牌把调用者记进参与者表")
    void join_recordsParticipant() {
        Booking booking = booking("token-xyz");
        bookingMapper.seed(booking);

        BizResult<JoinResultVo> result = service.join("token-xyz", GUEST_USER);

        assertTrue(result.isSuccess(), "有效令牌应当能加入");
        assertTrue(result.getData().isJoined(), "首次加入报告「新加入」");
        assertEquals(1, result.getData().getParticipantCount(), "此时名单里只有他一个");
        assertEquals(1, participantMapper.size(), "参与者表里应当多了一行");
    }

    @Test
    @DisplayName("加入：令牌无效时返回 404，且一行都不写")
    void join_returns404ForInvalidToken() {
        assertEquals(ErrorCode.NOT_FOUND, service.join("not-exist", GUEST_USER).getError(),
                "令牌无效时对调用方是 404");
        assertEquals(0, participantMapper.size(),
                "无效令牌不该在参与者表里留下任何痕迹 —— 那是准入判定的依据，"
                        + "多写一行就等于凭空给人开了一扇门");
    }

    @Test
    @DisplayName("查询：返回里带上参与者名单，昵称一并填好")
    void findByToken_includesParticipants() {
        Booking booking = booking("token-xyz");
        bookingMapper.seed(booking);
        participantMapper.seed(booking.getId(), HOST_USER, BookingParticipantRole.HOST.name());
        participantMapper.seed(booking.getId(), GUEST_USER,
                BookingParticipantRole.PARTICIPANT.name());
        userMapper.seed(user(HOST_USER, "小枫"));
        userMapper.seed(user(GUEST_USER, "阿黄"));

        BizResult<BookingInviteVo> result = service.findByToken("token-xyz");

        assertTrue(result.isSuccess(), "有效令牌应当查得到");
        List<BookingParticipantVo> participants = result.getData().getParticipants();
        assertEquals(2, participants.size(), "两个人都应当在名单里");
        assertEquals(HOST_USER, participants.get(0).getUserId(), "发起人排在最前");
        assertEquals("小枫", participants.get(0).getNickname(), "昵称要批量补齐");
        assertEquals("阿黄", participants.get(1).getNickname(), "被邀请者的昵称同样要有");
    }

    /**
     * 构造一个用户。
     *
     * @param id       用户 ID
     * @param nickname 昵称
     * @return 用户实体
     */
    private static SysUser user(Long id, String nickname) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        return user;
    }

    /**
     * 构造一个包场。
     *
     * @param inviteToken 邀请令牌，可为 null
     * @return 包场
     */
    private static Booking booking(String inviteToken) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(1L);
        booking.setHostUserId(HOST_USER);
        booking.setStartAt(LocalDateTime.now().plusHours(1));
        booking.setEndAt(LocalDateTime.now().plusHours(5));
        booking.setPrice(BigDecimal.valueOf(500));
        booking.setStatus(BookingStatus.PAID.name());
        booking.setInviteToken(inviteToken);
        return booking;
    }
}
