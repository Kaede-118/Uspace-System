/**
 * 用户接口（模块 1）。
 *
 * <p>路径前缀 {@code /api/user}。注册匿名，其余需登录。
 */
import http from './http'

/**
 * 注册。
 *
 * <p>⚠️ <b>注册接口不返回 token</b> —— 模块 1 不依赖模块 2，前端注册成功后
 * 需要再调一次登录。这是刻意的，不是遗漏。
 *
 * @param {object} data 注册信息
 * @param {string} data.username   用户名，注册校验限死 {@code [a-zA-Z0-9_]{3,20}}
 * @param {string} data.password   明文密码
 * @param {string} [data.nickname] 昵称
 * @param {string} [data.phone]    手机号
 * @param {string} [data.qq]       QQ 号（模块 11 靠它定位用户）
 * @param {string} [data.preference] 游玩偏好，逗号分隔的类型 code
 * @returns {Promise} 成功时 data 里是新建的用户资料
 */
export function register(data) {
  return http.post('/api/user/register', data)
}

/**
 * 查自己的资料。
 *
 * @returns {Promise<{data:{id, username, nickname, phone, qq, preference, avatar, banner, role, status, totalPaid, orderPaid, cardPaid}}>}
 *          路径里不出现用户 ID，身份一律从凭证取
 */
export function getProfile() {
  return http.get('/api/user/me')
}

/**
 * 改自己的资料。
 *
 * <p>⚠️ <b>这是「全量替换」语义：传 {@code null} 表示清空该项</b>。
 * 页面必须先把当前值全部回填、提交时一起带上 ——
 * 只提交改动的字段，会把手机号、QQ 号一起清掉，而且不报任何错。
 *
 * <p>⚠️ <b>头像与背景图不走这个接口</b>（它们各有独立的上传端点）：
 * 混进来的话，上面那条「只提交改动字段」的后果就变成「把头像清空」。
 *
 * @param {object} data 资料
 * @param {string} [data.nickname]   昵称，传 null 清空
 * @param {string} [data.phone]      手机号，传 null 清空
 * @param {string} [data.qq]         QQ 号，传 null 清空
 * @param {string} [data.preference] 游玩偏好，传 null 清空
 * @returns {Promise} 成功时 data 里是更新后的完整资料
 */
export function updateProfile(data) {
  return http.put('/api/user/me', data)
}

/**
 * 改密码。
 *
 * <p>改密成功后服务端会升 {@code token_version}，<b>所有旧凭证立即失效</b> ——
 * 包括当前这一个。前端拿到成功响应后应当清登录态并跳登录页。
 *
 * @param {string} oldPassword 原密码
 * @param {string} newPassword 新密码
 * @returns {Promise}
 */
export function changePassword(oldPassword, newPassword) {
  return http.put('/api/user/me/password', { oldPassword, newPassword })
}

/**
 * 上传头像（multipart）。
 *
 * @param {File} file 图片文件。后端按【文件头】判类型（JPG / PNG / WebP），
 *                    不看 Content-Type；上限 2MB
 * @returns {Promise} 成功时 data 是完整的用户资料（前端一行 setUser 即可）
 */
export function uploadAvatar(file) {
  return uploadImage('/api/user/me/avatar', file)
}

/**
 * 上传背景图（multipart）。
 *
 * @param {File} file 图片文件，约 6:1 横长图。后端不做宽高比校验，
 *                    比例由前端 {@code object-fit: cover} 裁切
 * @returns {Promise} 成功时 data 是完整的用户资料
 */
export function uploadBanner(file) {
  return uploadImage('/api/user/me/banner', file)
}

/**
 * 上传图片的公共实现。
 *
 * <p>⚠️ <b>不要手动设 {@code Content-Type}</b>：必须让浏览器自己带上
 * {@code multipart/form-data; boundary=...}，手写的那个字符串里没有 boundary，
 * 后端解析不出任何字段，报的还是「请选择要上传的图片」——看起来像没选文件。
 *
 * @param {string} url  上传端点
 * @param {File}   file 文件
 * @returns {Promise}
 */
function uploadImage(url, file) {
  const form = new FormData()
  // 字段名固定为 file（后端 @RequestParam("file")），改成别的会返回 400
  form.append('file', file)
  return http.post(url, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
