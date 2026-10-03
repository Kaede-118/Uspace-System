package com.kaede.uspace.order.reconcile;

import java.text.Normalizer;

/**
 * 对账用到的文本归一化。
 *
 * <p>纯静态、无状态。与 {@code OcrTextParser} 里那段 NFKC 归一化是同一套理由：
 * <b>用户与机器写出来的同一串数字，字符层面可以完全不同</b> ——
 * 全角的 {@code ４２００}、抄单号时手滑敲进去的空格、从 Excel 复制过来的
 * 零宽字符，肉眼看上去都跟正确的一模一样。
 *
 * <p><b>归一化只用于比对，不用于展示</b>：原始值该留着的地方仍然留原样
 *（{@code BillRecord#rawPaymentNo}、{@code biz_payment_proof.payment_no}）——
 * 归一化过的值只写进 {@code biz_reconcile_diff.payment_no}，那一列的定义就是
 * 「比对用的那个号」。
 *
 * <p><b>两边的归一化必须是同一个函数</b>：账单侧的单号与系统侧凭证上的单号
 * 都走这里，任何一边漏调都会让本来能对上的单子对不上 ——
 * 而表现是「全都是差异」，看不出哪里错了。
 */
public final class ReconcileTexts {

    /**
     * BOM 字符（{@code U+FEFF}）。
     *
     * <p>它本名「零宽不换行空格」，在文件开头就是 BOM。这里再挡一次是<b>第二道防线</b>：
     * {@link BillTextDecoder} 解码时已经按文件头剥过一遍，但一张表里若有人
     * 从 Excel 里复制粘贴过，同样的字符也会出现在单元格中间。
     *
     * <p>写成 {@code (char) 0xFEFF} 而不是字面量：这两个字符<b>在编辑器里是看不见的</b>，
     * 直接嵌进源码的话，下一个人改这一行时完全不知道自己在删什么。
     */
    private static final char BOM = (char) 0xFEFF;

    /** 零宽空格（{@code U+200B}）。从网页或聊天软件里复制文本时常被一并带上 */
    private static final char ZERO_WIDTH_SPACE = (char) 0x200B;

    /**
     * 归一化一个用于比对的字符串。
     *
     * <p>四步：
     * <ol>
     *   <li><b>NFKC</b> —— 全角数字与括号统一成半角。OCR 只认字形不认宽度，
     *       用户手抄时也可能敲成全角</li>
     *   <li><b>去掉所有空白</b> —— 单号常常被抄成 {@code 4200 0012 3420} 这样，
     *       带着空格是对不上的。注意这里去的是<b>所有</b>空白而不只是行内，
     *       因为归一化的对象是「一个单元格」，不存在跨行的概念</li>
     *   <li><b>去掉零宽字符与 BOM 残留</b>（见 {@link #BOM}）——
     *       它们不可见却参与比较，是「看起来一模一样却对不上」的经典成因</li>
     *   <li><b>转大写</b> —— 中文没有大小写；单号含字母时，用户小写、账单大写
     *       会变成一次假的不匹配。两边的归一化是同一份代码，所以这是对称的、安全的</li>
     * </ol>
     *
     * @param value 原始值，可为 null
     * @return 归一化结果；输入为 null、空串或全是空白时返回 {@code null}
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == BOM || c == ZERO_WIDTH_SPACE) {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 去掉首尾空白，空串归一成 null。
     *
     * <p>给「要原样存下来、但空着就不该存空串」的字段用 ——
     * 库里那些可空列上，{@code ''} 与 {@code NULL} 是两种不同的「没有」，
     * 而分辨它们没有任何意义，只会让 SQL 里多写一堆 {@code OR x = ''}。
     *
     * @param value 原始值
     * @return 去空白后的值；原值为 null 或全空白时返回 null
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 按列宽截断。
     *
     * <p>与 {@code OcrTextParser#MAX_TEXT_LENGTH} 同一套做法：在写库前截，
     * 而不是让数据库抛 {@code Data too long} —— 后者会让整条提交失败，
     * 而调用方完全无法自救。
     *
     * @param value 文本，可为 null
     * @param max   最大长度
     * @return 截断后的文本；原值为 null 时返回 null
     */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** 工具类，不实例化 */
    private ReconcileTexts() {
    }
}
