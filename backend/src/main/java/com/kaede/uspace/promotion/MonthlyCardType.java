package com.kaede.uspace.promotion;

import com.kaede.uspace.billing.CardScope;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 卡类型。取值与建表脚本里 {@code biz_monthly_card.card_type} 的注释一一对应。
 *
 * <p>三种卡的区别只在<b>覆盖哪些时段</b>与<b>能不能买</b>，价格与有效期天数
 * 都是运营参数，放在 {@link PromotionProperties} 里随时可调。
 *
 * <p><b>覆盖范围为什么不放进配置</b>：那样就允许把「全天卡」配成只免夜场、
 * 或者两张卡配成一模一样的覆盖范围 —— 这种配置没有任何意义，
 * 却会实实在在地卖出去。它是卡种的定义，属于代码事实。
 *
 * <p><b>这里只定义「免哪些时段」，不定义「哪几天免」</b> ——
 * 后者每张卡各不相同，由卡上的生效与失效日期给出，
 * 两者在计费侧合起来才是完整的
 * {@link com.kaede.uspace.billing.CardCoverage 覆盖范围}。
 * 唯一的例外是 {@link #STAFF}：它连日期都没有，覆盖范围就是「所有时段、所有时间」。
 */
public enum MonthlyCardType {

    /** 全天月卡：不限时段，在有效期内订单的所有段都免费 */
    ALL_DAY("全天月卡", CardScope.ALL, true),

    /**
     * 夜间月卡：只覆盖夜场时段。
     *
     * <p>夜场的起止时刻不在这里写死 —— 它沿用
     * {@code uspace.billing.day-start} / {@code day-end}，
     * 与计费本身共用同一套边界。若在别处再定义一次「什么算夜场」，
     * 两套边界迟早漂移，而漂移的后果是顾客账单与月卡权益对不上。
     */
    NIGHT("夜间月卡", CardScope.NIGHT, true),

    /**
     * STAFF 卡：<b>纯状态、只在后台发放</b>，覆盖所有时段 —— 店员自己人玩不计费。
     *
     * <p>它是一条<b>身份标记</b>而不是一次消费，三条特殊性各自对应一处实现：
     * <ul>
     *   <li><b>无法购买</b> —— {@link #isOnSale()} 为 false，在售卡种列表
     *       （{@code MonthlyCardService#cardTypes}）与购买接口都据此拒绝。
     *       让它在商城里出现的话，顾客会买到一张本来只该由店主发出去的卡</li>
     *   <li><b>「无到期日」靠一个远期日期表达</b> —— 库里存
     *       {@link #STAFF_END_DATE}（2099-12-31），而不是把日期列放开为可空。
     *       这是刻意的取舍：把两列改成可空，SQL 的日期条件、{@code CardCoverage}、
     *       计费切段与跳档预告全都得加 null 分支，而那条链路上漏判一处不是
     *       「少免一次」而是 NPE、结不了账。填一个远期日期让这些逻辑
     *       <b>一行不用改</b>，代价只剩展示层：卡包与后台要按卡种显示
     *       「长期有效」，而不是把 2099 与「还剩两万多天」摆给用户看</li>
     *   <li><b>收回靠状态</b> —— 到期日在 2099，实质上是「不会自己结束」，
     *       所以停用（{@link MonthlyCardStatus#REVOKED}）是它唯一的结束方式，
     *       离职、误发都走那里</li>
     * </ul>
     *
     * <p>它与 {@code role = ADMIN} 的「STAFF 徽章」是两回事：徽章表明
     * 「这人在后台有权限」，STAFF 卡表明「这人玩不收钱」—— 店主给自己发卡、
     * 店员没有后台权限，都是常态。两者同名只是因为这个职业称呼本来就只有一个。
     */
    STAFF("STAFF 卡", CardScope.ALL, false);

    /**
     * STAFF 卡的「起始日」—— 一个<b>远在过去的日期</b>，不是开卡当天。
     *
     * <p>⚠️ <b>为什么不能填开卡当日</b>（2026-10-10 晚现场踩到：猫头鹰十七的卡
     * 不显示在在店列表）：在店名册与结算判定月卡用的是<b>订单的开始日期</b>
     * （两者必须同口径，见 {@code InstoreService}），而订单可能是
     * <b>昨天开的、人一直挂到现在</b> —— 拿昨天的日期去查一张今天才开的卡，
     * {@code start_date <= 昨天} 不成立，卡就显示不出来。
     * 典型场景：先开门、后开卡，或跨零点的挂单。填一个比任何订单都早的日期，
     * 这个坑整个不存在。
     *
     * <p>取值 2000-01-01：比系统可能出现的最早订单还早（本项目 2026 年才有数据），
     * 又与 {@link #STAFF_END_DATE} 成对，两个日期合起来表达「一直有效」。
     * 「什么时候开的卡」这个事实不丢 —— 它记在 {@code paid_at}（开卡时刻）里。
     *
     * <p>⚠️ <b>要判断「这是不是 STAFF 卡」一律看 {@code card_type}，不要拿日期比</b> ——
     * 写 {@code startDate.isBefore(2000-01-02)} 这类判断的话，
     * 数据一改就静默失效，而那时没有人会记得这条推断。
     */
    public static final LocalDate STAFF_START_DATE = LocalDate.of(2000, 1, 1);

    /**
     * STAFF 卡的「到期日」—— 一个<b>远期日期</b>，不是真的会到期的日子。
     *
     * <p>取值考虑：离现在足够远（73 年），任何合理经营周期内都不会走到；
     * 又必须是真实存在的日期，好让日期的加一天、比较、切段这些运算
     * 全都照常工作（{@code CardCoverage} 会拿它算次日零点，
     * 2100-01-01 是个正常的日期，不需要任何特判）。
     *
     * <p>⚠️ <b>要判断「这是不是STAFF 卡」一律看 {@code card_type}，不要拿日期比</b> ——
     * 写 {@code endDate.isAfter(2099-12-30)} 这类判断的话，2099 年之后
     * 它会静默开始返回 false，而那时没有人会记得这条推断。
     */
    public static final LocalDate STAFF_END_DATE = LocalDate.of(2099, 12, 31);

    /** 面向用户的中文名称，供前端展示 */
    private final String label;

    /** 该卡种覆盖哪些计费时段，供计费侧判定 */
    private final CardScope scope;

    /** 是否在售。false 的卡种不出现在购买页、购买接口一律拒绝 */
    private final boolean onSale;

    MonthlyCardType(String label, CardScope scope, boolean onSale) {
        this.label = label;
        this.scope = scope;
        this.onSale = onSale;
    }

    /**
     * 取中文名称。
     *
     * @return 卡种的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 取该卡种覆盖的计费时段范围。
     *
     * @return 计费侧的时段范围枚举（不含日期）
     */
    public CardScope getScope() {
        return scope;
    }

    /**
     * 该卡种是否在售（可被用户购买）。
     *
     * <p>写成枚举上的一个字段而不是 {@code type != STAFF} 的判断：
     * 将来再加只在后台发放的卡种时，把它标在定义处，比在每个使用点补一个
     * if 更不容易漏 —— 而漏掉的表现是「顾客能买到一张不该卖的卡」，
     * 系统不会报任何错。
     *
     * @return 在售返回 true
     */
    public boolean isOnSale() {
        return onSale;
    }

    /**
     * 判断一个字符串是否为合法卡种名。
     *
     * <p>用于校验接口入参与库里读出来的历史值 —— 卡种列是 {@code VARCHAR}
     * 而非 {@code ENUM}，库不会替我们挡住非法值。
     *
     * @param name 待校验的卡种名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }

    /**
     * 把卡种名转成中文名称。
     *
     * <p>认不出的名字<b>原样返回</b>，让异常数据在界面上一眼看得出来 ——
     * 伪装成一个正常的中文名称反而会把它藏起来。
     *
     * @param name 卡种名，可为 null
     * @return 中文名称；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
