package com.kaede.uspace.promotion.dto;

import lombok.Data;

import java.util.List;

/**
 * 我的卡包。
 *
 * <p>把三样东西一次给全，前端不必连发三个请求：
 * <ul>
 *   <li>{@code active} —— 此刻生效的卡，没有则为 null。首页展示「月卡剩余 N 天」用它</li>
 *   <li>{@code pending} —— 有一笔待支付的购买单，没有则为 null。前端据此显示「去支付」</li>
 *   <li>{@code history} —— 已过期与已退款的卡，倒序。用于「我的卡包」history 列表</li>
 * </ul>
 *
 * <p><b>三类互不重叠</b>：一张卡要么此刻生效（进 active），要么进 history。
 * 前端按字段名分渲染即可，不必自己拿状态和日期去判断某个卡该放哪边 ——
 * 那样等于在客户端再实现一遍免单判定的口径。
 */
@Data
public class CardWalletVo {

    /** 此刻生效的月卡；没有则为 null */
    private MonthlyCardVo active;

    /** 待支付的购买单；没有则为 null */
    private CardPurchaseVo pending;

    /** 已过期与已退款的月卡，按时间倒序 */
    private List<MonthlyCardVo> history;
}
