/**
 * 设备接口（模块 4）。
 *
 * <p>路径前缀 {@code /api/devices}，<b>两个接口都匿名可访问</b> ——
 * 店里有什么机器、每台是不是在维护，是顾客走进店门就能亲眼看到的事。
 */
import http from './http'

/**
 * 查机台陈列（按类型分组）。
 *
 * <p>「维护中」的机台<b>照样陈列</b>，不会藏起来 —— 藏起来会让顾客以为
 * 机器搬走了。是否压暗用后端给的 {@code usable}，不要自己按状态名判断
 * （判宽判窄都是静默的错，两边的口径还容易分岔）。
 *
 * <p>⚠️ 字段名实测核对过，两处容易猜错的：
 * 分组的标识是 <b>{@code typeCode}</b>（不是 typeId）；
 * 机台状况的中文是 <b>{@code statusLabel}</b>（不是 statusText）。
 *
 * @returns {Promise<{data:Array<{typeCode, typeName, devices:Array}>}>}
 *          devices 每项含 {@code id, name, deviceNo, location, status, statusLabel, usable}
 */
export function listDevices() {
  return http.get('/api/devices')
}

/**
 * 查可选的设备类型（字典）。
 *
 * <p>两个用途：用户设置游玩偏好时的选项来源，以及<b>把在店名册里的偏好 code
 * 翻译成中文名</b>（后端不给 preferenceText，见 `api/store.js` 的说明）。
 *
 * <p>只返回<b>启用中</b>的类型 —— 停用类型下的机台仍陈列在门店里
 * （那是另一条查询），但不应再作为新偏好被选中。
 *
 * @returns {Promise<{data:Array<{id, code, name}>}>}
 */
export function listEquipmentTypes() {
  return http.get('/api/devices/types')
}
