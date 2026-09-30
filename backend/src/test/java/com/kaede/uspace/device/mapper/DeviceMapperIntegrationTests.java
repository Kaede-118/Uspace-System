package com.kaede.uspace.device.mapper;

import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.entity.EquipmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeviceMapper} 与 {@link EquipmentTypeMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * ID 与类型 code 取 999xxx 这类可辨认的值，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够、必须连真库</b>：假实现模拟的是数据库行为的语义，
 * 却绕过了真实 SQL —— 列名映射写错、{@code deleted = 0} 漏写、
 * 新增的列没被映射，这些问题在假 Mapper 面前统统不会暴露。本模块尤其要紧的是
 * <b>两个类型查询的过滤口径</b>：{@code selectListAll} 不过滤 {@code enabled}、
 * {@code selectEnabledList} 过滤 —— 写反了不会有任何报错，
 * 只会让停用类型下的机台整批从陈列页消失。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class DeviceMapperIntegrationTests {

    /** 测试用的门店 ID。取大数以免与真实数据混淆 */
    private static final Long STORE_ID = 999901L;

    /** 另一个门店，用来验证列表按门店隔离 */
    private static final Long OTHER_STORE_ID = 999902L;

    /** 测试用的类型 code，取 999 前缀避开字典表里的真实取值 */
    private static final String ENABLED_CODE = "TEST999A";
    private static final String DISABLED_CODE = "TEST999B";

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private EquipmentTypeMapper equipmentTypeMapper;

    // ==================================================================
    // 机台：落库与映射
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充审计字段并回填主键")
    void insert_fillsAuditFieldsAndId() {
        Device device = newDevice("拍拍机 1 号", 10);

        int affected = deviceMapper.insert(device);

        assertEquals(1, affected, "插入一行");
        assertNotNull(device.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(device.getCreatedAt(), "created_at 是 NOT NULL 且无默认值，靠自动填充补上");
        assertNotNull(device.getUpdatedAt(), "updated_at 同理");
    }

    @Test
    @DisplayName("新增的列都能正确落库与读出")
    void insert_mapsAllColumns() {
        Device device = newDevice("拍拍机 1 号", 10);
        device.setDeviceNo("PP-999");
        device.setLocation("靠窗第二台");
        device.setStatus("NEEDS_REPAIR");
        device.setRemark("等屏幕配件到货");

        deviceMapper.insert(device);
        Device loaded = deviceMapper.selectById(device.getId());

        assertEquals("PP-999", loaded.getDeviceNo());
        assertEquals("靠窗第二台", loaded.getLocation());
        assertEquals("NEEDS_REPAIR", loaded.getStatus(),
                "状况列是 VARCHAR，映射写错不会报错，只有查回来才知道");
        assertEquals("等屏幕配件到货", loaded.getRemark());
        assertEquals(10, loaded.getSort().intValue());
    }

    @Test
    @DisplayName("逻辑删除后按 ID 查不到")
    void delete_filtersByLogicDelete() {
        Device device = newDevice("退役的机器", 10);
        deviceMapper.insert(device);

        deviceMapper.deleteById(device.getId());

        assertNull(deviceMapper.selectById(device.getId()),
                "逻辑删除要真的被全局配置改写成 UPDATE，而不是物理删除");
        assertTrue(deviceMapper.selectListByStore(STORE_ID).stream()
                        .noneMatch(d -> d.getId().equals(device.getId())),
                "手写 SQL 里的 deleted = 0 也不能漏");
    }

    // ==================================================================
    // 机台：列表查询
    // ==================================================================

    @Test
    @DisplayName("列表按 sort 升序、id 升序排列")
    void selectListByStore_ordersBySortThenId() {
        Device third = newDevice("第三台", 30);
        Device first = newDevice("第一台", 10);
        Device second = newDevice("第二台", 10);
        deviceMapper.insert(third);
        deviceMapper.insert(first);
        deviceMapper.insert(second);

        List<Device> devices = deviceMapper.selectListByStore(STORE_ID);

        assertEquals(3, devices.size());
        assertEquals("第一台", devices.get(0).getName());
        assertEquals("第二台", devices.get(1).getName(),
                "sort 相同时按 id 排 —— 顺序不稳定的话页面上看起来像在随机抖动");
        assertEquals("第三台", devices.get(2).getName());
    }

    @Test
    @DisplayName("列表按门店隔离")
    void selectListByStore_scopesByStore() {
        deviceMapper.insert(newDevice("本店机器", 10));
        Device other = newDevice("别店机器", 20);
        other.setStoreId(OTHER_STORE_ID);
        deviceMapper.insert(other);

        List<Device> devices = deviceMapper.selectListByStore(STORE_ID);

        assertEquals(1, devices.size());
        assertEquals("本店机器", devices.get(0).getName());
    }

    // ==================================================================
    // 机台：编号查重
    // ==================================================================

    @Test
    @DisplayName("编号查重：命中、排除自己、空编号不参与")
    void countByDeviceNo() {
        Device device = newDevice("拍拍机 1 号", 10);
        device.setDeviceNo("PP-999");
        deviceMapper.insert(device);

        assertEquals(1, deviceMapper.countByDeviceNo(STORE_ID, "PP-999", null));
        assertEquals(0, deviceMapper.countByDeviceNo(STORE_ID, "PP-999", device.getId()),
                "改别的字段时回传自己的编号，不该算冲突");
        assertEquals(0, deviceMapper.countByDeviceNo(OTHER_STORE_ID, "PP-999", null),
                "编号只在同门店内要求唯一");
        assertEquals(0, deviceMapper.countByDeviceNo(STORE_ID, null, null),
                "SQL 里 NULL = NULL 不成立，空编号恒返回 0 —— 多台没贴编号的机器不冲突");
    }

    // ==================================================================
    // 机台：更新
    // ==================================================================

    @Test
    @DisplayName("全量更新能清空可选字段")
    void updateDevice_clearsNullableFields() {
        Device device = newDevice("拍拍机 1 号", 10);
        device.setDeviceNo("PP-999");
        device.setLocation("靠窗");
        device.setRemark("旧备注");
        deviceMapper.insert(device);

        int affected = deviceMapper.updateDevice(device.getId(), "拍拍机 1 号（改名）",
                null, 20L, null, "MAINTAINING", 50, null);

        assertEquals(1, affected);
        Device loaded = deviceMapper.selectById(device.getId());
        assertEquals("拍拍机 1 号（改名）", loaded.getName());
        assertNull(loaded.getDeviceNo(), "显式 SQL 才能把字段真的置为 NULL");
        assertNull(loaded.getLocation());
        assertNull(loaded.getRemark());
        assertEquals(20L, loaded.getTypeId());
        assertEquals("MAINTAINING", loaded.getStatus());
        assertEquals(50, loaded.getSort().intValue());
    }

    @Test
    @DisplayName("只改状况时其他字段原封不动")
    void updateStatus_touchesOnlyStatusColumn() {
        Device device = newDevice("拍拍机 1 号", 10);
        device.setDeviceNo("PP-999");
        device.setLocation("靠窗第二台");
        device.setRemark("运营备注");
        deviceMapper.insert(device);

        deviceMapper.updateStatus(device.getId(), "MAINTAINING");

        Device loaded = deviceMapper.selectById(device.getId());
        assertEquals("MAINTAINING", loaded.getStatus());
        assertEquals("拍拍机 1 号", loaded.getName(), "这条语句只该动 status 一列");
        assertEquals("PP-999", loaded.getDeviceNo());
        assertEquals("靠窗第二台", loaded.getLocation());
        assertEquals("运营备注", loaded.getRemark());
    }

    // ==================================================================
    // 类型字典：两个查询的过滤口径
    // ==================================================================

    @Test
    @DisplayName("全量类型查询不过滤 enabled，停用类型下的机台才查得到名字")
    void selectListAll_includesDisabled() {
        EquipmentType enabled = newType(ENABLED_CODE, "测试类型甲", 999, 1);
        EquipmentType disabled = newType(DISABLED_CODE, "测试类型乙", 999, 0);
        equipmentTypeMapper.insert(enabled);
        equipmentTypeMapper.insert(disabled);

        List<EquipmentType> all = equipmentTypeMapper.selectListAll();
        List<Long> ids = all.stream().map(EquipmentType::getId).toList();

        assertTrue(ids.contains(enabled.getId()));
        assertTrue(ids.contains(disabled.getId()),
                "陈列组装靠这条查到停用类型的名字 —— 过滤掉的话，挂在它下面的机台会整批消失");
    }

    @Test
    @DisplayName("可选类型查询过滤掉停用项")
    void selectEnabledList_excludesDisabled() {
        EquipmentType enabled = newType(ENABLED_CODE, "测试类型甲", 999, 1);
        EquipmentType disabled = newType(DISABLED_CODE, "测试类型乙", 999, 0);
        equipmentTypeMapper.insert(enabled);
        equipmentTypeMapper.insert(disabled);

        List<Long> ids = equipmentTypeMapper.selectEnabledList().stream()
                .map(EquipmentType::getId).toList();

        assertTrue(ids.contains(enabled.getId()));
        assertTrue(!ids.contains(disabled.getId()),
                "顾客选偏好、管理员选类型时都不该看到已停用的取值");
    }

    @Test
    @DisplayName("按 ID 查类型：停用的也查得到，删掉的查不到")
    void selectTypeById() {
        EquipmentType disabled = newType(DISABLED_CODE, "测试类型乙", 999, 0);
        equipmentTypeMapper.insert(disabled);

        assertNotNull(equipmentTypeMapper.selectTypeById(disabled.getId()),
                "把老机器改回它原本那个已停用的类型是合法操作，这里不能过滤 enabled");

        equipmentTypeMapper.deleteById(disabled.getId());
        assertNull(equipmentTypeMapper.selectTypeById(disabled.getId()));
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 构造一条机台。
     *
     * @param name 机台名称
     * @param sort 展示顺序
     * @return 机台实体
     */
    private static Device newDevice(String name, int sort) {
        Device device = new Device();
        device.setStoreId(STORE_ID);
        device.setName(name);
        device.setTypeId(1L);
        device.setStatus("NORMAL");
        device.setSort(sort);
        return device;
    }

    /**
     * 构造一条类型字典项。
     *
     * @param code    类型代码
     * @param name    类型名称
     * @param sort    排序权重
     * @param enabled 是否启用
     * @return 类型实体
     */
    private static EquipmentType newType(String code, String name, int sort, int enabled) {
        EquipmentType type = new EquipmentType();
        type.setCode(code);
        type.setName(name);
        type.setSort(sort);
        type.setEnabled(enabled);
        return type;
    }
}
