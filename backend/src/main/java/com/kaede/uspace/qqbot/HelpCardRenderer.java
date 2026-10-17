package com.kaede.uspace.qqbot;

import com.kaede.uspace.qqbot.QqReplyText.HelpLine;
import com.kaede.uspace.qqbot.QqReplyText.HelpPage;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * {@code fw帮助} 的图片渲染（模块 11）—— <b>一张 2×2 的方块图</b>。
 *
 * <p>2026-10-17 由用户要求新增：帮助原本是一屏 20 多行的文本，发到群里就是刷屏；
 * 改成一张图之后，扫一眼就能看出有哪些指令，要细看再点开。
 *
 * <h3>版式</h3>
 *
 * <p>四格固定为「常用 / 门店 / 个人信息 / 其他」（2026-10-17 由用户定的顺序与命名）。
 * 每格：标题 + 分隔线 + 若干条目；条目是两行 ——
 * <b>第一行主命令（粗体主色）+ 别名（灰色小字）</b>，第二行说明（深灰）。
 * 主次靠字号与颜色区分，与名册图是同一条思路（见 {@link InstoreCardRenderer}）。
 *
 * <p><b>管理员的两条并进「常用」格底部</b>，用一条分隔线 + 紫色「仅管理员」小标隔开 ——
 * 只有四格，第五格放不下；而管理员看帮助时最关心的又正是这两条。
 *
 * <p><b>格子等高</b>：同一行的两格取较高者，矮的那格底部留白。
 * 不按内容各自收缩是因为四张卡片对齐才有「方块」的观感；
 * 留下的空白由 {@link HelpPage#note()} 那一行小字贴底补一点内容。
 *
 * <p>⚠️ 2026-10-17 当天试过「拆成左右两张更窄的图」，想让缩略图里的字更大；
 * 但 QQ 对竖长图是按<b>高度</b>反算宽度，拆完每张只显示成约一半屏宽、
 * 两边留白明显，用户最终定回这张 2×2。
 *
 * <h3>失败路径</h3>
 *
 * <p>与名册图一致：<b>本机没有可画中文的字体、或绘制 / 编码抛异常都返回 null</b>，
 * 由调用方回落文字版（{@code QqReplyText#help}）。画图失败只该让这一条指令变回文本，
 * 不该把群消息链路拖垮。
 *
 * <p>纯静态、无 Spring 依赖，可脱离容器单测。
 */
@Slf4j
public final class HelpCardRenderer {

    /** 图片总宽 = 30 + 410 + 20 + 410 + 30 */
    private static final int WIDTH = 900;

    /** 四周留白 */
    private static final int PAGE_PAD = 30;

    /** 单张卡片宽 */
    private static final int CARD_W = 410;

    /** 卡片间距（横与纵同一个值） */
    private static final int CARD_GAP = 20;

    /** 卡片圆角 */
    private static final int CARD_RADIUS = 18;

    /** 卡片内边距（左右） */
    private static final int CARD_PAD = 22;

    /** 分组标题字号 */
    private static final Font TITLE_FONT = font(Font.BOLD, 28);

    /** 主命令字号 */
    private static final Font CMD_FONT = font(Font.BOLD, 20);

    /** 别名字号 */
    private static final Font ALIAS_FONT = font(Font.PLAIN, 15);

    /** 说明字号 */
    private static final Font DESC_FONT = font(Font.PLAIN, 16);

    /** 贴底小字字号 */
    private static final Font NOTE_FONT = font(Font.PLAIN, 14);

    /** 「仅管理员」小标字号 */
    private static final Font ADMIN_TAG_FONT = font(Font.PLAIN, 13);

    /** 一条条目的行高（命令 + 说明两行） */
    private static final int ROW_H = 58;

    /** 页面底色（浅灰紫，衬托白卡片 —— 与名册图同色） */
    private static final Color PAGE_BG = new Color(0xF4F3F7);

    /** 卡片底色 */
    private static final Color CARD_BG = new Color(0xFFFFFF);

    /** 分组标题色 */
    private static final Color TITLE = new Color(0x1F2329);

    /** 主命令色（主色，与网页端同一个色系） */
    private static final Color CMD = new Color(0x4250DB);

    /** 别名色 —— 比说明浅一档，但仍要能在群里压缩后看清 */
    private static final Color ALIAS = new Color(0x606876);

    /** 说明色（比别名深，正文级） */
    private static final Color DESC = new Color(0x3D444D);

    /** 分隔线色 */
    private static final Color DIVIDER = new Color(0xECEFF4);

    /** 标题左侧竖条的主色 */
    private static final Color ACCENT = new Color(0x5B6BF0);

    /** 贴底小字色 */
    private static final Color NOTE = new Color(0x606876);

    /** 「仅管理员」小标色 */
    private static final Color ADMIN_TAG = new Color(0x8A5ADF);

    private HelpCardRenderer() {
    }

    /**
     * 渲染帮助图（一张 2×2 四格）。
     *
     * @param pages 四页：常用 / 门店 / 个人信息 / 其他（见 {@link QqReplyText#helpPages}）
     * @return PNG 字节；本机没有中文字体、页数不是 4、或绘制 / 编码失败时返回 null
     */
    public static byte[] render(List<HelpPage> pages) {
        if (CardFonts.FAMILY == null) {
            log.warn("[QQ机器人] 本机没有可用的中文字体，帮助图回落文字版（部署时请安装 CJK 字体）");
            return null;
        }
        if (pages == null || pages.size() != 4) {
            // 版式写死 2×2：页数不对说明调用方拼错了，宁可回落文字版也不画一张半成品
            log.warn("[QQ机器人] 帮助图的页数不是 4（实际 {}），已回落文字版",
                    pages == null ? 0 : pages.size());
            return null;
        }
        try {
            int row1 = Math.max(pageHeight(pages.get(0)), pageHeight(pages.get(1)));
            int row2 = Math.max(pageHeight(pages.get(2)), pageHeight(pages.get(3)));
            int height = PAGE_PAD + row1 + CARD_GAP + row2 + PAGE_PAD;

            BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                g.setColor(PAGE_BG);
                g.fillRect(0, 0, WIDTH, height);

                int[] rowTops = {PAGE_PAD, PAGE_PAD + row1 + CARD_GAP};
                int[] rowHeights = {row1, row2};
                for (int i = 0; i < 4; i++) {
                    int x = PAGE_PAD + (i % 2) * (CARD_W + CARD_GAP);
                    drawPage(g, pages.get(i), x, rowTops[i / 2], rowHeights[i / 2]);
                }
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("[QQ机器人] 帮助图渲染失败，回落文字版：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 一页（一个格子）需要的高度。
     *
     * <p>条目区从卡片顶部 {@code 96} 处开始（标题与分隔线占掉的那段），
     * 每条 {@link #ROW_H}，最后一条的说明行再占 30；
     * 管理员条目、贴底小字各加一段固定高度。
     *
     * @param page 页面
     * @return 高度像素
     */
    private static int pageHeight(HelpPage page) {
        int items = page.lines().size() + page.adminLines().size();
        int height = 96 + Math.max(0, items - 1) * ROW_H + 30;
        if (!page.adminLines().isEmpty()) {
            height += 46;
        }
        if (page.note() != null) {
            height += 52;
        }
        return height + 20;
    }

    /**
     * 画一格。
     *
     * @param g     画布
     * @param page  页面
     * @param x     左上 x
     * @param y     左上 y
     * @param h     高度（同行两格等高）
     */
    private static void drawPage(Graphics2D g, HelpPage page, int x, int y, int h) {
        g.setColor(CARD_BG);
        g.fill(new RoundRectangle2D.Float(x, y, CARD_W, h, CARD_RADIUS, CARD_RADIUS));

        // 标题：左侧一根主色竖条 + 标题文字（与名册图的「重色块」是同一条视觉语言）
        g.setColor(ACCENT);
        g.fill(new RoundRectangle2D.Float(x + CARD_PAD, y + 26, 5, 30, 4, 4));
        g.setFont(TITLE_FONT);
        g.setColor(TITLE);
        g.drawString(page.title(), x + 40, y + 51);

        g.setColor(DIVIDER);
        g.fillRect(x + CARD_PAD, y + 72, CARD_W - 2 * CARD_PAD, 1);

        int textW = CARD_W - 2 * CARD_PAD;
        int cursor = y + 104;
        for (HelpLine line : page.lines()) {
            drawLine(g, line, x + CARD_PAD, cursor, textW, CMD);
            cursor += ROW_H;
        }

        if (!page.adminLines().isEmpty()) {
            int lineY = cursor - 8;
            g.setColor(DIVIDER);
            g.fillRect(x + CARD_PAD, lineY, CARD_W - 2 * CARD_PAD, 1);
            g.setFont(ADMIN_TAG_FONT);
            g.setColor(ADMIN_TAG);
            g.drawString("仅管理员", x + CARD_PAD, lineY + 20);
            cursor = lineY + 40;
            for (HelpLine line : page.adminLines()) {
                drawLine(g, line, x + CARD_PAD, cursor + 10, textW, ADMIN_TAG);
                cursor += ROW_H;
            }
        }

        if (page.note() != null) {
            int noteY = y + h - 26;
            g.setColor(DIVIDER);
            g.fillRect(x + CARD_PAD, noteY - 26, CARD_W - 2 * CARD_PAD, 1);
            g.setFont(NOTE_FONT);
            g.setColor(NOTE);
            g.drawString(ellipsize(page.note(), g.getFontMetrics(), textW),
                    x + CARD_PAD, noteY);
        }
    }

    /**
     * 画一条条目：主命令 + 别名 + 说明。
     *
     * <p>别名靠右接在主命令后面，放不下就截断（命令本身不截断 —— 它是这一行的主体）；
     * 说明单独一行，太长同样截断。截断是为了不让文字溢出卡片，
     * 完整句子在文字版里（见 {@link QqReplyText#help}）。
     *
     * @param g        画布
     * @param line     条目
     * @param x        左缘
     * @param baseline 命令行的基线
     * @param maxW     可用宽度
     * @param cmdColor 主命令颜色（管理员条目用紫色）
     */
    private static void drawLine(Graphics2D g, HelpLine line, int x, int baseline, int maxW,
                                 Color cmdColor) {
        FontMetrics cmdMetrics = g.getFontMetrics(CMD_FONT);
        FontMetrics aliasMetrics = g.getFontMetrics(ALIAS_FONT);

        String cmd = ellipsize(line.cmd(), cmdMetrics, maxW);
        g.setFont(CMD_FONT);
        g.setColor(cmdColor);
        g.drawString(cmd, x, baseline);
        int cursor = x + cmdMetrics.stringWidth(cmd);

        if (line.alias() != null && !line.alias().isEmpty()) {
            int room = maxW - (cursor - x) - 6;
            if (room > 40) {
                String alias = ellipsize("/ " + line.alias(), aliasMetrics, room);
                g.setFont(ALIAS_FONT);
                g.setColor(ALIAS);
                g.drawString(alias, cursor + 6, baseline - 2);
            }
        }

        String desc = line.shortDesc() != null ? line.shortDesc() : line.desc();
        g.setFont(DESC_FONT);
        g.setColor(DESC);
        g.drawString(ellipsize(desc, g.getFontMetrics(), maxW), x, baseline + 29);
    }

    /**
     * 按像素宽截断文字（超出加省略号）。
     *
     * @param text     原文
     * @param metrics  当前字体的度量
     * @param maxWidth 最大宽度
     * @return 截断后的文字；本来就放得下时原样返回
     */
    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0 || metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /**
     * 按 {字重, 字号} 造字体 —— 字体族取自 {@link CardFonts}。
     *
     * @param style 字重
     * @param size  字号
     * @return 字体
     */
    private static Font font(int style, int size) {
        // 字体族为 null（本机没有 CJK 字体）时退回逻辑字体 —— 这里只是别让静态初始化炸掉；
        // 真正决定「画不画」的是 render 开头的 CardFonts.FAMILY 判空
        return new Font(CardFonts.FAMILY == null ? Font.SANS_SERIF : CardFonts.FAMILY, style, size);
    }
}
