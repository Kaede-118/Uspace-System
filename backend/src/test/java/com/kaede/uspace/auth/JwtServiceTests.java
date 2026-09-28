package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.JwtPayload;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b> —— JwtService 刻意不依赖数据访问层
 * （版本号比对是过滤器的事），所以可以直接 new 出来测。
 *
 * <p>覆盖两类东西：一是签发的凭证能被正确解回，二是各种伪造与失效情形
 * 都必须被拒绝。第二类更重要 —— 一份「谁都能伪造」的凭证比没有凭证更糟，
 * 因为它看起来是安全的。
 *
 * <p>另有一条用例专门钉住密钥长度按<b>字节</b>而非字符判定，
 * 这是中文密钥场景下最容易写错的地方。
 */
class JwtServiceTests {

    /** 测试用密钥，长度远超 32 字节。真实密钥走环境变量，这里是纯单测，不涉及泄露 */
    private static final String SECRET = "unit-test-secret-for-jwt-service-0123456789abcdef";

    /** 用户 ID 基准值 */
    private static final Long USER_ID = 42L;

    /** 默认配置的 JwtService，各用例共用 */
    private final JwtService jwtService = newJwtService(SECRET, 7, "uspace");

    /**
     * 按给定参数构造一个 JwtService。
     *
     * @param secret     密钥
     * @param expireDays 有效期天数
     * @param issuer     签发者
     * @return 构造好的服务实例
     */
    private static JwtService newJwtService(String secret, int expireDays, String issuer) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpireDays(expireDays);
        properties.setIssuer(issuer);
        return new JwtService(properties);
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("签发的凭证能被解回全部载荷，且 sub 是用户 ID 的字符串形式")
    void issue_thenParse_returnsAllClaims() {
        String token = jwtService.issue(USER_ID, "ADMIN", 3);

        JwtPayload payload = jwtService.parse(token);

        assertEquals(USER_ID, payload.userId(), "用户 ID 应当能从 sub 声明中原样取回");
        assertEquals("ADMIN", payload.role(), "角色应当原样取回");
        assertEquals(3, payload.tokenVersion(), "版本号应当原样取回");
    }

    @Test
    @DisplayName("有效期按配置的天数生效")
    void issue_expiresInConfiguredDays() {
        assertEquals(7 * 24 * 60 * 60L, jwtService.getExpireSeconds(),
                "7 天应当换算成 604800 秒 —— 前端拿这个值判断凭证何时过期");
    }

    // ==================================================================
    // 伪造与失效：这些情形必须全部拒绝
    // ==================================================================

    @Test
    @DisplayName("凭证被篡改一个字符即拒绝")
    void parse_rejectsTamperedToken() {
        String token = jwtService.issue(USER_ID, "USER", 0);
        char lastChar = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (lastChar == 'A' ? 'B' : 'A');

        assertThrows(JwtException.class, () -> jwtService.parse(tampered),
                "改动任意一位都会让签名对不上，必须拒绝");
    }

    @Test
    @DisplayName("用别的密钥签发的凭证被拒绝")
    void parse_rejectsTokenSignedByAnotherKey() {
        JwtService attacker = newJwtService(
                "another-secret-that-is-long-enough-0123456789", 7, "uspace");
        String forged = attacker.issue(USER_ID, "ADMIN", 0);

        assertThrows(JwtException.class, () -> jwtService.parse(forged),
                "签名密钥不同就必须拒绝 —— 否则任何人都能签发管理员凭证");
    }

    @Test
    @DisplayName("签发者不符的凭证被拒绝")
    void parse_rejectsAnotherIssuer() {
        JwtService other = newJwtService(SECRET, 7, "some-other-system");
        String token = other.issue(USER_ID, "USER", 0);

        assertThrows(JwtException.class, () -> jwtService.parse(token),
                "密钥相同但签发者不同也应当拒绝，避免同一个密钥被复用到别的用途");
    }

    @Test
    @DisplayName("过期的凭证被拒绝，且抛出的是可区分的过期异常")
    void parse_rejectsExpiredToken() {
        // 有效期 -1 天 —— 签发出来就已经是过期的
        JwtService expiredIssuer = newJwtService(SECRET, -1, "uspace");
        String expired = expiredIssuer.issue(USER_ID, "USER", 0);

        assertThrows(ExpiredJwtException.class, () -> jwtService.parse(expired),
                "过期要用专门的异常类型，过滤器才能区分「过期」与「无效」并给出不同提示");
    }

    @Test
    @DisplayName("乱码与非凭证字符串被拒绝")
    void parse_rejectsGarbageInput() {
        assertThrows(JwtException.class, () -> jwtService.parse("not-a-jwt-at-all"));
        assertThrows(JwtException.class, () -> jwtService.parse("a.b.c"));
        assertThrows(IllegalArgumentException.class, () -> jwtService.parse(""));
    }

    // ==================================================================
    // 密钥校验：宁可起不来，也不能带着弱密钥跑
    // ==================================================================

    @Test
    @DisplayName("密钥为空时构造即抛异常，不静默降级")
    void constructor_throwsWhenSecretMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newJwtService("", 7, "uspace"),
                "空密钥必须让应用起不来 —— 用空密钥签名等于没有签名，且不会有任何报错提醒");

        assertTrue(ex.getMessage().contains("JWT_SECRET"),
                "异常消息要指明该配哪个环境变量，否则排查时只能翻代码");
    }

    @Test
    @DisplayName("密钥长度按【字节】而非字符判定")
    void constructor_checksSecretLengthInBytes() {
        // 10 个中文字符 = 30 字节（UTF-8 下每字 3 字节），不足 32 字节 → 应当拒绝
        assertThrows(IllegalStateException.class,
                () -> newJwtService("中文密钥测试一二三四", 7, "uspace"),
                "按字符数算它有 10 个字符、按字节算 30 字节，两种口径都不足 32 —— 拒绝");

        // 11 个中文字符 = 33 字节，按字符数不足 32、按字节数合格 → 应当通过。
        // 这一条正是用来钉住「按字节判定」这个口径的
        assertDoesNotThrow(
                () -> newJwtService("中文密钥测试一二三四五", 7, "uspace"),
                "11 个中文占 33 字节，按字节判定应当通过 —— 若改成按字符判定，这条会失败");
    }
}
