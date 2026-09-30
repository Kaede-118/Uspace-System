package com.kaede.uspace.device;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeviceStatus} 的单元测试。
 *
 * <p>枚举本身只有三个取值，但有两处判断会被别处依赖，各钉一条：
 * <ol>
 *   <li>{@code isValid} —— Service 拿它当落库前的唯一校验，认错了就会让
 *       {@code BROKEN} 这种值写进 {@code VARCHAR} 列，而库不会拦</li>
 *   <li>{@code isUsable} —— 「待维护算可用」是这一态存在的意义所在，
 *       改成 false 就等于把三态悄悄压回了两态</li>
 * </ol>
 */
class DeviceStatusTests {

    @Test
    @DisplayName("合法取值：三个枚举名都认，其余一律不认")
    void isValid() {
        assertTrue(DeviceStatus.isValid("NORMAL"));
        assertTrue(DeviceStatus.isValid("NEEDS_REPAIR"));
        assertTrue(DeviceStatus.isValid("MAINTAINING"));

        assertFalse(DeviceStatus.isValid("BROKEN"), "大小写与小写法都不该蒙混过关");
        assertFalse(DeviceStatus.isValid("normal"));
        assertFalse(DeviceStatus.isValid("良好"));
        assertFalse(DeviceStatus.isValid(""));
        assertFalse(DeviceStatus.isValid(null));
    }

    @Test
    @DisplayName("可用性：良好与待维护可用，维护中不可用")
    void isUsable() {
        assertTrue(DeviceStatus.isUsable("NORMAL"));
        assertTrue(DeviceStatus.isUsable("NEEDS_REPAIR"),
                "「待维护」的语义就是还能玩、只是有小毛病 —— 判成不可用等于砍掉了这一态");

        assertFalse(DeviceStatus.isUsable("MAINTAINING"));
        assertFalse(DeviceStatus.isUsable("BROKEN"), "认不出的取值不能当成可用放过去");
        assertFalse(DeviceStatus.isUsable(null));
    }

    @Test
    @DisplayName("中文说明：三个取值各有标签，认不出的原样返回")
    void labelOf() {
        assertEquals("良好", DeviceStatus.labelOf("NORMAL"));
        assertEquals("待维护", DeviceStatus.labelOf("NEEDS_REPAIR"));
        assertEquals("维护中", DeviceStatus.labelOf("MAINTAINING"));

        assertEquals("BROKEN", DeviceStatus.labelOf("BROKEN"),
                "库里被手工改成别的值时原样返回，让异常数据在界面上一眼可见 —— "
                        + "伪装成正常标签反而会把它藏起来");
        assertNull(DeviceStatus.labelOf(null));
    }

    @Test
    @DisplayName("枚举名与建表脚本注释里的取值一致")
    void enumNamesMatchSchema() {
        assertEquals(3, DeviceStatus.values().length,
                "建表脚本的 status 列注释写死了三个取值，加减枚举项时必须同步改注释");
        assertEquals("NORMAL", DeviceStatus.NORMAL.name());
        assertEquals("NEEDS_REPAIR", DeviceStatus.NEEDS_REPAIR.name());
        assertEquals("MAINTAINING", DeviceStatus.MAINTAINING.name());
    }
}
