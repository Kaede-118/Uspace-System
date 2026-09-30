/**
 * 月卡接口（模块 9）。
 *
 * <p>路径前缀 {@code /api/cards}，全部需登录。
 */
import http from './http'

/** 卡种。 */
export const CardType = {
  /** 全天月卡：所有时段免费 */
  ALL_DAY: 'ALL_DAY',
  /** 夜间月卡：仅夜场时段免费 */
  NIGHT: 'NIGHT'
}

/**
 * 查卡种列表。
 *
 * <p>⚠️ <b>价格与时段文案必须用返回值，不要在页面上写死</b>。
 * {@code periodText} 里的「22:00 – 次日 10:00」由后端的
 * {@code uspace.billing.day-start / day-end} 决定，写死会在改配置时对不上，
 * 而且不报任何错。
 *
 * @returns {Promise<{data:Array<{type, typeLabel, price, days, periodText, description}>}>}
 */
export function listCardTypes() {
  return http.get('/api/cards/types')
}

/**
 * 购买月卡（创建购买单，尚未付款）。
 *
 * <p>下单成功后还要走统一支付入口（{@code targetType = 'MONTHLY_CARD'}），
 * 付款成功的<b>同一个事务里走两跳</b>：购买单转已支付 + 发一张卡。
 *
 * <p>卡的有效期是<b>购买日起 30 天</b>（含首尾）。
 *
 * @param {string} cardType 见 {@link CardType}
 * @returns {Promise<{data:{id, orderNo, cardType, price, status}}>} 返回的 id 是购买单 ID
 */
export function purchaseCard(cardType) {
  return http.post('/api/cards/purchases', { cardType })
}

/**
 * 取消未支付的购买单。
 *
 * <p>⚠️ <b>是「取消未支付的购买」，不是退款</b> —— 本期没有退款接口。
 * 月卡是「一人一卡」，未关闭的旧单会把用户自己卡死（想再买一张买不了），
 * 所以必须有这个出口。
 *
 * @param {number|string} id 购买单 ID
 * @returns {Promise}
 */
export function cancelCardPurchase(id) {
  return http.post(`/api/cards/purchases/${id}/cancel`)
}

/**
 * 查我的卡包。
 *
 * <p>返回当前生效的卡（{@code active}）与历史购买记录。
 * 生效中的卡会带 {@code cardType}，前端据此显示「全天月卡 · 生效中」。
 *
 * @returns {Promise<{data:{active:object|null, cards:Array}}>}
 */
export function getMyCards() {
  return http.get('/api/cards/me')
}
