/**
 * 图片上传的公共实现（用户端与运营后台共用）。
 *
 * <p><b>为什么值得为这几行单开一个文件</b>：里面藏着两条「写错不报错」的约定 ——
 * 表单字段名必须是 `file`，`Content-Type` 必须由浏览器自己带上 boundary。
 * 在 `admin.js` 里复制一份，等于把这两条约定连同注释也复制了一份，
 * 而两份迟到会分岔（改了一处忘了另一处，症状还是一样难查）。
 */
import http from './http'

/**
 * 上传一张图片。
 *
 * <p>⚠️ <b>不要手动设 `Content-Type`</b>：必须让浏览器自己带上
 * `multipart/form-data; boundary=...`，而手写的那个字符串里没有 boundary ——
 * 后端会解析不出任何字段，报的还是「请选择要上传的图片」，看起来像没选文件。
 * axios 认出 `FormData` 之后会自己补这个头，所以这里一个字都不用写。
 *
 * @param {string} url  上传端点
 * @param {File}   file 图片文件
 * @returns {Promise} 成功时 data 的结构由各端点自定（用户资料视图 / {cover} 等）
 */
export function uploadImage(url, file) {
  const form = new FormData()
  // 字段名固定为 file（后端 @RequestParam("file")），改成别的会返回 400
  form.append('file', file)
  return http.post(url, form)
}
