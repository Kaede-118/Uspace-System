package com.kaede.uspace.common.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 随机令牌的生成（公共层）。
 *
 * <p>全项目有两处需要它，而且必须生成出<b>同一种规格</b>：
 * <ul>
 *   <li>{@code order} 包的 {@code InviteTokenService} —— 包场付款成功时生成邀请令牌</li>
 *   <li>{@code space} 包的 {@code BookingService} —— <b>0 元包场</b>在创建那一刻
 *       就生效，也要生成令牌</li>
 * </ul>
 *
 * <p><b>为什么两处不能共用一份实现</b>：{@code InviteTokenService} 住在 {@code order} 包，
 * 而依赖方向是 {@code order → space}，{@code space} 不能反向调它。
 * 于是把这段纯函数提到公共层，两边都调它 —— 生成规格只有一处定义，
 * 将来要改长度也不会「一边改一边漏」。漏了的话症状很隐蔽：
 * 一边生成的令牌另一边认不出来（长度不同，或混进了需要转义的字符），
 * 表现为「链接打不开」，而两边都不会报错。
 */
public final class TokenGenerator {

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

    /** 工具类，不实例化 */
    private TokenGenerator() {
    }

    /**
     * 生成一个令牌。
     *
     * <p>用 URL-safe 的 Base64 变体且去掉填充：令牌要塞进分享链接，
     * 标准 Base64 的 {@code +} {@code /} {@code =} 在 URL 里需要转义，
     * 多一层转义就多一处可能出错的地方（尤其是被聊天软件自动加空格、
     * 抑或被用户手动复制时截断）。
     *
     * <p><b>调用方负责落库</b>：本方法只产出一个字符串，不碰数据库。
     *
     * @return 43 个字符的 URL-safe 随机串
     */
    public static String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
