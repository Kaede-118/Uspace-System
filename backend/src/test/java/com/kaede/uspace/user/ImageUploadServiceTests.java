package com.kaede.uspace.user;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUploadService} 的单元测试。
 *
 * <p><b>为什么要这么多边界用例</b>：上传是这个模块里唯一「直接碰文件系统」的地方，
 * 而它的失败方式大多<b>不会报错</b> —— 路径穿越写到了目录外面、
 * 扩展名跟着原始文件名走、旧图删不掉留下垃圾、两次上传撞名后一次静默覆盖前一次。
 * 这些在功能演示时全都看不出来，只在出事时才暴露。
 *
 * <p>落盘目录用 JUnit 的 {@code @TempDir}，跑完自动清理，不碰开发库也不碰
 * 仓库里的 {@code backend/uploads/}。数据访问由 {@link FakeSysUserMapper} 顶替，
 * 因此本类不连数据库。
 */
class ImageUploadServiceTests {

    /** 一个合法的 JPEG 文件头（含 JFIF 标识），长度满足最短判定要求 */
    private static final byte[] JPEG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x02, 0x03};

    /** 一个合法的 PNG 文件头 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** 一个合法的 WebP 文件头：RIFF 容器 + 偏移 8 处的 WEBP 标识 */
    private static final byte[] WEBP_BYTES = {
            'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00,
            'W', 'E', 'B', 'P', 0x56, 0x50, 0x38, 0x20};

    /** 一段 JSP 源码 —— 冒充图片的典型攻击载荷 */
    private static final byte[] JSP_BYTES =
            "<%@ page import=\"java.util.*\" %><% out.print(1); %>".getBytes(StandardCharsets.UTF_8);

    /** 上传根目录，每个用例一个临时目录 */
    @TempDir
    Path tempDir;

    /** 被测服务的配置 */
    private UploadProperties props;

    /** 顶替真实 Mapper 的内存实现 */
    private FakeSysUserMapper fakeMapper;

    /** 被测对象 */
    private ImageUploadService service;

    /** 预置用户的 ID */
    private Long userId;

    /**
     * 组装被测对象：临时上传目录 + 内存 Mapper + 一个预置用户。
     */
    @BeforeEach
    void setUp() {
        props = new UploadProperties();
        props.setDir(tempDir.resolve("uploads").toString());
        props.setUrlPrefix("/uploads");
        props.setMaxImageBytes(2 * 1024 * 1024);

        fakeMapper = new FakeSysUserMapper();
        SysUser user = new SysUser();
        user.setUsername("xiaofeng");
        user.setNickname("小枫");
        user.setRole("USER");
        user.setStatus(1);
        user.setTokenVersion(0);
        user.setOrderPaid(BigDecimal.ZERO);
        user.setCardPaid(BigDecimal.ZERO);
        user.setTotalPaid(BigDecimal.ZERO);
        userId = fakeMapper.seed(user).getId();

        // 落盘那一段已经抽到公共层，这里给它同一个 props —— 两者共享引用，
        // 所以下面那些「构造之后再改配置」的用例（如超限）仍然生效
        service = new ImageUploadService(fakeMapper.asMapper(), new ImageStorage(props));
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("上传 JPEG 头像：落盘到 avatar 子目录，文件名是 {用户名}_{ID}.{ext}")
    void uploadAvatar_jpeg_succeeds() {
        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("photo.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess(), "应当成功");
        String url = result.getData().getAvatar();

        assertEquals("/uploads/avatar/xiaofeng_" + userId + ".jpg", url,
                "路径结构是 {前缀}/{avatar|banner}/{用户名}_{ID}.{ext} —— "
                        + "用户名与 ID 都已唯一，拼起来天然不重名，不需要再套日期目录");
        assertTrue(Files.exists(fileOf(url)), "文件必须真的落到磁盘上");
        assertEquals(url, fakeMapper.get(userId).getAvatar(), "新路径必须写回库里");
    }

    @Test
    @DisplayName("三种受支持的格式都能识别，扩展名由类型映射而非原始文件名决定")
    void uploadAvatar_supportedFormats_extensionFromMagicBytes() {
        // 刻意让原始文件名与真实内容不符：内容是 PNG，文件名却说 .jpg
        BizResult<UserProfileVo> png = service.uploadAvatar(userId,
                file("misleading.jpg", "image/jpeg", PNG_BYTES));
        assertTrue(png.getData().getAvatar().endsWith(".png"),
                "扩展名必须由文件内容推出；跟着原始文件名走的话，"
                        + "上传 evil.jsp 就会得到一个 .jsp 的文件名");

        BizResult<UserProfileVo> webp = service.uploadAvatar(userId,
                file("x", "application/octet-stream", WEBP_BYTES));
        assertTrue(webp.getData().getAvatar().endsWith(".webp"),
                "WebP 靠偏移 8 处的 WEBP 标识识别，不依赖 Content-Type");

        BizResult<UserProfileVo> jpeg = service.uploadAvatar(userId,
                file("x", null, JPEG_BYTES));
        assertTrue(jpeg.getData().getAvatar().endsWith(".jpg"),
                "Content-Type 缺失也不影响 —— 判类型只看文件头");
    }

    @Test
    @DisplayName("上传 Banner：写 banner 列，不动 avatar 列")
    void uploadBanner_doesNotTouchAvatar() {
        service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));
        String avatar = fakeMapper.get(userId).getAvatar();

        BizResult<UserProfileVo> result = service.uploadBanner(userId,
                file("b.png", "image/png", PNG_BYTES));

        assertTrue(result.isSuccess());
        assertTrue(result.getData().getBanner().contains("/uploads/banner/"), "Banner 落在 banner 子目录");
        assertEquals(avatar, fakeMapper.get(userId).getAvatar(),
                "换背景图不该顺手把头像写一遍 —— 那会在并发上传时丢更新");
    }

    @Test
    @DisplayName("返回的是完整资料视图，前端一行 store.setUser 即可刷新")
    void upload_returnsFullProfile() {
        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));

        UserProfileVo vo = result.getData();
        assertEquals(userId, vo.getId());
        assertEquals("小枫", vo.getNickname());
        assertEquals("USER", vo.getRole());
        assertTrue(vo.getAvatar().startsWith("/uploads/"));
    }

    // ==================================================================
    // 类型校验：看文件头，不看客户端说的
    // ==================================================================

    @Test
    @DisplayName("伪装成图片的 JSP：按文件头识破，Content-Type 伪造无效")
    void upload_rejectsFakeContentType() {
        // 「客户端声称是 image/png」正是这道校验要防的场景
        BizResult<UserProfileVo> result = service.uploadAvatar(userId,
                file("shell.png", "image/png", JSP_BYTES));

        assertFalse(result.isSuccess(), "内容不是图片，就必须拒绝");
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, result.getError());
        assertNull(fakeMapper.get(userId).getAvatar(), "被拒绝时不能写库");
        assertNoFilesUnder(props.getDir(), "被拒绝时不能留下任何文件");
    }

    @Test
    @DisplayName("不支持的图片格式（GIF）与过短的伪文件都被拒")
    void upload_rejectsUnsupportedAndTruncated() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00, 0x00, 0x00};
        assertFalse(service.uploadAvatar(userId, file("a.gif", "image/gif", gif)).isSuccess(),
                "只支持 JPG / PNG / WebP");

        byte[] truncated = {(byte) 0xFF, (byte) 0xD8};
        assertFalse(service.uploadAvatar(userId, file("a.jpg", "image/jpeg", truncated)).isSuccess(),
                "太短的内容不足以判定，必须拒绝 —— 否则一次只传两个字节就能建出文件");
    }

    // ==================================================================
    // 大小与空值
    // ==================================================================

    @Test
    @DisplayName("超过大小上限：413 对应的错误码")
    void upload_rejectsOversizedFile() {
        // 上限比这张图小一个字节，把边界精确地卡在「刚好超限」上
        props.setMaxImageBytes(JPEG_BYTES.length - 1);

        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("big.jpg", "image/jpeg", JPEG_BYTES));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.UPLOAD_FILE_TOO_LARGE, result.getError(),
                "「太大了」与「格式不对」要分开 —— 用户的可行动作不同");
        assertNoFilesUnder(props.getDir(), "超限时不能留下文件");
    }

    @Test
    @DisplayName("大小恰好等于上限：放行 —— 边界是「超过」而不是「达到」")
    void upload_acceptsFileExactlyAtLimit() {
        props.setMaxImageBytes(JPEG_BYTES.length);

        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess(),
                "判断写的是 size > max 而不是 size >= max；把恰好 2MB 的图拒掉，"
                        + "用户会以为「我明明压到 2MB 了怎么还不行」");
    }

    @Test
    @DisplayName("没带文件 / 空文件：400，而不是 500")
    void upload_rejectsMissingOrEmptyFile() {
        // Controller 把 file 声明为可选就是为了让「没带文件」走到这里 ——
        // Spring 对必填 MultipartFile 抛的异常不在全局异常处理器名单里，会变成 500
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID,
                service.uploadAvatar(userId, null).getError());

        MultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID,
                service.uploadAvatar(userId, empty).getError());
    }

    @Test
    @DisplayName("用户不存在：拒绝，且不留下文件")
    void upload_rejectsUnknownUser() {
        BizResult<UserProfileVo> result = service.uploadAvatar(99999L, file("a.jpg", "image/jpeg", JPEG_BYTES));

        assertEquals(ErrorCode.USER_NOT_FOUND, result.getError());
        assertNoFilesUnder(props.getDir(), "先查用户再落盘，用户不存在时不该产生任何文件");
    }

    // ==================================================================
    // 文件名完全由服务端生成
    // ==================================================================

    @Test
    @DisplayName("原始文件名一个字都不用 —— 路径穿越与 .jsp 扩展名同时失效")
    void upload_ignoresOriginalFileName() throws IOException {
        BizResult<UserProfileVo> result = service.uploadAvatar(userId,
                file("../../../../evil.jsp", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess());
        Path written = fileOf(result.getData().getAvatar());
        assertTrue(written.normalize().startsWith(Paths.get(props.getDir()).toAbsolutePath().normalize()),
                "落盘位置必须在上传目录之内");
        assertFalse(Files.exists(tempDir.resolve("evil.jsp")),
                "原始文件名里的 ../ 一旦被采信就会写到上传目录之外");
        assertTrue(written.getFileName().toString().endsWith(".jpg"),
                "扩展名跟着内容走，而不是跟着 evil.jsp 走");
    }

    @Test
    @DisplayName("重传同一种格式：原地覆盖，既不产生垃圾，也不会把自己删掉")
    void upload_sameFormatTwice_overwritesInPlace() throws IOException {
        String first = service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES))
                .getData().getAvatar();

        // 换一份内容再传，模拟用户「换一张头像」
        byte[] anotherImage = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x16,
                'E', 'x', 'i', 'f', 0x00, 0x00};
        String second = service.uploadAvatar(userId, file("b.jpg", "image/jpeg", anotherImage))
                .getData().getAvatar();

        assertEquals(first, second, "文件名固定，同格式重传落在同一个路径上");
        // ⚠️ 这一条是这个设计最容易踩的坑：路径相同时若还去「删旧图」，
        // 删掉的正是刚写进去的那张 —— 用户既没有新图也没有旧图，而接口返回 200
        assertTrue(Files.exists(fileOf(second)), "新图必须在");
        assertTrue(sizeOf(fileOf(second)) == anotherImage.length,
                "文件内容应当是后一次上传的那份，说明覆盖确实发生了");
        assertEquals(1, countFilesUnder(props.getDir()),
                "同一用户的同一种图只该留下一个文件，不该越传越多");
    }

    @Test
    @DisplayName("换格式（png → jpg）：旧格式的文件被清掉，不留垃圾")
    void upload_differentFormat_deletesOldFile() {
        String png = service.uploadAvatar(userId, file("a.png", "image/png", PNG_BYTES))
                .getData().getAvatar();
        assertTrue(Files.exists(fileOf(png)));

        String jpg = service.uploadAvatar(userId, file("b.jpg", "image/jpeg", JPEG_BYTES))
                .getData().getAvatar();

        assertNotEquals(png, jpg);
        assertFalse(Files.exists(fileOf(png)),
                "新图落在另一个路径上，旧格式的文件必须清掉，否则磁盘越用越多");
        assertTrue(Files.exists(fileOf(jpg)));
    }

    @Test
    @DisplayName("文件名里只有用户名与 ID，没有任何用户可控片段")
    void upload_fileNameShape() {
        String url = service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES))
                .getData().getAvatar();

        assertEquals("xiaofeng_" + userId + ".jpg", url.substring(url.lastIndexOf('/') + 1));
    }

    // ==================================================================
    // 旧图清理：两道保险
    // ==================================================================

    @Test
    @DisplayName("旧图路径越出上传目录：拒绝删除，越界文件原封不动")
    void upload_refusesToDeleteOutsideUploadDir() throws IOException {
        // 在上传目录【之外】造一个文件，它绝不能因为一次换头像而消失
        Path outside = tempDir.resolve("outside.jpg");
        Files.write(outside, JPEG_BYTES);

        // 先正常传一张（会生成目录），再把库里的路径改成穿越形式
        service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));
        fakeMapper.get(userId).setAvatar("/uploads/../outside.jpg");

        service.uploadAvatar(userId, file("b.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(Files.exists(outside),
                "⚠️ /uploads/../outside.jpg 能通过「以前缀开头」的字符串检查，"
                        + "必须先 normalize() 再比前缀才能挡住它。"
                        + "这道校验漏了的后果是：改一次 sys_user.avatar 就能删掉任意文件，且不报错");
    }

    @Test
    @DisplayName("旧图路径不在上传前缀之下：拒绝删除")
    void upload_refusesToDeletePathWithoutPrefix() throws IOException {
        Path other = tempDir.resolve("other.jpg");
        Files.write(other, JPEG_BYTES);

        service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));
        // 合法值只会是本服务自己写进去的 /uploads/... 形式，
        // 出现别的取值说明这条记录被人工改过 —— 那不是我们该删的东西
        fakeMapper.get(userId).setAvatar(tempDir.resolve("other.jpg").toAbsolutePath().toString());

        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("b.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess(), "旧图删不掉不该让上传失败");
        assertTrue(Files.exists(other), "不在前缀之下的路径一律不碰");
    }

    @Test
    @DisplayName("旧图文件本就不存在：不报错，照常上传成功")
    void upload_missingOldFile_isNotAnError() {
        fakeMapper.get(userId).setAvatar("/uploads/avatar/20200101/1_deadbeef.jpg");

        BizResult<UserProfileVo> result = service.uploadAvatar(userId, file("a.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess(), "删除失败只记日志，不影响上传成功 —— 换头像这件事已经完成了");
        assertTrue(Files.exists(fileOf(result.getData().getAvatar())));
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 造一个上传文件。
     *
     * @param originalName 原始文件名，服务端应当完全忽略它
     * @param contentType  客户端声称的类型，同样不参与判定
     * @param bytes        文件内容
     * @return MultipartFile
     */
    private static MultipartFile file(String originalName, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", originalName, contentType, bytes);
    }

    /**
     * 把库里的相对路径还原成磁盘上的文件路径。
     *
     * @param url 站内相对路径，如 {@code /uploads/avatar/20260930/12_ab.jpg}
     * @return 绝对路径
     */
    private Path fileOf(String url) {
        return Paths.get(props.getDir()).toAbsolutePath().normalize()
                .resolve(url.substring(props.getUrlPrefix().length() + 1));
    }

    /**
     * 读文件大小。
     *
     * @param path 文件路径
     * @return 字节数
     */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new AssertionError("读取文件大小失败：" + path, e);
        }
    }

    /**
     * 数上传目录下的文件个数。
     *
     * @param dir 上传目录
     * @return 常规文件的数量
     */
    private static long countFilesUnder(String dir) {
        Path root = Paths.get(dir);
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new AssertionError("遍历上传目录失败", e);
        }
    }

    /**
     * 断言上传目录下没有任何文件。
     *
     * @param dir 上传目录
     * @param msg 失败提示
     */
    private static void assertNoFilesUnder(String dir, String msg) {
        assertEquals(0, countFilesUnder(dir), msg);
    }
}
