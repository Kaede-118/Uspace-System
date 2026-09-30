/**
 * 优惠管理（模块 9）。
 *
 * <p><b>本模块负责「用户享不享得了优惠」与优惠的发放</b>，两条线并行存在、互不叠加：
 * <ul>
 *   <li><b>月度累计消费优惠</b> —— 当月已支付订单实付额达到门槛后，本单整单走优惠价。
 *       优惠的<b>计算规则</b>（原价与优惠价两套单价、两套封顶）在模块 7 的
 *       {@code BillingService}，本模块负责查出「当月累计了多少」并交给它判定；
 *       门槛与优惠价本身是配置项（{@code uspace.billing.monthly-discount.*}）</li>
 *   <li><b>月卡</b> —— 全天卡 600 元 / 夜间卡 320 元，按购买日起 30 天计。
 *       购买走模块 8 的支付服务（<b>收款归模块 8</b>，与包场同理），
 *       生效后由计费侧逐段免费：全天卡覆盖所有段，夜间卡只覆盖夜场段</li>
 * </ul>
 *
 * <p>月卡拆成两张表：{@code biz_monthly_card}（卡，是资产）与
 * {@code biz_monthly_card_order}（购买单，是交易）——理由见建表脚本里的说明。
 *
 * <p>与订单模块的依赖是单向的 {@code order → promotion}：订单结算时调本模块查月卡覆盖，
 * 本模块不反向引用订单。支付目标处理器 {@code MonthlyCardPaymentTargetHandler}
 * 因此住在 order 包（与包场处理器同理），它操作本模块的表，但代码归属收款方。
 *
 * <p>对应毕业论文中的「优惠管理」章节。
 */
package com.kaede.uspace.promotion;
