package com.kaede.uspace.qqbot;

import com.kaede.uspace.order.dto.InstoreUserVo;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 在店名册的图片渲染（模块 11）。
 *
 * <p><b>版式照搬 Web 端「在店用户」页</b>（{@code InstoreView.vue} 的两列卡片栅格，
 * 2026-10-10 由用户定）：「宽两个用户信息卡片」，每张卡片的六行结构与
 * {@code UserCard.vue} 一致 ——
 * banner / 头像 · STAFF · 昵称 / 偏好标签 / 到店时刻 / 在店时长 / 月卡标签。
 * 群里看到的与网页上看到的是同一张名册，只是载体从 DOM 换成了像素。
 *
 * <p><b>尺寸是网页端的等比放大</b>：网页卡片 160px 宽、内容 328px；
 * 图上卡片 340px 宽、总宽 768px —— 手机上点开清晰，又不至于大到被群里压缩。
 *
 * <p><b>每张图固定 2×2 四个卡片，人数多了由调用方分页连发</b>
 * （2026-10-10 由用户定：每四人一张图）。人数不满四个也按 2×2 渲染、
 * 空位留白 —— 图高恒定、版面观感一致；<b>图内不写人数</b>，
 * 「店内目前有 X 人」由第一张图前的那句话负责（见 {@code QqCommandService}）。
 *
 * <p><b>取值放大而非照抄</b>：字号、圆角、间距都按约 2.1 倍的观感取整，
 * 不是严格乘 2.1 —— 与 {@code UserCard.vue} 里「roomy 档不是等比例放大」
 * 是同一条理由：等比例放大在小尺寸上正好、在大尺寸上会散。
 *
 * <p><b>头图与头像真的会去读本机文件</b>（{@link ImageLoader}，
 * 由调用方用上传目录装配）：用户传过 banner / 头像就画真的，
 * 读不到（没传、文件被清、非本站路径）回落<b>首字母底</b> ——
 * 与网页端的兜底形态一致，绝不会是破图。
 *
 * <p><b>两条失败路径都回落到文字版</b>（本类只如实返回 null，由调用方回落）：
 * <ol>
 *   <li><b>本机没有可画中文的字体</b> —— 容器镜像里没装 CJK 字体时，
 *       Java2D 会拿一个画不出汉字的兜底字体硬画，结果是一屏方框而不是报错。
 *       所以这里先探测字体（{@code canDisplay('在')}），探不到就干脆不画
 *      （部署时记得装 CJK 字体，见 {@code docs/Docker化与云部署草案.md}）</li>
 *   <li><b>绘制或编码抛异常</b> —— 记 warn 返回 null，绝不让一把画刷
 *       把一条群指令拖垮</li>
 * </ol>
 *
 * <p>纯静态、无 Spring 依赖（与 {@link QqReplyText} 同一性质），可脱离容器单测。
 */
@Slf4j
public final class InstoreCardRenderer {

    /** 图片总宽 = 32 + 340 + 24 + 340 + 32 —— 与 {@code InstoreView.vue} 同为两列 */
    private static final int WIDTH = 768;

    /** 四周留白 */
    private static final int PAGE_PAD = 32;

    /** 单张卡片宽 —— 网页端 160px 的放大档 */
    private static final int CARD_W = 340;

    /** 卡片间距（横与纵同一个值） */
    private static final int CARD_GAP = 24;

    /** 卡片高。固定值：并排卡片等高是名册「扫一眼就能比」的前提（同网页端 min-height 的用意） */
    private static final int CARD_H = 360;

    /** 卡片圆角 */
    private static final int CARD_RADIUS = 16;

    /** banner 高 —— 3:1，与网页端 {@code UserCard} 的比例一致 */
    private static final int BANNER_H = 114;

    /** 头像直径 */
    private static final int AVATAR_D = 52;

    /**
     * 每张图固定画的卡片数：2×2 四个（2026-10-10 由用户定，每四人一张图）。
     *
     * <p>人数不满四个也按 2×2 的网格渲染（空位留白）—— 图高因此恒定、
     * 每一张的版面观感一致；超过四个由调用方切页连发。
     */
    private static final int CARDS_PER_PAGE = 4;

    /** 卡片底色 */
    private static final Color CARD_BG = new Color(0xFFFFFF);

    /** 页面底色（浅灰紫，衬托白卡片） */
    private static final Color PAGE_BG = new Color(0xF4F3F7);

    /** 主色（首字母、强调元素） */
    private static final Color ACCENT = new Color(0x5B6BF0);

    /** 主色浅底（头像兜底圆、banner 兜底底） */
    private static final Color ACCENT_PALE = new Color(0xE9EBFE);

    /** 正文色 */
    private static final Color TEXT = new Color(0x323842);

    /** 次要文字色（到店/在店两行、还有 N 人） */
    private static final Color MUTED = new Color(0x8A9099);

    /** 中性标签底（偏好胶囊） */
    private static final Color TAG_BG = new Color(0xEEEDF3);

    /** 标签文字色 */
    private static final Color TAG_TEXT = new Color(0x5D5476);

    /** STAFF 徽章底色 —— 比主色系的绿浅一档（与网页端 {@code .badge-staff} 同一取值） */
    private static final Color STAFF_BG = new Color(0x5FC08F);

    /** 月卡胶囊底色（浅绿） */
    private static final Color CARD_TAG_BG = new Color(0xE4F6EC);

    /** 月卡胶囊文字色（深绿） */
    private static final Color CARD_TAG_TEXT = new Color(0x2F9E68);

    /** 头像兜底圆的浅底（比主色淡的更中性一点） */
    private static final Color ICON_BG = new Color(0xEDECF3);

    /**
     * 中文字体候选，按平台排列：Windows → macOS → Linux（Noto / 思源 / 文泉驿）。
     *
     * <p>逐个试到 {@code canDisplay('在')} 为真的那个；一个都画不出汉字时
     *（容器里没装任何 CJK 字体）为 null，渲染直接回落文字版。
     * 不写死某一个字体名：Windows 上没有 {@code Noto Sans CJK SC}，
     * Linux 容器里也没有 {@code Microsoft YaHei} —— 写死等于换个平台就画不出字。
     */
    private static final List<String> FONT_CANDIDATES = List.of(
            "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
            "Source Han Sans SC", "WenQuanYi Micro Hei", "SimHei");

    /** 选定并可画汉字的字体名；null 表示本机画不了中文 */
    private static final String FONT_FAMILY = pickFontFamily();

    private InstoreCardRenderer() {
    }

    /**
     * 本地图片读取器 —— 把头像 / banner 的站内相对路径变成本机图片。
     *
     * <p>做成接口而不是让渲染器自己认识上传目录：纯静态类不该依赖
     * {@code UploadProperties}；而「怎么读」与「怎么画」分开之后，
     * 单测可以塞一个假读取器，不必真的往磁盘上放图。
     */
    @FunctionalInterface
    public interface ImageLoader {

        /**
         * 读一张图。
         *
         * @param url 站内相对路径（如 {@code /uploads/avatar/xxx.png}）
         * @return 图片；读不到时返回 null（渲染回落到首字母底）
         */
        BufferedImage load(String url);
    }

    /**
     * 渲染一张在店名册卡片图（一页 2×2 四个卡片）。
     *
     * @param users            本页的顾客（调用方每 4 人切一页），按进店时刻升序
     *                         （与网页端、文字版同一数据源）；超过 4 个只画前 4 个
     * @param preferenceLabels 偏好 code → 中文名
     * @param imageLoader      头像 / banner 的读取器；传 null 时全部走首字母底
     * @return PNG 字节；本机没有中文字体、或绘制 / 编码失败时返回 null
     */
    public static byte[] render(List<InstoreUserVo> users, Map<String, String> preferenceLabels,
                                ImageLoader imageLoader) {
        if (FONT_FAMILY == null) {
            log.warn("[QQ机器人] 本机没有可用的中文字体，名册图回落文字版（部署时请安装 CJK 字体）");
            return null;
        }
        try {
            List<InstoreUserVo> shown = users == null ? List.of() : users;
            int listed = Math.min(shown.size(), CARDS_PER_PAGE);

            // 固定 2 行：不满四个也按 2×2 渲染、空位留白 —— 图高恒定，观感一致
            int height = PAGE_PAD + 2 * CARD_H + CARD_GAP + PAGE_PAD;
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

                for (int i = 0; i < listed; i++) {
                    int col = i % 2;
                    int row = i / 2;
                    int x = PAGE_PAD + col * (CARD_W + CARD_GAP);
                    int y = PAGE_PAD + row * (CARD_H + CARD_GAP);
                    drawCard(g, shown.get(i), preferenceLabels, imageLoader, x, y);
                }
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            // 画图失败只该让这一条指令回落文字，不该把群消息链路拖垮
            log.warn("[QQ机器人] 名册图渲染失败，回落文字版：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 画一张用户卡片（六行结构见类注释）。
     *
     * <p>整张卡片先设圆角裁剪、再往里画 —— banner 因此天然带上圆角，
     * 不必单独处理「上圆下直」的拼接。
     *
     * @param g                画布
     * @param user             这一行的顾客
     * @param labels           偏好中文名映射
     * @param loader           图片读取器，可为 null
     * @param x                卡片左上角 x
     * @param y                卡片左上角 y
     */
    private static void drawCard(Graphics2D g, InstoreUserVo user, Map<String, String> labels,
                                 ImageLoader loader, int x, int y) {
        Shape cardShape = new RoundRectangle2D.Float(x, y, CARD_W, CARD_H,
                CARD_RADIUS, CARD_RADIUS);
        Shape oldClip = g.getClip();
        g.setClip(cardShape);
        g.setColor(CARD_BG);
        g.fill(cardShape);

        String name = QqReplyText.displayName(user.getNickname(), user.getUserId());
        String initial = name.substring(0, 1);

        // 第 1 行：banner（3:1）。有图用图、没有用浅底 + 大首字母（与网页端同一形态）
        BufferedImage banner = loader == null ? null : loader.load(user.getBanner());
        if (banner != null) {
            drawCover(g, banner, x, y, CARD_W, BANNER_H);
        } else {
            g.setColor(ACCENT_PALE);
            g.fillRect(x, y, CARD_W, BANNER_H);
            g.setColor(withAlpha(ACCENT, 70));
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, 52));
            drawCentered(g, initial, x + CARD_W / 2, y + BANNER_H / 2);
        }

        // 第 2 行：头像 · STAFF · 昵称
        int identityY = y + BANNER_H;
        int avatarX = x + 18;
        int avatarY = identityY + 10;
        drawAvatar(g, loader == null ? null : loader.load(user.getAvatar()),
                avatarX, avatarY, initial);

        Font nameFont = new Font(FONT_FAMILY, Font.BOLD, 24);
        FontMetrics nameMetrics = g.getFontMetrics(nameFont);
        int avatarCenterY = avatarY + AVATAR_D / 2;
        int cursor = avatarX + AVATAR_D + 12;
        if ("ADMIN".equals(user.getRole())) {
            cursor = drawStaffBadge(g, cursor, avatarCenterY - 12) + 10;
        }
        g.setFont(nameFont);
        g.setColor(TEXT);
        int nameMax = x + CARD_W - 18 - cursor;
        g.drawString(ellipsize(name, nameMetrics, nameMax), cursor,
                avatarCenterY + (nameMetrics.getAscent() - nameMetrics.getDescent()) / 2);

        // 第 3 行：偏好标签（每个一枚胶囊）
        int tagY = identityY + 84;
        int tagCursor = x + 18;
        for (String tag : preferenceTags(user.getPreference(), labels)) {
            tagCursor = drawPill(g, tag, tagCursor, tagY, TAG_BG, TAG_TEXT) + 8;
            if (tagCursor > x + CARD_W - 50) {
                break;
            }
        }

        // 第 4、5 行：到店时刻 / 在店时长（与网页端的两行各占一行一致）
        g.setColor(MUTED);
        g.setFont(new Font(FONT_FAMILY, Font.PLAIN, 19));
        int infoY = tagY + 62;
        g.drawString(arrivalText(user.getStartTime()), x + 18, infoY);
        g.drawString("在店 " + durationText(user.getStayMinutes()), x + 18, infoY + 30);

        // 第 6 行：月卡标签（放最下面，与网页端一致）
        if (user.getCardTypeLabel() != null && !user.getCardTypeLabel().isBlank()) {
            drawPill(g, user.getCardTypeLabel(), x + 18, y + CARD_H - 56,
                    CARD_TAG_BG, CARD_TAG_TEXT);
        }

        g.setClip(oldClip);
    }

    /**
     * 画 STAFF 徽章（绿底白字小圆角），返回徽章右缘。
     *
     * @param g  画布
     * @param x  徽章左缘
     * @param y  徽章上缘
     * @return 徽章右缘的 x
     */
    private static int drawStaffBadge(Graphics2D g, int x, int y) {
        Font font = new Font(FONT_FAMILY, Font.BOLD, 15);
        FontMetrics metrics = g.getFontMetrics(font);
        int w = metrics.stringWidth("STAFF") + 16;
        g.setColor(STAFF_BG);
        g.fillRoundRect(x, y, w, 24, 8, 8);
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString("STAFF", x + 8, y + 18);
        return x + w;
    }

    /**
     * 画一枚胶囊标签（偏好 / 月卡共用），返回胶囊右缘。
     *
     * @param g      画布
     * @param text   文字
     * @param x      左缘
     * @param y      上缘
     * @param bg     底色
     * @param color  文字色
     * @return 胶囊右缘的 x
     */
    private static int drawPill(Graphics2D g, String text, int x, int y, Color bg, Color color) {
        Font font = new Font(FONT_FAMILY, Font.PLAIN, 18);
        FontMetrics metrics = g.getFontMetrics(font);
        int w = Math.min(metrics.stringWidth(text) + 24, 180);
        g.setColor(bg);
        g.fillRoundRect(x, y, w, 34, 17, 17);
        g.setFont(font);
        g.setColor(color);
        g.drawString(ellipsize(text, metrics, w - 20), x + 12, y + 24);
        return x + w;
    }

    /**
     * 画圆形头像（白描边），读不到图时回落浅底 + 首字母。
     *
     * @param g       画布
     * @param avatar  头像图，可为 null
     * @param x       圆心左上 x
     * @param y       圆心左上 y
     * @param initial 首字母（兜底用）
     */
    private static void drawAvatar(Graphics2D g, BufferedImage avatar, int x, int y,
                                   String initial) {
        Ellipse2D circle = new Ellipse2D.Float(x, y, AVATAR_D, AVATAR_D);
        Shape oldClip = g.getClip();
        g.setClip(circle);
        if (avatar != null) {
            drawCover(g, avatar, x, y, AVATAR_D, AVATAR_D);
        } else {
            g.setColor(ICON_BG);
            g.fill(circle);
            g.setColor(ACCENT);
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, 20));
            drawCentered(g, initial, x + AVATAR_D / 2, y + AVATAR_D / 2);
        }
        g.setClip(oldClip);
        // 白描边让它从 banner 上「浮」起来（同网页端的 border: 2px solid #fff）
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Float(x + 1.5f, y + 1.5f, AVATAR_D - 3, AVATAR_D - 3));
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * 按 cover 方式画图（裁剪填满目标区域，不留白、不变形）。
     *
     * <p><b>超宽图取右侧</b> —— 与网页端 {@code object-position: right center}
     * 是同一条规则：顾客传的多半是舞萌 DX 姓名框素材，右侧才是要看的内容。
     *
     * @param g  画布
     * @param img 源图
     * @param x  目标左上 x
     * @param y  目标左上 y
     * @param w  目标宽
     * @param h  目标高
     */
    private static void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double scale = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int srcW = Math.min(img.getWidth(), (int) Math.round(w / scale));
        int srcH = Math.min(img.getHeight(), (int) Math.round(h / scale));
        int srcX = img.getWidth() - srcW;
        int srcY = (img.getHeight() - srcH) / 2;
        g.drawImage(img, x, y, x + w, y + h, srcX, srcY, srcX + srcW, srcY + srcH, null);
    }

    /**
     * 水平居中地画一行字（首字母用）。
     *
     * @param g     画布
     * @param text  文字
     * @param cx    中心 x
     * @param cy    中心 y
     */
    private static void drawCentered(Graphics2D g, String text, int cx, int cy) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, cx - metrics.stringWidth(text) / 2,
                cy + (metrics.getAscent() - metrics.getDescent()) / 2);
    }

    /**
     * 按像素宽截断文字（超出加省略号）。
     *
     * @param text    原文
     * @param metrics 当前字体的度量
     * @param maxWidth 最大宽度
     * @return 截断后的文字；本来就放得下时原样返回
     */
    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
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
     * 偏好 code 串 → 中文标签列表。
     *
     * <p>与网页端 {@code utils/labels.js#preferenceLabels} 同口径：
     * 认不出的 code 原样显示（宁可露出一个英文词，也不静默吞掉）。
     *
     * @param preference 逗号分隔的 code 串，可为 null
     * @param labels     code → 中文名
     * @return 标签列表；没有偏好时为空列表
     */
    private static List<String> preferenceTags(String preference, Map<String, String> labels) {
        if (preference == null || preference.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(preference.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> labels == null ? code : labels.getOrDefault(code, code))
                .limit(3)
                .toList();
    }

    /**
     * 到店时刻文案。
     *
     * <p>当天只报 {@code HH:mm}，跨天带上「昨天 / 前天 / M月d日」——
     * 与网页端 {@code formatArrivalTime} 同一个用意：昨晚进店的人
     * 在名册上不能看起来像今天凌晨来的。
     *
     * @param startTime 进店时刻，可为 null
     * @return 文案；缺失时返回「到店时间未知」
     */
    private static String arrivalText(LocalDateTime startTime) {
        if (startTime == null) {
            return "到店时间未知";
        }
        String clock = startTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        long days = Duration.between(startTime.toLocalDate().atStartOfDay(),
                LocalDate.now().atStartOfDay()).toDays();
        if (days <= 0) {
            return clock + " 到店";
        }
        if (days == 1) {
            return "昨天 " + clock + " 到店";
        }
        if (days == 2) {
            return "前天 " + clock + " 到店";
        }
        return startTime.toLocalDate().getMonthValue() + "月"
                + startTime.toLocalDate().getDayOfMonth() + "日 " + clock + " 到店";
    }

    /**
     * 在店时长文案。
     *
     * <p>复用 {@link QqReplyText#duration} —— 与文字版、QQ 播报同一个说法，
     * 各写一份的话「1 小时 20 分」会在两处长得不一样。
     *
     * @param stayMinutes 在店分钟数，可为 null
     * @return 文案
     */
    private static String durationText(Integer stayMinutes) {
        if (stayMinutes == null || stayMinutes <= 0) {
            return "刚进店";
        }
        return QqReplyText.duration(stayMinutes);
    }

    /**
     * 带透明度的颜色（首字母底用）。
     *
     * @param color 原色
     * @param alpha 透明度 0~255
     * @return 新颜色
     */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 本机当前能否画出中文 —— 即 {@link #render} 会不会真的出图。
     *
     * <p>供单元测试跳过「画面相关」的断言用（没装 CJK 字体的机器上，
     * 渲染返回 null 是设计行为而不是缺陷），部署时也可以拿它自检。
     *
     * @return 能画返回 true
     */
    static boolean canRenderChinese() {
        return FONT_FAMILY != null;
    }

    /**
     * 造一个从本机上传目录读图的 {@link ImageLoader}。
     *
     * <p>路径来自数据库里的站内相对路径（{@code /uploads/…}）：
     * 只认配置的那个前缀，落点必须仍在上传目录之内 —— 与
     * {@code ImageStorage} 删旧图时那套 {@code normalize + startsWith}
     * 防御同源，这里防的是同一条：库里要是混进一条 {@code ../} 开头的脏路径，
     * 渲染器就成了任意文件读取器。
     *
     * <p>读不到（文件不存在、不是图片、路径非法）一律返回 null ——
     * 由渲染回落到首字母底，不去打扰调用方。
     *
     * @param uploadDir 上传根目录（{@code uspace.upload.dir}）
     * @param urlPrefix 站内 URL 前缀（{@code uspace.upload.url-prefix}，如 {@code /uploads}）
     * @return 读取器
     */
    public static ImageLoader localFileLoader(Path uploadDir, String urlPrefix) {
        Path root = uploadDir == null ? null : uploadDir.toAbsolutePath().normalize();
        String prefix = (urlPrefix == null || urlPrefix.isBlank() ? "" : urlPrefix) + "/";
        return url -> {
            if (root == null || url == null || !url.startsWith(prefix)) {
                return null;
            }
            Path target = root.resolve(url.substring(prefix.length())).normalize();
            if (!target.startsWith(root)) {
                log.warn("[QQ机器人] 名册图的图片路径越界，已忽略 url={}", url);
                return null;
            }
            try {
                return ImageIO.read(target.toFile());
            } catch (Exception e) {
                // 读不到不是错误：没传过头像、文件被清、格式坏了都会走到这里
                return null;
            }
        };
    }

    /**
     * 从候选里挑一个能画汉字的字体。
     *
     * <p>判定用 {@code canDisplay('在')} 而不是「字体名存不存在」：
     * {@code new Font(不存在的名)} 会返回一个兜底字体而不报错，
     * 而那个兜底字体在没装 CJK 的系统上画不出汉字 ——
     * 只看名字是查不出来的，画出来才发现是一屏方框。
     *
     * @return 可用的字体名；一个都没有时返回 null
     */
    private static String pickFontFamily() {
        for (String name : FONT_CANDIDATES) {
            if (new Font(name, Font.PLAIN, 16).canDisplay('在')) {
                return name;
            }
        }
        return null;
    }
}
