package com.kaede.uspace.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.device.entity.Device;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 机台台账的数据访问接口。
 *
 * <p><b>为什么一个分页方法都没有</b>：机台是「店里有几台机器」，
 * 十几到几十条封顶，且用户端陈列本来就要一次取全。为这个量级套分页，
 * 只会让前端多写一轮翻页逻辑、后端多一个用不上的参数。
 * 什么时候真开成几十家分店、单店机台上百台，再回来加分页。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法，对注解里手写的 SQL 不生效 ——
 * 漏掉的后果是「已退役的机台还挂在陈列页上」，从界面上完全看不出来。
 */
public interface DeviceMapper extends BaseMapper<Device> {

    /**
     * 查询某门店的全部机台，按展示顺序排列。
     *
     * <p>用户端陈列与后台列表共用这一条：两者要的是同一批数据，
     * 差别只在「展示哪些字段」（用户端不含备注），那由各自的 VO 决定，
     * 不必分成两条 SQL。
     *
     * <p>排序用 {@code sort ASC, id ASC}：{@code sort} 是管理员可调的展示顺序；
     * 补上 {@code id} 是让<b>权重相同时的顺序稳定</b> —— 否则同权重的几台
     * 每次查询可能以不同顺序返回，页面看起来像在随机抖动。
     *
     * <p>不加「状态筛选」参数：维护中的机台<b>照样要陈列</b>
     * （藏起来会让顾客以为机器搬走了），后台要筛选由前端自己过滤即可。
     *
     * @param storeId 门店 ID
     * @return 机台列表；没有机台时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_device
             WHERE deleted = 0
               AND store_id = #{storeId}
             ORDER BY sort ASC, id ASC
            """)
    List<Device> selectListByStore(@Param("storeId") Long storeId);

    /**
     * 统计同门店内编号重复的机台数。
     *
     * <p>用于录入前的查重，给出「该编号已被占用」这样的友好提示。
     * 真正保证唯一的是库上的唯一索引 {@code uk_store_device_no} ——
     * 应用层查重与插入之间存在竞态窗口，并发时由索引兜底。
     *
     * <p><b>调用方须先排除编号为空的情形</b>：本方法用 {@code =} 比较，
     * 而 SQL 里 {@code NULL = NULL} 不成立，编号为空时这里恒返回 0。
     * 行为虽然「碰巧正确」（多台没贴编号的机器本就不该算冲突），
     * 但把它当成约定写进注释，免得读代码的人以为查重漏了空值分支。
     *
     * @param storeId   门店 ID
     * @param deviceNo  待查的资产编号，非空
     * @param excludeId 要排除的机台 ID（修改自己时传自己的 ID），新增时传 null
     * @return 重复的机台数；0 表示可用
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_device
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND device_no = #{deviceNo}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countByDeviceNo(@Param("storeId") Long storeId,
                        @Param("deviceNo") String deviceNo,
                        @Param("excludeId") Long excludeId);

    /**
     * 更新机台全部业务字段。
     *
     * <p>用显式 SQL 而非 {@code updateById}，是为了配合 PUT 的全量替换语义：
     * 传 null 即清空该字段（如撤掉位置描述）。{@code updateById} 的默认策略
     * 会忽略 null 字段，那样位置就永远删不掉了。理由同
     * {@code StoreMapper#updateStore}。
     *
     * @param id       机台 ID
     * @param name     机台名称
     * @param deviceNo 资产编号，可为 null
     * @param typeId   设备类型 ID
     * @param location 位置描述，可为 null
     * @param status   状况名
     * @param sort     展示顺序
     * @param remark   备注，可为 null
     * @return 受影响行数；0 表示机台不存在或已删除
     */
    @Update("""
            UPDATE biz_device
               SET name       = #{name},
                   device_no  = #{deviceNo},
                   type_id    = #{typeId},
                   location   = #{location},
                   status     = #{status},
                   sort       = #{sort},
                   remark     = #{remark},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateDevice(@Param("id") Long id,
                     @Param("name") String name,
                     @Param("deviceNo") String deviceNo,
                     @Param("typeId") Long typeId,
                     @Param("location") String location,
                     @Param("status") String status,
                     @Param("sort") Integer sort,
                     @Param("remark") String remark);

    /**
     * 只更新机台状况。
     *
     * <p><b>单独开一个方法，而不是让调用方走全量替换</b>：改状况是运营里最高频的
     * 动作（一台机器坏了、修好了都在点它）。走全量替换意味着前端要先把整条记录读出来、
     * 改一个字段、再整个传回去 —— 中间若有别人改了名称或位置，这次提交会<b>静默覆盖</b>
     * 对方的改动。只更新一列的语句没有这个窗口。
     *
     * @param id     机台 ID
     * @param status 新的状况名
     * @return 受影响行数；0 表示机台不存在或已删除
     */
    @Update("""
            UPDATE biz_device
               SET status     = #{status},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
