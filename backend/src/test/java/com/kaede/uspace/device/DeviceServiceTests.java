package com.kaede.uspace.device;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.device.dto.DeviceDisplayVo;
import com.kaede.uspace.device.dto.DeviceGroupVo;
import com.kaede.uspace.device.dto.DeviceRequest;
import com.kaede.uspace.device.dto.DeviceVo;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import com.kaede.uspace.device.dto.UpdateDeviceStatusRequest;
import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.entity.EquipmentType;
import com.kaede.uspace.notice.FakeNoticeMapper;
import com.kaede.uspace.notice.NoticeContents;
import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.NoticeService;
import com.kaede.uspace.notice.NoticeSourceType;
import com.kaede.uspace.notice.entity.Notice;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeviceService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>覆盖重点有四块，都是「错了也不会报错、只会在界面上悄悄错」的地方：
 * <ol>
 *   <li><b>陈列分组</b> —— 组按类型权重排、组内按机台权重排、空组不出现、
 *       维护中的机台必须还在</li>
 *   <li><b>停用类型的两种口径</b> —— 陈列要能查到停用类型下的机台（否则它们
 *       整批消失），「可选类型」则不能给出停用类型</li>
 *   <li><b>状况在修改时的例外语义</b> —— {@code status} 传 null 要<b>保持原值</b>，
 *       不能被一次普通改名顺手改回「良好」</li>
 *   <li><b>改状况只动一列</b> —— 不碰名称与位置，避免覆盖别人刚改的内容</li>
 * </ol>
 *
 * <p>本模块不参与计费与准入，因此<b>没有</b>任何「机台状态影响下单」的用例 ——
 * 那种影响在设计上就不存在。
 */
class DeviceServiceTests {

    /** 测试门店的 ID */
    private static final Long STORE_ID = 1L;

    /** 字典表预置的两类机器，ID 与建表脚本无关，测的是 Service 的组装逻辑 */
    private static final Long PAIPAI_ID = 10L;
    private static final Long TAISHOU_ID = 20L;

    /** 内存版数据访问层 */
    private final FakeDeviceMapper deviceMapper = new FakeDeviceMapper();
    private final FakeEquipmentTypeMapper typeMapper = new FakeEquipmentTypeMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();

    /**
     * 公告模块的数据访问层。
     *
     * <p>机台状况变更会同步生成/撤销公告，所以本测试需要它。
     * 用<b>真的</b> {@code NoticeService} 配假 Mapper，而不是再造一个假的 Service ——
     * 这样「服务调了公告服务」与「公告服务写了什么」两段都被真实执行到，
     * 断言才能落在「库里到底有没有那条公告」上。
     */
    private final FakeNoticeMapper noticeMapper = new FakeNoticeMapper();

    /** 被测服务 */
    private final DeviceService deviceService = new DeviceService(
            deviceMapper.asMapper(), typeMapper.asMapper(), storeMapper.asMapper(),
            new NoticeService(noticeMapper.asMapper()));

    /** 每个用例前预置门店与两条类型字典 */
    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);

        typeMapper.seed(type(PAIPAI_ID, "PAIPAI", "拍拍机", 10));
        typeMapper.seed(type(TAISHOU_ID, "TAISHOU", "抬手乐", 20));
    }

    // ==================================================================
    // 用户端陈列
    // ==================================================================

    @Test
    @DisplayName("陈列：按类型分组，组的顺序由类型的排序权重决定")
    void listForDisplay_groupsByTypeInTypeOrder() {
        // 抬手乐的机台先插进去，但它的类型权重更大，组应当排在后面
        deviceMapper.seed(device(101L, "抬手乐 1 号", TAISHOU_ID, "NORMAL", 10));
        deviceMapper.seed(device(102L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        BizResult<List<DeviceGroupVo>> result = deviceService.listForDisplay();

        assertTrue(result.isSuccess());
        List<DeviceGroupVo> groups = result.getData();
        assertEquals(2, groups.size(), "两个类型各有一组");
        assertEquals("拍拍机", groups.get(0).getTypeName(),
                "组的先后由类型字典的 sort 决定（拍拍机 10 < 抬手乐 20），与插入顺序无关");
        assertEquals("PAIPAI", groups.get(0).getTypeCode());
        assertEquals("抬手乐", groups.get(1).getTypeName());
    }

    @Test
    @DisplayName("陈列：组内机台按展示顺序排列")
    void listForDisplay_ordersDevicesBySortWithinGroup() {
        deviceMapper.seed(device(101L, "拍拍机 3 号", PAIPAI_ID, "NORMAL", 30));
        deviceMapper.seed(device(102L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        deviceMapper.seed(device(103L, "拍拍机 2 号", PAIPAI_ID, "NORMAL", 20));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.size());
        List<String> names = groups.get(0).getDevices().stream()
                .map(DeviceDisplayVo::getName).toList();
        assertEquals(List.of("拍拍机 1 号", "拍拍机 2 号", "拍拍机 3 号"), names);
    }

    @Test
    @DisplayName("陈列：维护中的机台照样展示，只是标记为不可用")
    void listForDisplay_keepsMaintainingDevices() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "MAINTAINING", 10));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.size(), "维护中的机台不能从陈列里消失 —— "
                + "藏起来会让顾客以为机器搬走了");
        DeviceDisplayVo vo = groups.get(0).getDevices().get(0);
        assertEquals("MAINTAINING", vo.getStatus());
        assertEquals("维护中", vo.getStatusLabel());
        assertFalse(vo.isUsable(), "维护中不可用");
    }

    @Test
    @DisplayName("陈列：待维护的机台算可用（有小毛病但能玩）")
    void listForDisplay_needsRepairIsUsable() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NEEDS_REPAIR", 10));

        DeviceDisplayVo vo = deviceService.listForDisplay().getData()
                .get(0).getDevices().get(0);

        assertEquals("待维护", vo.getStatusLabel());
        assertTrue(vo.isUsable(), "「待维护」的语义是还能玩，只是有小毛病");
    }

    @Test
    @DisplayName("陈列：没有机台的类型不出现空组")
    void listForDisplay_omitsEmptyGroups() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.size(), "抬手乐一台都没有，不该出现一个有标题没内容的组");
        assertEquals("拍拍机", groups.get(0).getTypeName());
    }

    @Test
    @DisplayName("陈列：类型被停用不影响它下面的机台展示")
    void listForDisplay_keepsDevicesOfDisabledType() {
        // 把拍抬乐的字典项停用（模拟运营下架了这个类型）
        typeMapper.get(TAISHOU_ID).setEnabled(0);
        deviceMapper.seed(device(101L, "抬手乐 1 号", TAISHOU_ID, "NORMAL", 10));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.size(),
                "停用是「不再新增使用」，不是删掉 —— 挂在它下面的机台必须还在陈列里");
        assertEquals("抬手乐", groups.get(0).getTypeName());
    }

    @Test
    @DisplayName("陈列：类型字典里查不到的机台归入「其他」组而不是消失")
    void listForDisplay_putsOrphanDevicesInFallbackGroup() {
        deviceMapper.seed(device(101L, "某台老机器", 999L, "NORMAL", 10));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.size(), "类型查不到也得陈列出来，不能凭空少一台");
        assertEquals("其他", groups.get(0).getTypeName());
        assertNull(groups.get(0).getTypeCode());
        assertEquals("某台老机器", groups.get(0).getDevices().get(0).getName());
    }

    @Test
    @DisplayName("陈列：已删除的机台不再出现")
    void listForDisplay_excludesDeletedDevices() {
        Device removed = deviceMapper.seed(device(101L, "退役的机器", PAIPAI_ID, "NORMAL", 10));
        removed.setDeleted(1);
        deviceMapper.seed(device(102L, "在役的机器", PAIPAI_ID, "NORMAL", 20));

        List<DeviceGroupVo> groups = deviceService.listForDisplay().getData();

        assertEquals(1, groups.get(0).getDevices().size());
        assertEquals("在役的机器", groups.get(0).getDevices().get(0).getName());
    }

    @Test
    @DisplayName("陈列：门店不存在时返回 STORE_NOT_FOUND")
    void listForDisplay_storeMissing() {
        FakeStoreMapper empty = new FakeStoreMapper();
        DeviceService service = new DeviceService(
                deviceMapper.asMapper(), typeMapper.asMapper(), empty.asMapper(),
                new NoticeService(noticeMapper.asMapper()));

        BizResult<List<DeviceGroupVo>> result = service.listForDisplay();

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 可选类型列表
    // ==================================================================

    @Test
    @DisplayName("可选类型：不含已停用的类型")
    void listSelectableTypes_excludesDisabled() {
        typeMapper.get(TAISHOU_ID).setEnabled(0);

        List<EquipmentTypeVo> types = deviceService.listSelectableTypes().getData();

        assertEquals(1, types.size(), "停用的类型不该出现在顾客的选择框里");
        assertEquals("PAIPAI", types.get(0).getCode());
    }

    @Test
    @DisplayName("可选类型：按排序权重排列，且带上偏好要存的 code")
    void listSelectableTypes_orderedBySort() {
        List<EquipmentTypeVo> types = deviceService.listSelectableTypes().getData();

        assertEquals(2, types.size());
        assertEquals(List.of("PAIPAI", "TAISHOU"),
                types.stream().map(EquipmentTypeVo::getCode).toList());
        assertEquals("拍拍机", types.get(0).getName());
        assertNotNull(types.get(0).getId());
    }

    // ==================================================================
    // 新增机台
    // ==================================================================

    @Test
    @DisplayName("新增：成功，且省略的字段按默认值落库")
    void createDevice_success() {
        BizResult<DeviceVo> result = deviceService.createDevice(
                request("拍拍机 1 号", PAIPAI_ID));

        assertTrue(result.isSuccess());
        Device saved = deviceMapper.get(result.getData().getId());
        assertEquals(STORE_ID, saved.getStoreId());
        assertEquals("拍拍机 1 号", saved.getName());
        assertEquals("NORMAL", saved.getStatus(), "状况不填默认「良好」");
        assertEquals(0, saved.getSort().intValue(), "排序不填默认 0");
        assertNull(saved.getDeviceNo());
        assertNull(saved.getLocation());
        assertNull(saved.getRemark());
        assertEquals("拍拍机", result.getData().getTypeName(), "视图里直接带上类型名");
    }

    @Test
    @DisplayName("新增：可选字段的空白串归一为 null")
    void createDevice_trimsBlankFieldsToNull() {
        DeviceRequest request = request("拍拍机 1 号", PAIPAI_ID);
        request.setDeviceNo("  PP-01  ");
        request.setLocation("   ");
        request.setRemark("");

        Device saved = deviceMapper.get(
                deviceService.createDevice(request).getData().getId());

        assertEquals("PP-01", saved.getDeviceNo(), "首尾空白会被去掉");
        assertNull(saved.getLocation(), "纯空白等于没填");
        assertNull(saved.getRemark());
    }

    @Test
    @DisplayName("新增：可以指定初始状况")
    void createDevice_acceptsExplicitStatus() {
        DeviceRequest request = request("二手拍拍机", PAIPAI_ID);
        request.setStatus("NEEDS_REPAIR");

        Device saved = deviceMapper.get(
                deviceService.createDevice(request).getData().getId());

        assertEquals("NEEDS_REPAIR", saved.getStatus());
    }

    @Test
    @DisplayName("新增：编号重复被拒绝")
    void createDevice_rejectsDuplicateDeviceNo() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        deviceMapper.get(101L).setDeviceNo("PP-01");

        DeviceRequest request = request("拍拍机 2 号", PAIPAI_ID);
        request.setDeviceNo("PP-01");

        BizResult<DeviceVo> result = deviceService.createDevice(request);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_NO_EXISTS, result.getError());
    }

    @Test
    @DisplayName("新增：多台都没编号不算冲突")
    void createDevice_allowsMultipleBlankDeviceNo() {
        deviceService.createDevice(request("拍拍机 1 号", PAIPAI_ID));

        BizResult<DeviceVo> result = deviceService.createDevice(request("拍拍机 2 号", PAIPAI_ID));

        assertTrue(result.isSuccess(), "编号是选填的，新机还没贴编号时也该能录入");
    }

    @Test
    @DisplayName("新增：类型不存在被拒绝")
    void createDevice_rejectsUnknownType() {
        BizResult<DeviceVo> result = deviceService.createDevice(request("不知名的机器", 999L));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.EQUIPMENT_TYPE_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("新增：状况取值非法被拒绝")
    void createDevice_rejectsInvalidStatus() {
        DeviceRequest request = request("拍拍机 1 号", PAIPAI_ID);
        request.setStatus("BROKEN");

        BizResult<DeviceVo> result = deviceService.createDevice(request);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_STATUS_INVALID, result.getError());
    }

    @Test
    @DisplayName("新增：门店不存在时返回 STORE_NOT_FOUND")
    void createDevice_storeMissing() {
        DeviceService service = new DeviceService(
                deviceMapper.asMapper(), typeMapper.asMapper(), new FakeStoreMapper().asMapper(),
                new NoticeService(noticeMapper.asMapper()));

        BizResult<DeviceVo> result = service.createDevice(request("拍拍机 1 号", PAIPAI_ID));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 修改机台
    // ==================================================================

    @Test
    @DisplayName("修改：全量替换各字段")
    void updateDevice_replacesAllFields() {
        deviceMapper.seed(device(101L, "旧名字", PAIPAI_ID, "NORMAL", 10));

        DeviceRequest request = request("新名字", TAISHOU_ID);
        request.setDeviceNo("TS-09");
        request.setLocation("进门右手边");
        request.setSort(50);
        request.setRemark("新到货");

        BizResult<DeviceVo> result = deviceService.updateDevice(101L, request);

        assertTrue(result.isSuccess());
        Device saved = deviceMapper.get(101L);
        assertEquals("新名字", saved.getName());
        assertEquals("TS-09", saved.getDeviceNo());
        assertEquals(TAISHOU_ID, saved.getTypeId());
        assertEquals("进门右手边", saved.getLocation());
        assertEquals(50, saved.getSort().intValue());
        assertEquals("新到货", saved.getRemark());
        assertEquals("抬手乐", result.getData().getTypeName());
    }

    @Test
    @DisplayName("修改：状况传 null 时保持原值，不被改回「良好」")
    void updateDevice_keepsStatusWhenNull() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "MAINTAINING", 10));

        DeviceRequest request = request("拍拍机 1 号（已改名）", PAIPAI_ID);
        // 刻意不设 status
        BizResult<DeviceVo> result = deviceService.updateDevice(101L, request);

        assertTrue(result.isSuccess());
        assertEquals("MAINTAINING", deviceMapper.get(101L).getStatus(),
                "一次普通的改名不该把正在维修的机台改回「良好」—— "
                        + "设备状态是管理员盯着的事实，不是可以随便清空的描述字段");
    }

    @Test
    @DisplayName("修改：可选字段传 null 即清空（PUT 的全量替换语义）")
    void updateDevice_clearsOptionalFieldsWhenNull() {
        Device existing = deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        existing.setDeviceNo("PP-01");
        existing.setLocation("靠窗");
        existing.setRemark("旧备注");

        deviceService.updateDevice(101L, request("拍拍机 1 号", PAIPAI_ID));

        Device saved = deviceMapper.get(101L);
        assertNull(saved.getDeviceNo(), "编号可以撤掉");
        assertNull(saved.getLocation());
        assertNull(saved.getRemark());
    }

    @Test
    @DisplayName("修改：保留自己的编号不算重复")
    void updateDevice_allowsKeepingOwnDeviceNo() {
        Device existing = deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        existing.setDeviceNo("PP-01");

        DeviceRequest request = request("拍拍机 1 号（改个名）", PAIPAI_ID);
        request.setDeviceNo("PP-01");

        assertTrue(deviceService.updateDevice(101L, request).isSuccess(),
                "改别的字段时把自己的编号一起回传，不该被判成冲突");
    }

    @Test
    @DisplayName("修改：编号撞上其他机台被拒绝")
    void updateDevice_rejectsDuplicateDeviceNo() {
        Device other = deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        other.setDeviceNo("PP-01");
        Device existing = deviceMapper.seed(device(102L, "拍拍机 2 号", PAIPAI_ID, "NORMAL", 20));
        existing.setDeviceNo("PP-02");

        DeviceRequest request = request("拍拍机 2 号", PAIPAI_ID);
        request.setDeviceNo("PP-01");

        BizResult<DeviceVo> result = deviceService.updateDevice(102L, request);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_NO_EXISTS, result.getError());
    }

    @Test
    @DisplayName("修改：机台不存在")
    void updateDevice_notFound() {
        BizResult<DeviceVo> result = deviceService.updateDevice(999L, request("拍拍机", PAIPAI_ID));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("修改：类型不存在被拒绝")
    void updateDevice_rejectsUnknownType() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        BizResult<DeviceVo> result = deviceService.updateDevice(101L, request("拍拍机", 999L));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.EQUIPMENT_TYPE_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("修改：状况取值非法被拒绝")
    void updateDevice_rejectsInvalidStatus() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        DeviceRequest request = request("拍拍机 1 号", PAIPAI_ID);
        request.setStatus("BROKEN");

        BizResult<DeviceVo> result = deviceService.updateDevice(101L, request);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_STATUS_INVALID, result.getError());
    }

    // ==================================================================
    // 修改状况
    // ==================================================================

    @Test
    @DisplayName("改状况：成功")
    void updateStatus_success() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        BizResult<Void> result = deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        assertTrue(result.isSuccess());
        assertEquals("MAINTAINING", deviceMapper.get(101L).getStatus());
    }

    @Test
    @DisplayName("改状况：只动状况一列，不碰名称、位置与备注")
    void updateStatus_doesNotTouchOtherFields() {
        Device existing = deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        existing.setDeviceNo("PP-01");
        existing.setLocation("靠窗");
        existing.setRemark("运营备注");
        existing.setSort(30);

        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        Device saved = deviceMapper.get(101L);
        assertEquals("拍拍机 1 号", saved.getName(), "改状况不该碰到名称");
        assertEquals("PP-01", saved.getDeviceNo());
        assertEquals("靠窗", saved.getLocation());
        assertEquals("运营备注", saved.getRemark(), "备注尤其不能被清掉");
        assertEquals(30, saved.getSort().intValue());
        assertEquals(PAIPAI_ID, saved.getTypeId());
    }

    @Test
    @DisplayName("改状况：重复点两下不算错误")
    void updateStatus_isIdempotent() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "MAINTAINING", 10));

        BizResult<Void> result = deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        assertTrue(result.isSuccess(), "改成与当前相同的状况应当照常成功，前端不必先判断");
    }

    @Test
    @DisplayName("改状况：取值非法被拒绝")
    void updateStatus_rejectsInvalidStatus() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        BizResult<Void> result = deviceService.updateStatus(101L, statusRequest("BROKEN"));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_STATUS_INVALID, result.getError());
        assertEquals("NORMAL", deviceMapper.get(101L).getStatus(), "被拒绝时不该留下半截改动");
    }

    @Test
    @DisplayName("改状况：机台不存在")
    void updateStatus_notFound() {
        BizResult<Void> result = deviceService.updateStatus(999L, statusRequest("NORMAL"));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 删除机台
    // ==================================================================

    @Test
    @DisplayName("删除：逻辑删除后不再出现在陈列与列表里")
    void deleteDevice_success() {
        deviceMapper.seed(device(101L, "退役的机器", PAIPAI_ID, "NORMAL", 10));

        BizResult<Void> result = deviceService.deleteDevice(101L);

        assertTrue(result.isSuccess());
        assertEquals(1, deviceMapper.get(101L).getDeleted(),
                "退役走逻辑删除，记录仍留在库里可追溯");
        assertTrue(deviceService.listForDisplay().getData().isEmpty());
        assertTrue(deviceService.listDevices().getData().isEmpty());
    }

    @Test
    @DisplayName("删除：机台不存在")
    void deleteDevice_notFound() {
        BizResult<Void> result = deviceService.deleteDevice(999L);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 后台列表
    // ==================================================================

    @Test
    @DisplayName("后台列表：带上类型名与运营备注，维护中的也在")
    void listDevices_includesInternalFields() {
        Device device = deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "MAINTAINING", 10));
        device.setRemark("等屏幕配件到货");

        List<DeviceVo> devices = deviceService.listDevices().getData();

        assertEquals(1, devices.size(), "后台恰恰要靠它找到「哪几台要修」");
        assertEquals("拍拍机", devices.get(0).getTypeName());
        assertEquals("PAIPAI", devices.get(0).getTypeCode());
        assertEquals("维护中", devices.get(0).getStatusLabel());
        assertEquals("等屏幕配件到货", devices.get(0).getRemark());
    }

    @Test
    @DisplayName("后台列表：类型已被删除时类型字段留空，机台本身仍查得出来")
    void listDevices_toleratesMissingType() {
        deviceMapper.seed(device(101L, "某台老机器", 999L, "NORMAL", 10));

        List<DeviceVo> devices = deviceService.listDevices().getData();

        assertEquals(1, devices.size(), "机台本身是有效数据，不能因为字典缺一条就查不出来");
        assertNull(devices.get(0).getTypeName());
        assertNull(devices.get(0).getTypeCode());
        assertEquals(999L, devices.get(0).getTypeId(), "原始 ID 仍要给出，便于排查");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 构造一条机台。
     *
     * @param id      机台 ID
     * @param name    机台名称
     * @param typeId  类型 ID
     * @param status  状况名
     * @param sort    展示顺序
     * @return 机台实体
     */
    private static Device device(long id, String name, long typeId, String status, int sort) {
        Device device = new Device();
        device.setId(id);
        device.setStoreId(STORE_ID);
        device.setName(name);
        device.setTypeId(typeId);
        device.setStatus(status);
        device.setSort(sort);
        device.setDeleted(0);
        return device;
    }

    /**
     * 构造一条类型字典项。
     *
     * @param id   类型 ID
     * @param code 类型代码
     * @param name 类型名称
     * @param sort 排序权重
     * @return 类型实体
     */
    private static EquipmentType type(long id, String code, String name, int sort) {
        EquipmentType type = new EquipmentType();
        type.setId(id);
        type.setCode(code);
        type.setName(name);
        type.setSort(sort);
        type.setEnabled(1);
        type.setDeleted(0);
        return type;
    }

    /**
     * 构造一个只填必填项的机台请求。
     *
     * @param name   机台名称
     * @param typeId 类型 ID
     * @return 请求对象
     */
    private static DeviceRequest request(String name, Long typeId) {
        DeviceRequest request = new DeviceRequest();
        request.setName(name);
        request.setTypeId(typeId);
        return request;
    }

    /**
     * 构造一个改状况请求。
     *
     * @param status 状况名
     * @return 请求对象
     */
    private static UpdateDeviceStatusRequest statusRequest(String status) {
        UpdateDeviceStatusRequest request = new UpdateDeviceStatusRequest();
        request.setStatus(status);
        return request;
    }

    // ==================================================================
    // 与公告模块的联动
    //
    // 公告是一条【消息】，不是一份【状态】。所以这一组用例盯的不是
    // 「公告与当前状态一致」，而是「刚才发生的事有没有被记下来」：
    // 机台坏一次记一条、好一次再记一条，而先前那条维护消息【仍然在】——
    // 消息流里没有「撤销已经发生的事」这回事。
    //
    // 钩子挂漏一个不会编译报错、也不会让别的用例变红，
    // 只会让顾客在首页公告栏里看不到那台机器的动向。
    // ==================================================================

    @Test
    @DisplayName("公告联动：转维护中记一条「由 A 转为 B」")
    void notice_goesMaintaining_recordsChange() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        assertEquals(1, noticeMapper.aliveCount());
        assertEquals("拍拍机 1 号 由 良好 转为 维护中", noticeMapper.last().getTitle());
        assertEquals(NoticePublishMode.AUTO.name(), noticeMapper.last().getPublishMode());
        assertEquals(NoticeSourceType.DEVICE.name(), noticeMapper.last().getSourceType());
        assertEquals(101L, noticeMapper.last().getSourceId());
    }

    @Test
    @DisplayName("公告联动：修好后再记一条，且先前那条【仍在】—— 历史不被撤销")
    void notice_goesBackToNormal_recordsAnotherAndKeepsHistory() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));
        deviceService.updateStatus(101L, statusRequest("NORMAL"));

        assertEquals(2, noticeMapper.aliveCount(),
                "「坏过」与「修好了」是两件事、两条消息。"
                        + "把前一条撤掉或改掉，等于抹掉这段历史 —— 那是把公告当状态投影时的做法");
        assertEquals("拍拍机 1 号 由 维护中 转为 良好", noticeMapper.last().getTitle());

        // 两条按时间倒序展示时，读起来是一段过程
        assertEquals(List.of("拍拍机 1 号 由 维护中 转为 良好", "拍拍机 1 号 由 良好 转为 维护中"),
                noticeServiceOf().listForUser(null).getData().stream()
                        .map(vo -> vo.getTitle()).toList());
    }

    @Test
    @DisplayName("公告联动：良好 ↔ 待维护 也记一条（所有状态更新都记）")
    void notice_normalToNeedsRepair_recordsChange() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        deviceService.updateStatus(101L, statusRequest("NEEDS_REPAIR"));

        assertEquals(1, noticeMapper.aliveCount(), "「这台机器今天被标了待维护」本身就是一条值得记录的事实");
        assertEquals("拍拍机 1 号 由 良好 转为 待维护", noticeMapper.last().getTitle());
    }

    @Test
    @DisplayName("公告联动：走「编辑机台」改状况同样记一条（最容易漏的一处）")
    void notice_updateDeviceWithStatus_recordsChange() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        DeviceRequest request = request("拍拍机 1 号", PAIPAI_ID);
        request.setStatus("MAINTAINING");
        deviceService.updateDevice(101L, request);

        assertEquals(1, noticeMapper.aliveCount(),
                "管理员从「编辑机台」对话框里改状况，走的是 updateDevice 而不是 updateStatus —— "
                        + "只挂后者会漏掉这条最常见的路径，而且不报任何错");
    }

    @Test
    @DisplayName("公告联动：编辑机台时 status 传 null（状况没变），不记")
    void notice_updateDeviceWithNullStatus_recordsNothing() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        DeviceRequest request = request("拍拍机 1 号（改名了）", PAIPAI_ID);
        request.setStatus(null);
        deviceService.updateDevice(101L, request);

        assertEquals(0, noticeMapper.aliveCount(),
                "只是改了个名字，什么都没发生，不该往公告栏里塞一条");
    }

    @Test
    @DisplayName("公告联动：重复提交同一个状况，不记第二条")
    void notice_sameStatusTwice_recordsOnce() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));
        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));
        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        assertEquals(1, noticeMapper.aliveCount(),
                "对着已经在维护中的机器再点一次，什么都没发生。"
                        + "反复点几下就把公告栏刷满，会把真正要紧的信息挤下去");
    }

    @Test
    @DisplayName("公告联动：新增机台记一条「新增机台」")
    void notice_createDevice_recordsAddition() {
        DeviceRequest request = request("新到的拍拍机", PAIPAI_ID);
        request.setStatus("MAINTAINING");
        deviceService.createDevice(request);

        assertEquals(1, noticeMapper.aliveCount(),
                "新机到店也是发生了一件事，顾客看到陈列页多了台机器，公告栏里该有它的来历");
        assertEquals("新增机台 新到的拍拍机（维护中）", noticeMapper.last().getTitle());
    }

    @Test
    @DisplayName("公告联动：删除机台记一条「已退役」")
    void notice_deleteDevice_recordsRetirement() {
        deviceMapper.seed(device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10));

        deviceService.deleteDevice(101L);

        assertEquals(1, noticeMapper.aliveCount(),
                "顾客会发现陈列页上少了一台机器，公告栏里该有个交代");
        assertEquals("机台 拍拍机 1 号 已退役", noticeMapper.last().getTitle());
    }

    @Test
    @DisplayName("公告联动：公告标题里不含机台备注（运营内部信息）")
    void notice_neverLeaksDeviceRemark() {
        Device device = device(101L, "拍拍机 1 号", PAIPAI_ID, "NORMAL", 10);
        device.setRemark("等屏幕配件到货，张三反映左键不灵");
        deviceMapper.seed(device);

        deviceService.updateStatus(101L, statusRequest("MAINTAINING"));

        assertFalse(noticeMapper.last().getTitle().contains("配件"),
                "机台备注连匿名的 DeviceDisplayVo 都不含，公告更不该含 —— "
                        + "文案由 NoticeContents 生成，那边的方法签名里根本没有这个参数");
    }

    /** 便于断言「用户端看到什么」 */
    private NoticeService noticeServiceOf() {
        return new NoticeService(noticeMapper.asMapper());
    }
}
