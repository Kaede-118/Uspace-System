package com.kaede.uspace.qqbot;

import java.awt.Font;
import java.util.List;

/**
 * 群内卡片图的字体选择（模块 11）。
 *
 * <p><b>名册图与帮助图共用这一份候选表与探测逻辑</b>：两处各写一份的话，
 * 换台机器部署时会出现「一张图画得出来、另一张全是一屏方框」这种很难联想到原因的现象。
 *
 * <p><b>判定用 {@code canDisplay('在')} 而不是「字体名存不存在」</b>：
 * {@code new Font(不存在的名)} 会返回一个兜底字体而不报错，而那个兜底字体
 * 在没装 CJK 的系统上画不出汉字 —— 只看名字是查不出来的，画出来才发现是一屏方框。
 *
 * <p>纯静态、不依赖 Spring，可脱离容器单测。
 */
final class CardFonts {

    /**
     * 中文字体候选，按平台排列：Windows → macOS → Linux（Noto / 思源 / 文泉驿）。
     *
     * <p>逐个试到 {@code canDisplay('在')} 为真的那个；一个都画不出汉字时
     *（容器里没装任何 CJK 字体）为 null，渲染器据此回落到文字版。
     * 不写死某一个字体名：Windows 上没有 {@code Noto Sans CJK SC}，
     * Linux 容器里也没有 {@code Microsoft YaHei} —— 写死等于换个平台就画不出字。
     */
    static final List<String> CANDIDATES = List.of(
            "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
            "Source Han Sans SC", "WenQuanYi Micro Hei", "SimHei");

    /** 选定并可画汉字的字体名；null 表示本机画不了中文 */
    static final String FAMILY = pick();

    private CardFonts() {
    }

    /**
     * 本机当前能否画出中文 —— 即卡片图会不会真的出图。
     *
     * <p>供单元测试跳过「画面相关」的断言用（没装 CJK 字体的机器上，
     * 渲染返回 null 是设计行为而不是缺陷），部署时也可以拿它自检。
     *
     * @return 能画返回 true
     */
    static boolean canRenderChinese() {
        return FAMILY != null;
    }

    private static String pick() {
        for (String name : CANDIDATES) {
            if (new Font(name, Font.PLAIN, 16).canDisplay('在')) {
                return name;
            }
        }
        return null;
    }
}
