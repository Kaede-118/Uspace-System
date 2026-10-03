package com.kaede.uspace.order.reconcile;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 对账差异的类型。
 *
 * <p><b>声明顺序即优先级</b>，后台列表就按这个顺序排（SQL 里的
 * {@code ORDER BY FIELD(diff_type, ...)} 与它对齐），所以最紧急的那类排在最前。
 * 这条约束由一个单测钉住：{@link #FIELD_ORDER} 必须等于按声明顺序拼出来的字符串。
 * 少了那条用例，改枚举顺序时 SQL 不会跟着变，而<b>排错顺序不会报任何错</b>。
 *
 * <h3>为什么是六类，而不是「对不上就对不上」</h3>
 *
 * <p>因为<b>处置动作完全不同</b>，而管理员打开这一页要的就是「我该做什么」：
 *
 * <ul>
 *   <li>{@link #REJECTED_IN_BILL} —— 回去改复核结论</li>
 *   <li>{@link #DUPLICATE_CLAIM} —— 查这人是不是拿一张截图付了两单</li>
 *   <li>{@link #PROOF_ONLY} —— 找钱（顾客说付了，账上却没有）</li>
 *   <li>{@link #AMOUNT_MISMATCH} —— 查那笔到底收了多少</li>
 *   <li>{@link #BILL_ONLY} —— 找顾客（钱到了，但没人提交凭证）</li>
 *   <li>{@link #NO_PAYMENT_NO} —— 联系顾客补单号，或者干脆不处理</li>
 * </ul>
 *
 * <p>合并成一类的代价很具体：{@code REJECTED_IN_BILL} 会淹进
 * {@code BILL_ONLY} 里，而前者是「系统当前的结论与事实相反」、
 * 后者只是「有人还没交凭证」—— 一屏几十条里混着一条要立刻改结论的，
 * 那条就会被错过。
 */
public enum ReconcileDiffType {

    /**
     * 账单里收到了钱，而系统里那条凭证被驳回了。
     *
     * <p><b>六类里最紧急的一类</b>：它的含义不是「可疑」，而是
     * 「系统当前写着『这笔钱不认』，而账单说钱确实到了」——
     * 一个<b>已知的错误结论</b>，比「疑似作弊」更该先处理。
     */
    REJECTED_IN_BILL("驳回后有款",
            "账单收到了这笔钱，而系统里那条凭证被驳回了。请到付款凭证页处理那条复核结论，本页不代改"),

    /**
     * 同一笔账单被多条凭证认领。
     *
     * <p>一张截图付两单，是纯信任制下最省事的作弊手法，而它留下的痕迹只有这一个。
     * 与凭证复核页的 {@code DUPLICATE_PAYMENT_NO} 风险标记是同一件事的两个视角：
     * 那里是「同一个流水号出现在多条凭证上」，这里是「同一笔钱被多条凭证拿去对账」。
     */
    DUPLICATE_CLAIM("一单多认领",
            "同一笔账单被多条凭证认领 —— 可能是同一张截图用了两次，请核对这两条凭证"),

    /**
     * 系统有凭证、账单里找不到这笔钱。
     *
     * <p>用户说付了，收款账号的账单里却没有 —— 骗钱的嫌疑。
     */
    PROOF_ONLY("凭证无对应账单",
            "系统里有这条凭证，但账单里找不到对应的收款记录。请核对这笔钱是否真的到账"),

    /** 单号对上了，金额对不上 */
    AMOUNT_MISMATCH("金额不符",
            "交易单号对上了，但金额不一致。请核对实际收到了多少"),

    /**
     * 账单里有这笔钱、系统里任何凭证都没认领。
     *
     * <p>常见的成因是顾客付了款却没提交凭证（或者说，线下付了但没人记）。
     */
    BILL_ONLY("账单无对应凭证",
            "账单里有这笔收款，但系统里没有任何凭证认领它。多半是顾客付款后没提交凭证"),

    /**
     * 凭证没填交易单号，无法对账。
     *
     * <p><b>⚠️ 它与 {@link #PROOF_ONLY} 必须分开</b>：那个说的是「有单号但对不上」，
     * 这个说的是「压根没单号」。合成一类会把「用户没填」误报成「用户可能骗钱」，
     * 而流水号本来就是选填的 —— 项目文档里写的原话是「选填，但强烈建议填」。
     *
     * <p>这一类通常不需要当天处理，所以前端默认<b>不把它混进主列表</b>，
     * 而是在类型筛选里单列一个入口。
     */
    NO_PAYMENT_NO("未填流水号",
            "这些凭证没有填写交易单号，无法与账单自动比对。可以联系顾客补填，也可以不处理");

    /**
     * 排差异用的类型清单，供 SQL 的 {@code ORDER BY FIELD(...)} 拼进去。
     *
     * <p><b>必须写成字面量而不是由 {@code values()} 算出来</b>：它要拼进注解里的
     * SQL 字符串，而注解的值要求是编译期常量。写成静态初始化块算出来的值编译不过。
     *
     * <p>代价是它可能与枚举的声明顺序漂移，所以有一条单测专门盯着
     *（{@code ReconcileDiffTypeTests}）—— 而排错顺序不会报任何错，
     * 正是那种必须靠测试才看得见的约束。
     */
    public static final String FIELD_ORDER = "'REJECTED_IN_BILL','DUPLICATE_CLAIM',"
            + "'PROOF_ONLY','AMOUNT_MISMATCH','BILL_ONLY','NO_PAYMENT_NO'";

    /** 面向运营的短标签，列表上的 chip 用它 */
    private final String label;

    /** 给管理员的一句话说明：这类差异意味着什么、该做什么 */
    private final String hint;

    ReconcileDiffType(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /**
     * 取短标签。
     *
     * @return 标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 取给管理员看的说明。
     *
     * @return 说明文本
     */
    public String getHint() {
        return hint;
    }

    /**
     * 判断一个字符串是否为合法取值。
     *
     * @param name 待校验的取值，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }

    /**
     * 把取值转成中文标签。
     *
     * <p><b>认不出的取值原样返回</b>，与 {@code PaymentChannel#userLabelOf} 同一条契约：
     * 库列是 {@code VARCHAR}，可能存着枚举之外的字符串。
     *
     * <p>常量在左以保 null 安全 —— 不要改成 {@code Set.of(...).contains()}。
     *
     * @param name 取值，可为 null
     * @return 中文标签；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return Arrays.stream(values())
                .filter(t -> t.name().equals(name))
                .map(ReconcileDiffType::getLabel)
                .findFirst()
                .orElse(name);
    }

    /**
     * 按声明顺序拼出 {@code ORDER BY FIELD} 需要的字面量（仅供单测比对用）。
     *
     * @return 形如 {@code 'A','B'} 的字符串
     */
    static String declarationOrderLiterals() {
        return Arrays.stream(values())
                .map(t -> "'" + t.name() + "'")
                .collect(Collectors.joining(","));
    }
}
