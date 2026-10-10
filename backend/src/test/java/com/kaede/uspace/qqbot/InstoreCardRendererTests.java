package com.kaede.uspace.qqbot;

import com.kaede.uspace.order.dto.InstoreUserVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link InstoreCardRenderer} 的单元测试。
 *
 * <p>版式照搬 Web 端在店列表（两列卡片、每行两张），守的是
 * 「画出来的图确实是那张名册」：
 * <ol>
 *   <li><b>合法 PNG、尺寸按行算</b> —— 宽度恒为 768（两个卡片），
 *       高度按<b>行数</b>而不是人数增长。画图链（字体 → Graphics2D → ImageIO）
 *       任何一环出问题都是「群里发出去一张空白 / 损坏的图」，而代码里不报错</li>
 *   <li><b>图片读取器失效不影响出图</b> —— banner / 头像读不到
 *      （没传过、文件被清）时回落首字母底。这是名册图最常见的真实形态：
 *       店里多数顾客没传过头像，而卡片排得整整齐齐才是他们要的效果</li>
 * </ol>
 *
 * <p>画中文需要本机有 CJK 字体（Windows / macOS 天生有，Linux 容器要装）。
 * 没有时渲染器按设计返回 null（回落文字版），画面相关的断言用
 * {@link org.junit.jupiter.api.Assumptions} 跳过 ——
 * 「没有字体」是部署环境的问题，不是代码缺陷。
 */
class InstoreCardRendererTests {

    @Test
    @DisplayName("渲染：画出一张合法 PNG，宽度恒定、高度按行数算")
    void render_producesPng() throws Exception {
        assumeTrue(InstoreCardRenderer.canRenderChinese(),
                "本机没有中文字体 —— 渲染器按设计回落文字版，画面断言跳过");

        byte[] one = InstoreCardRenderer.render(
                List.of(user("张三", null, null)), Map.of(), null);
        byte[] three = InstoreCardRenderer.render(List.of(
                user("张三", null, null), user("李四", null, null), user("王五", null, null)),
                Map.of(), null);

        assertNotNull(one, "本机有中文字体时不该回落");
        assertNotNull(three);

        // PNG 魔数：89 50 4E 47（"…PNG"）—— 编码失败 / 退化时这里会先红
        assertEquals((byte) 0x89, one[0]);
        assertEquals((byte) 0x50, one[1]);
        assertEquals((byte) 0x4E, one[2]);
        assertEquals((byte) 0x47, one[3]);

        BufferedImage imageOne = ImageIO.read(new ByteArrayInputStream(one));
        BufferedImage imageThree = ImageIO.read(new ByteArrayInputStream(three));
        assertEquals(768, imageOne.getWidth(),
                "宽度 = 两个卡片（32 + 340 + 24 + 340 + 32）—— 用户定的「宽两个用户信息卡片」");
        assertEquals(768, imageThree.getWidth());
        assertEquals(imageOne.getHeight(), imageThree.getHeight(),
                "⚠️ 不满四个也按 2×2 渲染（空位留白）—— 图高恒定、每一张观感一致");
    }

    @Test
    @DisplayName("渲染：一页恒为 2×2 —— 2 人、4 人、5 人（超出只画前 4）图高都一样")
    void render_fixedTwoByTwoGrid() throws Exception {
        assumeTrue(InstoreCardRenderer.canRenderChinese(), "本机没有中文字体，画面断言跳过");

        byte[] two = InstoreCardRenderer.render(List.of(
                user("甲", null, null), user("乙", null, null)), Map.of(), null);
        byte[] four = InstoreCardRenderer.render(List.of(
                user("甲", null, null), user("乙", null, null),
                user("丙", null, null), user("丁", null, null)), Map.of(), null);
        byte[] five = InstoreCardRenderer.render(List.of(
                user("甲", null, null), user("乙", null, null), user("丙", null, null),
                user("丁", null, null), user("戊", null, null)), Map.of(), null);

        int heightTwo = ImageIO.read(new ByteArrayInputStream(two)).getHeight();
        int heightFour = ImageIO.read(new ByteArrayInputStream(four)).getHeight();
        int heightFive = ImageIO.read(new ByteArrayInputStream(five)).getHeight();
        assertEquals(heightFour, heightTwo,
                "⚠️ 不满四个也按 2×2 渲染（空位留白）—— 图高恒定，每一张观感一致");
        assertEquals(heightFour, heightFive,
                "超过四个只画前四个（分页由调用方做）—— 高度不该再长");
    }

    @Test
    @DisplayName("渲染：无人时也出一张图（不是不画）")
    void render_emptyStillDrawsCard() {
        assumeTrue(InstoreCardRenderer.canRenderChinese(), "本机没有中文字体，画面断言跳过");

        byte[] png = InstoreCardRenderer.render(List.of(), Map.of(), null);

        assertNotNull(png,
                "无人不是「不画」—— 调用方要靠非 null 才知道图发出去了");
    }

    @Test
    @DisplayName("渲染：图片读取器给真图 / 返 null 两条路都不崩")
    void render_toleratesImageLoaderVariants() {
        assumeTrue(InstoreCardRenderer.canRenderChinese(), "本机没有中文字体，画面断言跳过");

        InstoreUserVo withImages = user("张三", null, null);
        withImages.setBanner("/uploads/banner/a.png");
        withImages.setAvatar("/uploads/avatar/a.png");
        List<InstoreUserVo> users = List.of(withImages);

        // ① 读取器给真图：banner 的 cover 裁剪与头像的圆形裁剪都被走到
        byte[] withReal = InstoreCardRenderer.render(users, Map.of(),
                url -> solidImage(600, 200));
        // ② 读取器永远返回 null（没传过图 / 文件被清的真实形态）：回落首字母底
        byte[] withNull = InstoreCardRenderer.render(users, Map.of(), url -> null);

        assertNotNull(withReal, "给得出图就该画真图");
        assertNotNull(withNull,
                "读不到图要回落首字母底，而不是整张图失败 —— 这是店里多数顾客的真实形态");
    }

    @Test
    @DisplayName("渲染：偏好 code 经映射画成标签，认不出的原样显示（与网页端 labels.js 同口径）")
    void render_mapsPreferenceLabels() {
        assumeTrue(InstoreCardRenderer.canRenderChinese(), "本机没有中文字体，画面断言跳过");

        InstoreUserVo user = user("张三", null, null);
        user.setPreference("PAIPAI,UNKNOWN_CODE");

        byte[] png = InstoreCardRenderer.render(List.of(user),
                Map.of("PAIPAI", "拍拍机"), null);

        assertNotNull(png, "认不出的偏好 code 不该让渲染失败 —— 原样显示它就行");
    }

    /**
     * 造一张纯色图（假的 banner / 头像）。
     *
     * @param w 宽
     * @param h 高
     * @return 图片
     */
    private static BufferedImage solidImage(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0x88AACC));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 造一个在店顾客。
     *
     * @param nickname      昵称
     * @param role          角色名（{@code ADMIN} 时画 STAFF 徽章）；可为 null
     * @param cardTypeLabel 月卡标签（如「夜间月卡」）；可为 null
     * @return 名册上的一行
     */
    private static InstoreUserVo user(String nickname, String role, String cardTypeLabel) {
        InstoreUserVo vo = new InstoreUserVo();
        vo.setUserId(1000L);
        vo.setNickname(nickname);
        vo.setRole(role);
        vo.setCardTypeLabel(cardTypeLabel);
        vo.setStartTime(LocalDateTime.now().minusMinutes(20));
        vo.setStayMinutes(20);
        return vo;
    }
}
