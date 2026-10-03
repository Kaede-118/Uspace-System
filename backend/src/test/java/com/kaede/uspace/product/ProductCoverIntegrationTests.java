package com.kaede.uspace.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.user.QqVerifyService;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.product.mapper.ProductMapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品封面上传的集成测试（连真库，{@code @Transactional} 回滚）。
 *
 * <p>钉的是几件<b>只有跑起来才验得了</b>的事：
 * <table border="1">
 *   <tr><th>用例</th><th>防的是哪一类错</th></tr>
 *   <tr>
 *     <td>{@code uploadCover_thenFetchAnonymously}</td>
 *     <td>{@code PUBLIC_PATHS} 里的 {@code /uploads/**} 与 {@code WebMvcConfig} 的资源映射
 *         —— 少了任一处，图片全裂而<b>后端日志里一行都没有</b>。
 *         对 {@code product} 子目录虽然「理论上同一条规则」，但那是推理不是事实</td>
 *   </tr>
 *   <tr>
 *     <td>{@code uploadCover_thenCreateProduct_roundTrip}</td>
 *     <td>上传 → 填表单 → 落库这条链路真的闭合（不写库的设计不等于不落库）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code nonAdminUpload_returns403}</td>
 *     <td>上传接口挪出 {@code AdminProductController} 之后变成「任何登录用户可调」</td>
 *   </tr>
 *   <tr>
 *     <td>{@code nonMultipartRequest_returns400}</td>
 *     <td>{@code GlobalExceptionHandler} 的非 multipart 分支 —— 这条曾被
 *         MockMvc 的 {@code multipart()} 构造器骗过，所以刻意用普通 {@code post()} 发起</td>
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
class ProductCoverIntegrationTests {

    /** 测试用户名前缀，避免与开发库中的真实数据混淆 */
    private static final String PREFIX = "pcit_";

    /** 测试密码，满足 8 位下限 */
    private static final String PASSWORD = "Test@1234";

    /** 合法的 PNG 文件头 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** 商品封面的 URL 前缀 */
    private static final String COVER_PREFIX = "/uploads/product/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    /** 注册流程要消费一次群内验证，见 {@link #register} */
    @Autowired
    private QqVerifyService qqVerifyService;

    /** 自动生成的 QQ 号序号。理由见 {@code ImageUploadIntegrationTests} 里同一处说明 */
    private final AtomicInteger qqSequence = new AtomicInteger(77100000);

    // ==================================================================
    // 主链路
    // ==================================================================

    @Test
    @DisplayName("上传封面 → 图片能被匿名取回，且内容就是上传的那份")
    void uploadCover_thenFetchAnonymously() throws Exception {
        String token = adminToken("fetch");

        String cover = uploadCover(token, "cover.png", "image/png", PNG_BYTES);
        assertTrue(cover.startsWith(COVER_PREFIX), "实际返回：" + cover);

        // ⚠️ 关键：这里【不带】Authorization 头。
        // 图片是 <img src> 加载的，浏览器不会为它带凭证 ——
        // PUBLIC_PATHS 漏了 /uploads/** 的话，这一步会拿到 401 而不是图片
        mockMvc.perform(get(cover))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    @DisplayName("端到端：上传拿到的路径随商品一起落库，查回来一致")
    void uploadCover_thenCreateProduct_roundTrip() throws Exception {
        String token = adminToken("roundtrip");
        String cover = uploadCover(token, "cover.png", "image/png", PNG_BYTES);

        // 这正是前端的操作顺序：先传图拿到路径，再保存商品（上传接口自己不写库）
        Long id = createProduct(token, USER("goods"), cover);
        assertEquals(cover, productMapper.selectById(id).getCover(), "封面路径必须原样落库");
    }

    @Test
    @DisplayName("置空封面：库里的 cover 变成 null（「移除封面」按钮的后端保障）")
    void updateWithEmptyCover_clearsIt() throws Exception {
        String token = adminToken("clear");
        String cover = uploadCover(token, "cover.png", "image/png", PNG_BYTES);
        Long id = createProduct(token, USER("clearme"), cover);
        assertNotNull(productMapper.selectById(id).getCover());

        // 前端「移除封面」就是把这个字段置空（全量替换语义：没传即清空）
        mockMvc.perform(put("/api/admin/products/" + id)
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + token)
                        .content("""
                                {"name":"%s","cover":"","price":2.50,"stock":10,"sortNo":0,"enabled":1}
                                """.formatted(USER("clearme"))))
                .andExpect(status().isOk());

        assertNull(productMapper.selectById(id).getCover(),
                "空串应当被归一为 null，而不是留下一个空字符串");
    }

    // ==================================================================
    // 拒绝路径
    // ==================================================================

    @Test
    @DisplayName("普通用户上传封面：403")
    void nonAdminUpload_returns403() throws Exception {
        register(USER("nobody"));
        String token = login(USER("nobody"));

        // @PreAuthorize 标在 AdminProductController 类上，所以本接口自动继承。
        // 一旦有人把它挪去别的 Controller，这条会红
        mockMvc.perform(multipart("/api/admin/products/cover")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("伪装成 PNG 的 JSP：400 与 UPLOAD_FILE_INVALID")
    void fakeImage_returns400() throws Exception {
        String token = adminToken("fake");

        byte[] jsp = "<%@ page import=\"java.util.*\" %>".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/admin/products/cover")
                        .file(new MockMultipartFile("file", "shell.png", "image/png", jsp))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("超过 multipart 上限：413 与 UPLOAD_FILE_TOO_LARGE，而不是 500")
    void oversizedUpload_returns413() throws Exception {
        String token = adminToken("huge");

        // 3MB > application.properties 里配的 2MB。超限由 Spring 在解析 multipart 时拦下，
        // 走的是 GlobalExceptionHandler 的 MaxUploadSizeExceededException 分支
        mockMvc.perform(multipart("/api/admin/products/cover")
                        .file(new MockMultipartFile("file", "huge.png", "image/png", new byte[3 * 1024 * 1024]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(41300));
    }

    @Test
    @DisplayName("请求里没带 file 部分：400，而不是 500")
    void missingFilePart_returns400() throws Exception {
        String token = adminToken("nofile");

        // Controller 把 file 声明为可选就是为了这一条：声明为必填时 Spring 抛的
        // MissingServletRequestPartException 不在全局异常处理器名单里，会变成 500
        mockMvc.perform(multipart("/api/admin/products/cover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("用普通 POST（压根不是 multipart）调用：400，而不是 500")
    void nonMultipartRequest_returns400() throws Exception {
        String token = adminToken("plain");

        // ⚠️ 必须用普通的 post()：MockMvc 的 multipart() 构造器【永远】会造出一个合法的
        // multipart 请求，因此走不到「压根不是 multipart」这条路上 —— 而真实服务上
        // 它落到的是兜底分支的 500。断言落在【具体文案】上是为了钉住「是哪个分支接下的」：
        // 走 handleBadUploadRequest 才是这条，走 file 为 null 那条会拿到「请选择要上传的图片」。
        // 两者都是 400，只断状态码等于没测
        mockMvc.perform(post("/api/admin/products/cover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请以 multipart/form-data 方式上传文件"));
    }

    @Test
    @DisplayName("未登录不能上传：401")
    void uploadWithoutToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/admin/products/cover")
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
     * 走真实接口注册用户。
     *
     * <p>⚠️ <b>QQ 号自 2026-10-01 起是注册必填项，且必须走完群内验证</b> ——
     * 见 {@code ImageUploadIntegrationTests#register} 里同一段说明。
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
     * 造一个已登录的管理员，返回其凭证。
     *
     * <p>不走接口：改角色接口本身要求管理员权限，而这里正是要造出管理员。
     * 测试里直接改库是合理的 —— 真实系统由建表脚本预置管理员账号。
     *
     * @param suffix 用户名后缀
     * @return JWT 字符串
     * @throws Exception 请求失败时抛出
     */
    private String adminToken(String suffix) throws Exception {
        String username = USER(suffix);
        register(username);
        SysUser user = userMapper.selectByUsername(username);
        userMapper.updateRole(user.getId(), "ADMIN");
        return login(username);
    }

    /**
     * 调上传接口并取回封面的站内相对路径。
     *
     * @param token       凭证
     * @param fileName    原始文件名
     * @param contentType 客户端声称的类型
     * @param bytes       文件内容
     * @return 站内相对路径
     * @throws Exception 请求失败时抛出
     */
    private String uploadCover(String token, String fileName, String contentType, byte[] bytes)
            throws Exception {
        String body = mockMvc.perform(multipart("/api/admin/products/cover")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String cover = objectMapper.readTree(body).path("data").path("cover").asText();
        assertNotNull(cover, "上传成功必须返回封面路径");
        return cover;
    }

    /**
     * 新增一件商品，返回它的 ID。
     *
     * @param token 管理员凭证
     * @param name  商品名
     * @param cover 封面路径，可为 null
     * @return 新建商品的 ID
     * @throws Exception 请求失败时抛出
     */
    private Long createProduct(String token, String name, String cover) throws Exception {
        String body = mockMvc.perform(post("/api/admin/products")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + token)
                        .content("""
                                {"name":"%s","cover":"%s","price":2.50,"stock":10,"sortNo":0,"enabled":1}
                                """.formatted(name, cover == null ? "" : cover)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("id").asLong();
    }
}
