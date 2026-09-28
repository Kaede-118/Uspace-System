package com.kaede.uspace.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证模块配置，对应 {@code application.properties} 的 {@code uspace.auth.*}。
 *
 * <p>写法与既有的 {@code LockProperties}、{@code BillingProperties} 一致：
 * {@code @Data} + {@code @Component} + {@code @ConfigurationProperties}，
 * 默认值就地初始化在字段上。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.auth")
public class JwtProperties {

    /**
     * JWT 签名密钥。
     *
     * <p><b>值来自环境变量，不落在配置文件里</b> ——
     * 配置文件会进版本库，密钥进了版本库就等于公开。
     * {@code application.properties} 里写的是 {@code ${JWT_SECRET:}}，
     * 只负责把环境变量映射到这个字段。
     *
     * <p>长度要求至少 32 字节（HS256 的密钥强度下限）。
     * 校验在 {@link JwtService} 的构造器里做，不满足直接让应用起不来 ——
     * 弱密钥带来的是一种「看起来有签名但其实很容易被伪造」的虚假安全感，
     * 比明摆着没有签名更危险。
     *
     * <p>默认空串而非 null，是为了让「没配置」与「配置了个空值」走同一条判定分支。
     */
    private String secret = "";

    /**
     * 凭证有效期（天）。
     *
     * <p>取 7 天而不是更短：共享空间的用户可能间隔数周才再次到店，
     * 有效期太短会让人每次都要重新登录，而这不是一个高频使用的应用。
     * 安全上不吃亏 —— 有 token 版本号兜底，封禁与改密都是立即生效的。
     */
    private int expireDays = 7;

    /**
     * 签发者标识，写进载荷的 {@code iss} 声明。
     *
     * <p>校验时一并比对：万一将来同一个密钥被用于签发别的用途的凭证，
     * 只认 {@code iss} 相符的那些，不会混用。
     */
    private String issuer = "uspace";
}
