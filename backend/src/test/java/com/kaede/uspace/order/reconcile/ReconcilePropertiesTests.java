package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.config.UploadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReconcileProperties} 与 {@link ReconcileBillStorage} 的单元测试。
 *
 * <p><b>不启动 Spring</b>：配置类的默认值直接 new 出来读，
 * 留档组件用一个 {@link TempDir} 现造的目录。
 *
 * <p>最要紧的是「账单目录不能落在公开的上传目录之下」那一条 ——
 * 那是整个对账功能里唯一一处<b>安全</b>约束，而配置写错不会有任何报错，
 * 只会把本店全部交易对手与金额挂到公网上。所以它必须拒绝启动，
 * 而不是记条 warn 就算了。
 */
class ReconcilePropertiesTests {

    @Test
    @DisplayName("默认值：窗口不对称，后置天数比前置宽")
    void defaults() {
        ReconcileProperties properties = new ReconcileProperties();

        assertEquals("reconcile-bills", properties.getBillDir(),
                "默认与 uploads 平级 —— 落在 uploads 之下会拒绝启动");
        assertEquals(1, properties.getWindowBeforeDays());
        assertEquals(2, properties.getWindowAfterDays(),
                "上界比下界宽：用户当天玩完忘了传、隔天才想起来是常见情形，"
                        + "而「先传图后付款」少见且顶多差几小时");
        assertEquals(5000, properties.getCandidateWarnThreshold());
    }

    // ==================================================================
    // 目录校验：唯一的一处安全约束
    // ==================================================================

    @Test
    @DisplayName("留档目录落在上传目录之下 → 拒绝启动")
    void storage_rejectsBillDirInsideUploadDir() {
        ReconcileProperties properties = new ReconcileProperties();
        properties.setBillDir("uploads/bills");

        ReconcileBillStorage storage = new ReconcileBillStorage(properties, new UploadProperties());

        IllegalStateException e = assertThrows(IllegalStateException.class, storage::validateLocation,
                "/uploads/** 在 PUBLIC_PATHS 里是匿名可访问的（头像与截图要用 <img src> 加载，"
                        + "浏览器不为图片请求带 Authorization 头）。账单含全部交易对手、备注与金额，"
                        + "落在那里等于挂在公网上 —— 而配置写错不会有任何报错，所以必须让它起不来");
        assertTrue(e.getMessage().contains("bill-dir"),
                "报错要说清是哪个配置项写错了。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("两者是同一个目录 → 也拒绝")
    void storage_rejectsSameDirectory() {
        ReconcileProperties properties = new ReconcileProperties();
        // 与 UploadProperties 的默认值 "uploads" 指的是同一个目录，只是写法不同
        properties.setBillDir("./uploads/");

        ReconcileBillStorage storage = new ReconcileBillStorage(properties, new UploadProperties());

        assertThrows(IllegalStateException.class, storage::validateLocation,
                "「uploads」与「./uploads/」是同一个目录，但字符串比较看不出来 ——"
                        + "所以两个路径都要先 normalize() 成绝对路径再比");
    }

    @Test
    @DisplayName("平级的目录能通过校验")
    void storage_acceptsSiblingDirectory() {
        ReconcileProperties properties = new ReconcileProperties();

        ReconcileBillStorage storage = new ReconcileBillStorage(properties, new UploadProperties());

        storage.validateLocation();   // 不抛即通过
    }

    // ==================================================================
    // 留档与读取
    // ==================================================================

    @Test
    @DisplayName("落盘：文件名用 UUID，扩展名取自原始文件名的安全部分")
    void store_usesUuidAndSafeExtension(@TempDir Path tempDir) {
        ReconcileBillStorage storage = storageAt(tempDir);
        byte[] content = "交易时间,交易单号\n2026-09-30 21:45:32,4200001234\n"
                .getBytes(StandardCharsets.UTF_8);

        String stored = storage.store(content, "微信支付账单(20260901-20260930).csv");

        assertNotNull(stored);
        assertTrue(stored.endsWith(".csv"), "扩展名保留下来，下载时浏览器才知道这是 CSV");
        assertTrue(stored.matches("[0-9a-f-]{36}\\.csv"),
                "文件名是 UUID —— 管理员给的文件名是自由文本（含空格、括号、斜杠），"
                        + "清洗成安全文件名的那套规则没有任何测试能穷尽。实际：" + stored);
        assertArrayEquals(content, storage.load(stored), "存进去什么，读出来还是什么");
    }

    @Test
    @DisplayName("落盘：扩展名不可信时干脆不要 —— 它只影响观感，不参与类型判定")
    void store_dropsUnsafeExtension(@TempDir Path tempDir) {
        ReconcileBillStorage storage = storageAt(tempDir);

        String stored = storage.store("x".getBytes(StandardCharsets.UTF_8), "账单.带空格 的扩展名");

        assertNotNull(stored);
        assertTrue(stored.matches("[0-9a-f-]{36}"),
                "带空格的扩展名被丢掉。反正类型判定看的是文件头（见 BillRowReaders），"
                        + "扩展名在这里纯属展示。实际：" + stored);
    }

    @Test
    @DisplayName("读回：路径为空、越界或文件不存在时返回 null，不抛异常")
    void load_returnsNullWhenUnavailable(@TempDir Path tempDir) {
        ReconcileBillStorage storage = storageAt(tempDir);

        assertNull(storage.load(null), "批次上没留档时路径是 null");
        assertNull(storage.load("  "));
        assertNull(storage.load("not-exists.csv"), "文件被清理掉了");
        assertNull(storage.load("../../etc/passwd"),
                "路径越界要拒绝 —— 只比字符串开头的话这种写法能通过检查。"
                        + "这一列是服务端自己写的，理论上不会越界，但校验的代价是两行，"
                        + "而漏掉的后果是「读走服务器上的任意文件」");
    }

    /**
     * 造一个落在临时目录里的留档组件。
     *
     * @param tempDir 临时目录，同时当作上传目录与留档目录的父目录
     * @return 留档组件
     */
    private static ReconcileBillStorage storageAt(Path tempDir) {
        ReconcileProperties properties = new ReconcileProperties();
        properties.setBillDir(tempDir.resolve("bills").toString());

        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setDir(tempDir.resolve("uploads").toString());

        return new ReconcileBillStorage(properties, uploadProperties);
    }
}
