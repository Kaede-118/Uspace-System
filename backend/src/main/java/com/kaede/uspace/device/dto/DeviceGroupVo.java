package com.kaede.uspace.device.dto;

import lombok.Data;

import java.util.List;

/**
 * 按类型分组的机台陈列（用户端）。
 *
 * <p><b>分组为什么在后端做</b>：分组的顺序由字典表的 {@code sort} 决定，
 * 那是运营数据而非展示偏好；放在后端，网页与将来的套壳 App 才会得到同一个顺序。
 * 前端要做「门店信息页的精简版」（几台良好、几台待维护），从同一份数据里
 * 自行统计即可，不必再开一个接口 —— 精简与否是展示问题，缓存两份数据
 * 反而会出现「首页说 6 台、设施页说 5 台」这种自相矛盾。
 *
 * <p>组内的机台已按展示顺序排好，且<b>含维护中的机台</b> ——
 * 陈列的目的就是让顾客看到「这台在修」，隐藏会造成机器搬走了的误解。
 */
@Data
public class DeviceGroupVo {

    /** 类型代码，如 {@code PAIPAI}。类型已被删除时为空 */
    private String typeCode;

    /** 类型名称，如「拍拍机」。前端用作分组标题 */
    private String typeName;

    /** 该类型下的机台，按展示顺序排列 */
    private List<DeviceDisplayVo> devices;
}
