package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.entity.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final InviteTokenService service = new InviteTokenService(bookingMapper.asMapper());

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

        BizResult<BookingVo> result = service.findByToken("token-xyz");

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

        BizResult<BookingVo> result = service.findByToken("any-token");

        assertEquals(ErrorCode.NOT_FOUND, result.getError(),
                "令牌是付款那一刻才生成的，未付款的场根本不该在令牌索引里出现 —— "
                        + "否则被邀请者能在包场人还没付钱时就进店");
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
