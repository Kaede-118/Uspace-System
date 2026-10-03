package com.kaede.uspace.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 头像 / Banner 上传的端到端集成测试：<b>从 HTTP 入口一直验到浏览器能不能把图取回来</b>。
 *
 * <p><b>为什么单测之外还必须有它</b>：这条链路要三处配置同时到位才能工作，
 * 而<b>三处漏任何一处都不会有任何报错</b>：
 *
 * <table border="1">
 *   <caption>本测试专门覆盖的三个「漏了不报错」的地方</caption>
 *   <tr><th>漏掉的地方</th><th>症状</th><th>哪个用例盯着它</th></tr>
 *   <tr>
 *     <td>{@code WebMvcConfig} 的资源映射</td>
 *     <td>图片 404，后端日志里一行都没有</td>
 *     <td>{@code uploadedImage_isServedAnonymously}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code SecurityConfig.PUBLIC_PATHS} 里的 {@code /uploads/**}</td>
 *     <td><b>页面上头像全裂</b>，而日志里看不到 —— 图是
 *         {@code <img src>} 加载的，浏览器不会为图片请求带 {@code Authorization} 头</td>
 *     <td>同上（该用例刻意<b>不带</b> Authorization 头）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GlobalExceptionHandler} 的上传超限分支</td>
 *     <td>超限返回 500「服务异常，请稍后重试」，而事实是「你的图太大了」</td>
 *     <td>{@code oversizedUpload_returns413}（走 Spring 的 multipart 闸门那一条路）</td>
 *   </tr>
 * </table>
 *
 * <p>落盘目录被 {@code @TestPropertySource} 指到 {@code target/test-uploads}，
 * 不污染开发时手工上传的 {@code backend/uploads/}。数据库改动由
 * {@code @Transactional} 回滚（文件不回滚，但它们在 {@code target/} 里，
 * {@code mvn clean} 一并清掉）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "uspace.upload.dir=target/test-uploads")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class ImageUploadIntegrationTests {

    /** 测试用户名前缀，避免与开发库中的真实数据混淆 */
    private static final String PREFIX = "iit_";

    /** 测试密码，满足 8 位下限 */
    private static final String PASSWORD = "Test@1234";

    /** 合法的 PNG 文件头 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** 合法的 JPEG 文件头（含 JFIF 标识），用于「换格式」那条用例 */
    private static final byte[] JPEG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x02, 0x03};

    /** 上传目录，与 {@code @TestPropertySource} 里的一致 */
    private static final String UPLOAD_DIR = "target/test-uploads";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    /** 注册流程要消费一次群内验证，见 {@link #register} */
    @Autowired
    private QqVerifyService qqVerifyService;

    /**
     * 自动生成的 QQ 号序号。
     *
     * <p>同一个用例里可能注册多个用户，而 {@code uk_qq} 是唯一键 —— 每次都要一个新的。
     * 用递增而不是随机：随机数偶尔会撞，而那种失败是「跑十次错一次」的，最难查。
     */
    private final AtomicInteger qqSequence = new AtomicInteger(77000000);

    // ==================================================================
    // 主链路
    // ==================================================================

    @Test
    @DisplayName("上传头像 → 图片能被匿名取回，且内容就是上传的那份")
    void uploadThenFetch_succeeds() throws Exception {
        register(USER("happy"));
        String token = login(USER("happy"));

        String url = uploadAvatar(token, "photo.png", "image/png", PNG_BYTES);
        assertTrue(url.startsWith("/uploads/avatar/"), "实际返回：" + url);

        // ⚠️ 关键：这里【不带】Authorization 头。
        // PUBLIC_PATHS 漏了 /uploads/** 的话，这一步会拿到 401 而不是图片 ——
        // 而真实场景里浏览器加载 <img src> 正是这么请求的
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));

        assertEquals(url, userMapper.selectByUsername(USER("happy")).getAvatar(),
                "新路径必须写回库");
    }

    @Test
    @DisplayName("上传 Banner：写 banner 列，avatar 不受影响")
    void uploadBanner_writesOnlyBannerColumn() throws Exception {
        register(USER("banner"));
        String token = login(USER("banner"));

        uploadAvatar(token, "a.png", "image/png", PNG_BYTES);
        String avatarBefore = userMapper.selectByUsername(USER("banner")).getAvatar();

        String banner = uploadBanner(token, "b.png", "image/png", PNG_BYTES);

        SysUser loaded = userMapper.selectByUsername(USER("banner"));
        assertTrue(banner.contains("/uploads/banner/"));
        assertEquals(avatarBefore, loaded.getAvatar(), "换背景图不该顺手把头像写一遍");
        mockMvc.perform(get(banner)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("重传同一格式：原地覆盖，别把自己删掉")
    void replacingAvatar_sameFormat_overwritesInPlace() throws Exception {
        register(USER("replace"));
        String token = login(USER("replace"));

        String first = uploadAvatar(token, "a.png", "image/png", PNG_BYTES);
        String second = uploadAvatar(token, "b.png", "image/png", PNG_BYTES);

        // 文件名固定成 {用户名}_{ID}.{ext}，同格式重传落在同一个路径上。
        // 此时若还去「删旧图」，删掉的就是刚写进去的那张 —— 接口返回 200，
        // 而用户既没有新图也没有旧图
        assertEquals(first, second, "同格式重传应当落在同一个路径上");
        assertTrue(Files.exists(onDisk(second)), "覆盖之后文件必须还在");
        mockMvc.perform(get(second)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("换格式（png → jpg）：旧格式的文件被清掉，磁盘不越用越多")
    void replacingAvatar_differentFormat_deletesOldFile() throws Exception {
        register(USER("reformat"));
        String token = login(USER("reformat"));

        String png = uploadAvatar(token, "a.png", "image/png", PNG_BYTES);
        String jpg = uploadAvatar(token, "b.jpg", "image/jpeg", JPEG_BYTES);

        assertFalse(Files.exists(onDisk(png)),
                "新图落在另一个路径上，旧格式的文件必须清掉，否则磁盘越用越多");
        assertTrue(Files.exists(onDisk(jpg)));
        mockMvc.perform(get(png)).andExpect(status().isNotFound());
        mockMvc.perform(get(jpg)).andExpect(status().isOk());
    }

    // ==================================================================
    // 拒绝路径
    // ==================================================================

    @Test
    @DisplayName("伪装成 PNG 的 JSP：400 与 UPLOAD_FILE_INVALID，且不产生文件")
    void fakeImage_returns400() throws Exception {
        register(USER("fake"));
        String token = login(USER("fake"));

        byte[] jsp = "<%@ page import=\"java.util.*\" %>".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/user/me/avatar")
                        .file(new MockMultipartFile("file", "shell.png", "image/png", jsp))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        assertNull(userMapper.selectByUsername(USER("fake")).getAvatar(), "被拒绝时不能写库");
    }

    @Test
    @DisplayName("超过 multipart 上限：413 与 UPLOAD_FILE_TOO_LARGE，而不是 500")
    void oversizedUpload_returns413() throws Exception {
        register(USER("huge"));
        String token = login(USER("huge"));

        // 3MB > application.properties 里配的 2MB。超限由 Spring 在解析 multipart 时拦下，
        // 走的是 GlobalExceptionHandler 的 MaxUploadSizeExceededException 分支 ——
        // 那个分支漏了的后果是返回 500「服务异常，请稍后重试」，
        // 让用户去重试一件永远不会成功的事
        mockMvc.perform(multipart("/api/user/me/avatar")
                        .file(new MockMultipartFile("file", "huge.png", "image/png", new byte[3 * 1024 * 1024]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(41300));
    }

    @Test
    @DisplayName("请求里没带 file 部分：400，而不是 500")
    void missingFilePart_returns400() throws Exception {
        register(USER("nofile"));
        String token = login(USER("nofile"));

        // Controller 把 file 声明为可选就是为了这一条：声明为必填时 Spring 抛的
        // MissingServletRequestPartException 不在全局异常处理器名单里，会变成 500
        mockMvc.perform(multipart("/api/user/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("用普通 POST（压根不是 multipart）调用：400，而不是 500")
    void nonMultipartRequest_returns400() throws Exception {
        register(USER("plain"));
        String token = login(USER("plain"));

        // ⚠️ 这条用例是【真机实测】之后补回来的，来龙去脉值得记一笔：
        // MockMvc 的 multipart() 构造器【永远】会造出一个合法的 multipart 请求，
        // 因此它走不到「压根不是 multipart」这条路上 —— 于是
        // GlobalExceptionHandler 漏掉 HttpMediaTypeNotSupportedException 分支这件事，
        // 集成测试全绿，而真实服务返回的是 500「服务异常，请稍后重试」。
        // 所以这里刻意用普通的 post()：不带请求体、不带 Content-Type。
        // 断言落在【具体文案】上而不是只断 400，是为了钉住「是哪个分支接下的」：
        //   - 走 handleBadUploadRequest  → "请以 multipart/form-data 方式上传文件"
        //   - 走 file 参数为 null 那条    → "请选择要上传的图片"（40001）
        // 两者都是 400，但只有前者能证明「非 multipart 请求被识别出来了」。
        // 只断状态码的话，这条用例在修复前也可能因为别的原因变绿，等于没测。
        mockMvc.perform(post("/api/user/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请以 multipart/form-data 方式上传文件"));
    }

    @Test
    @DisplayName("未登录不能上传：401")
    void uploadWithoutToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/user/me/avatar")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES)))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 拼测试用户名。
     *
     * @param suffix 后缀
     * @return 带前缀的用户名
     */
    private static String USER(String suffix) {
        return PREFIX + suffix;
    }

    /**
     * 把站内相对路径还原成磁盘路径。
     *
     * @param url 形如 {@code /uploads/avatar/20260930/12_ab.png}
     * @return 绝对路径
     */
    private static Path onDisk(String url) {
        return Paths.get(UPLOAD_DIR).toAbsolutePath().normalize()
                .resolve(url.substring("/uploads/".length()));
    }

    /**
     * 走真实接口注册用户。
     *
     * <p>⚠️ <b>QQ 号自 2026-10-01 起是注册必填项，且必须走完群内验证</b> ——
     * 所以这里先借 {@code QqVerifyService} 走一遍「签发 → 群内确认」，
     * 再带上 challengeId 提交。真机上中间那一步由群消息触发
     * （见 {@code qqbot} 包的 {@code QqCommandService}）。
     *
     * <p>少了它，注册会返回 400，而用例看到的是一个莫名其妙的
     * 「expected 200 but was 400」—— 与 QQ 这件事毫无关联的样子。
     *
     * @param username 用户名
     * @throws Exception 请求失败时抛出
     */
    private void register(String username) throws Exception {
        String qq = String.valueOf(qqSequence.incrementAndGet());
        QqVerifyIssueVo issued = qqVerifyService.issue(qq).getData();
        qqVerifyService.confirm(qq, issued.getCode());

        mockMvc.perform(post("/api/user/register")
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","password":"%s","qq":"%s","challengeId":"%s"}
                                """.formatted(username, PASSWORD, qq, issued.getChallengeId())))
                .andExpect(status().isOk());
    }

    /**
     * 走真实接口登录并取出凭证。
     *
     * @param username 用户名
     * @return JWT 字符串
     * @throws Exception 请求失败或响应结构不符时抛出
     */
    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /**
     * 调上传接口并取回新图的站内相对路径。
     *
     * @param token       凭证
     * @param fileName    原始文件名
     * @param contentType 客户端声称的类型
     * @param bytes       文件内容
     * @return 站内相对路径
     * @throws Exception 请求失败时抛出
     */
    private String uploadAvatar(String token, String fileName, String contentType, byte[] bytes)
            throws Exception {
        String body = mockMvc.perform(multipart("/api/user/me/avatar")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String url = objectMapper.readTree(body).path("data").path("avatar").asText();
        assertNotNull(url, "上传成功必须返回头像路径");
        return url;
    }

    /**
     * 调上传接口上传背景图。
     *
     * @param token       凭证
     * @param fileName    原始文件名
     * @param contentType 客户端声称的类型
     * @param bytes       文件内容
     * @return 站内相对路径
     * @throws Exception 请求失败时抛出
     */
    private String uploadBanner(String token, String fileName, String contentType, byte[] bytes)
            throws Exception {
        String body = mockMvc.perform(multipart("/api/user/me/banner")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("banner").asText();
    }
}
