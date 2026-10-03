/**
 * 计费接口（模块 7）。
 *
 * <p>只有一个端点：价目表。它<b>匿名可访问</b> —— 与门店名、营业状态同理，
 * 价格是走到店门口就该看得见的信息，想进店的人不必先注册。
 *
 * <p>⚠️ <b>页面上的价格数字一律来自这里，不要在前端写死</b>：单价、封顶、
 * 宽限、优惠门槛全是配置项（{@code uspace.billing.*}）。前端抄一份等于把计费规则
 * 复制了一份，调价之后两处必然对不上 —— 而那种不一致<b>不会有任何报错</b>，
 * 只是页面上写着旧价格、账单按新价格收。
 */
import http from './http'

/**
 * 查价目表（计费规则）。
 *
 * <p>⚠️ <b>给的是两套口径，选错了不会报错、只会显示一个错价格</b>：
 * <ul>
 *   <li>{@code dayUnitPrice} 等 —— 「元 / 计费单位」（配置里存的那个数，
 *       画价格阶梯表要用：第 n 档金额 = n × 单价）</li>
 *   <li>{@code dayPricePerHour} 等 —— 「元 / 小时」（展示与那句优惠文案要用）</li>
 * </ul>
 *
 * <p>⚠️ <b>有两个「7 元/小时」不要搞混</b>：优惠后的<b>日间</b>价是 7 元/小时，
 * 而<b>夜间原价</b>也是 7 元/小时 —— 数字一样、含义不同，优惠表里要分行写。
 *
 * @returns {Promise<{data:{
 *   unitMinutes, graceMinutes, dayStart, dayEnd,
 *   dayUnitPrice, nightUnitPrice, dayCap, nightCap,
 *   dayPricePerHour, nightPricePerHour,
 *   discountEnabled, discountThreshold,
 *   discountDayUnitPrice, discountNightUnitPrice, discountDayCap, discountNightCap,
 *   discountDayPricePerHour, discountNightPricePerHour
 * }}>}
 */
export function getBillingRules() {
  return http.get('/api/billing/rules')
}
