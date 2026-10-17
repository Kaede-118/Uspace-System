package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HelpCardRenderer} 与帮助分页的测试（模块 11）。
 *
 * <p>画面相关的断言一律先看 {@link CardFonts#canRenderChinese()} ——
 * 没装 CJK 字体的机器上渲染返回 null 是设计行为，不是缺陷；
 * 而「分页内容」那些不看画面的断言照常跑。
 */
class HelpCardRendererTests {

    /**
     * 解出图片。调用前必须先确认渲染没返回 null。
     *
     * @param png PNG 字节
     * @return 图片
     * @throws Exception 解码失败
     */
    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    @DisplayName("帮助图：一张 2×2，宽 900")
    void render_hasFixedWidth() throws Exception {
        byte[] png = HelpCardRenderer.render(QqReplyText.helpPages(true, false));
        if (png == null) {
            return;
        }
        BufferedImage image = read(png);
        assertEquals(900, image.getWidth(), "宽度是版式常量，不随内容变");
        assertTrue(image.getHeight() > 600, "四格两行，太矮说明版式塌了：" + image.getHeight());
    }

    @Test
    @DisplayName("帮助图：管理员版更高 —— 管理两条并进常用格")
    void render_adminIsTaller() throws Exception {
        byte[] normal = HelpCardRenderer.render(QqReplyText.helpPages(true, false));
        byte[] admin = HelpCardRenderer.render(QqReplyText.helpPages(true, true));
        if (normal == null || admin == null) {
            return;
        }
        assertTrue(read(admin).getHeight() > read(normal).getHeight(),
                "管理员多两条，第一行该被撑高 —— 否则说明那两条没进图");
    }

    @Test
    @DisplayName("帮助图：页数不是四页时返回 null，由调用方回落文字版")
    void render_rejectsWrongPageCount() {
        assertNull(HelpCardRenderer.render(null), "空参数不能抛异常");
        assertNull(HelpCardRenderer.render(List.of()), "版式写死 2×2，页数不对就不画");
    }

    @Test
    @DisplayName("帮助分页：固定四页，顺序是 常用 / 门店 / 个人信息 / 其他")
    void helpPages_fourInOrder() {
        List<QqReplyText.HelpPage> pages = QqReplyText.helpPages(true, false);
        assertEquals(4, pages.size());
        assertEquals("常用", pages.get(0).title());
        assertEquals("门店", pages.get(1).title());
        assertEquals("个人信息", pages.get(2).title());
        assertEquals("其他", pages.get(3).title());
    }

    @Test
    @DisplayName("帮助分页：只读时常用格只剩两条，并写清写指令为什么没列")
    void helpPages_readOnlyKeepsTwoCommonLines() {
        QqReplyText.HelpPage common = QqReplyText.helpPages(false, false).get(0);
        assertEquals(2, common.lines().size(), "在店与菜单照样发得动，不能一并藏掉");
        assertNotNull(common.note(), "静默少列一段，用户只会以为机器人坏了");
        assertTrue(common.note().contains("写指令未开启"), common.note());
        assertTrue(common.adminLines().isEmpty(), "写开关关掉时管理员也不该看到管理条目");
    }

    @Test
    @DisplayName("帮助分页：管理员两条挂在常用格底部，且带短说明")
    void helpPages_adminLinesAttachedToCommon() {
        QqReplyText.HelpPage common = QqReplyText.helpPages(true, true).get(0);
        assertEquals(2, common.adminLines().size());
        assertEquals("fw可乐5", common.adminLines().get(0).cmd());
        assertNotNull(common.adminLines().get(1).shortDesc(),
                "图上放不下整句（约 22 字），管理员那条必须给短说明");
    }

    @Test
    @DisplayName("帮助分页：其他格贴底写清绑定方式，个人信息格指路网页端")
    void helpPages_notes() {
        List<QqReplyText.HelpPage> pages = QqReplyText.helpPages(true, false);
        assertTrue(pages.get(2).note().contains("网页端"), "账单入口要指出来");
        assertTrue(pages.get(3).note().contains("fw验证"), "绑定方式写在图上最省事");
    }
}
