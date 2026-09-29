package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 点击开门（创建订单）的请求体。
 *
 * <p><b>这个请求没有「门店」「时长」这类参数</b>，因为都没有意义：
 * 单门店下门店是唯一的；时长由用户什么时候点「结束使用」决定，
 * 下单时选时长是「预约制」的做法，而本系统是即时制。
 *
 * <p>唯一可传的是邀请令牌 —— 且只有包场时段内的被邀请者才需要它。
 */
@Data
public class CreateOrderRequest {

    /**
     * 包场邀请令牌。平时下单不传。
     *
     * <p><b>它的作用范围很窄</b>：只在「当前时刻落在某个已付款包场区间内」
     * 时才会被检查。平时（无包场）传什么都不影响；包场时段内不传、
     * 或传了一个与本场对不上的令牌，都会被拒（{@code BOOKING_ACCESS_DENIED}）。
     *
     * <p>包场人本人不需要它 —— 系统按 {@code host_user_id} 就认得出来。
     * 这个字段是为<b>被邀请者</b>准备的：他们不记在包场的任何字段上，
     * 唯一的凭证就是包场人分享出去的那个链接。
     *
     * <p>刻意加长度上限：它最终会与库里的令牌做比对，限长可以挡掉
     * 「塞一个超长字符串进来」这类无意义的请求。
     */
    @Size(max = 64, message = "邀请令牌格式不正确")
    private String inviteToken;
}
