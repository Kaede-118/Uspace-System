package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.JwtPayload;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的签发与解析（模块 2）。
 *
 * <p><b>本类只回答两个问题</b>：这段凭证是不是本系统签发的、有没有过期。
 * 它<b>不判断 token 版本号</b> —— 那需要查数据库，属于认证过滤器的职责。
 * 这样切分的意义在于：本类不依赖数据访问层，可以脱离 Spring 容器直接单测，
 * 覆盖篡改、换密钥、过期、伪造签发者等各种情况。
 *
 * <p>用 HS256 对称签名：签发方与校验方是同一个服务，没有必要引入非对称密钥
 * （那套适用于「多方校验、一方签发」的场景，本系统不是）。
 *
 * <p><b>密钥在构造时校验，不满足直接让应用启动失败</b>。这是刻意的 fail-fast：
 * 一个空密钥或短密钥不会导致任何报错，只会让签出来的凭证人人都能伪造 ——
 * 而这种「看起来有签名」的假象比明摆着没有签名危险得多，
 * 因为没人会去怀疑它。宁可起不来，也不要带着弱密钥上线。
 */
@Slf4j
@Service
public class JwtService {

    /** 角色声明名 */
    private static final String CLAIM_ROLE = "role";

    /** token 版本号声明名 */
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    /**
     * 密钥的最小字节数。
     *
     * <p>32 字节 = 256 位，是 HS256 的密钥强度下限。
     * <b>按字节而非字符判定</b>：UTF-8 下一个中文字符占 3 字节，
     * 若按字符数校验，一个 32 字的中文密钥会被判定为合格，
     * 实际强度却只有 96 字节 —— 虽然够用，但判定标准与文档说的就不是一回事了。
     * 更重要的是反向情形：11 个中文字符按字符数算「不够 32」、
     * 按字节算却有 33 字节，两种口径给出的结论不一致，必须固定一种。
     */
    private static final int MIN_SECRET_BYTES = 32;

    /** 解析后的签名密钥 */
    private final SecretKey secretKey;

    /** 凭证有效期 */
    private final Duration validity;

    /** 签发者标识，写进 iss 声明并在校验时比对 */
    private final String issuer;

    /**
     * 构造 JWT 服务，并校验密钥。
     *
     * <p>密钥校验失败时抛 {@link IllegalStateException} 让应用启动失败，
     * 而不是打个警告继续跑 —— 见类注释。
     *
     * @param properties 认证配置
     * @throws IllegalStateException 密钥未配置或不足 32 字节时抛出
     */
    public JwtService(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 签名密钥未配置。请设置环境变量 JWT_SECRET（至少 32 字节）后重启服务。");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT 签名密钥过短：当前 %d 字节，HS256 要求至少 %d 字节。"
                            + "注意是按【字节】计算，一个中文字符占 3 字节。",
                    keyBytes.length, MIN_SECRET_BYTES));
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.validity = Duration.ofDays(properties.getExpireDays());
        this.issuer = properties.getIssuer();

        // 只记长度，绝不记密钥内容 —— 日志会被收集、转发、长期留存
        log.info("[认证] JWT 服务已就绪：有效期 {} 天，签发者 {}，密钥 {} 字节",
                properties.getExpireDays(), issuer, keyBytes.length);
    }

    /**
     * 签发凭证。
     *
     * @param userId       用户 ID，写进标准声明 {@code sub}
     * @param role         签发时的角色（仅留痕，鉴权以数据库为准）
     * @param tokenVersion 签发时的 token 版本号，用于日后比对是否已被撤销
     * @return 紧凑格式的 JWT 字符串
     */
    public String issue(Long userId, String role, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验凭证。
     *
     * <p>校验三件事：签名是否正确、是否由本系统签发（比对 {@code iss}）、是否已过期。
     * <b>不校验 token 版本号</b> —— 见类注释。
     *
     * @param token 紧凑格式的 JWT 字符串
     * @return 解析出的载荷
     * @throws io.jsonwebtoken.ExpiredJwtException 凭证已过期时抛出（调用方据此区分「过期」与「无效」）
     * @throws io.jsonwebtoken.JwtException        签名错误、格式错误、签发者不符时抛出
     * @throws IllegalArgumentException            token 为空时抛出
     */
    public JwtPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // 版本号缺失时给 -1：它与库里的任何合法值都不相等，
        // 于是这份凭证会被判定为已撤销。方向是安全的 —— 宁可错杀，不可放过
        Integer tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Integer.class);

        return new JwtPayload(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_ROLE, String.class),
                tokenVersion == null ? -1 : tokenVersion
        );
    }

    /**
     * 取凭证有效期（秒），用于登录响应里的 {@code expiresIn}。
     *
     * @return 有效期秒数
     */
    public long getExpireSeconds() {
        return validity.getSeconds();
    }
}
