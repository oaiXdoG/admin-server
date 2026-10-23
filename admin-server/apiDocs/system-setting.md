# 系统设置

所有人看到的全局品牌信息，存在 `system_setting` 表（键值）。主题、布局等个人偏好由前端「偏好设置」各自保存，不在这里。

## 读取

`POST /system/setting/get`，请求体 `{}`。**不需要登录**，登录页也要显示系统名称和 Logo。

返回字段：`appName`（未设置时为 `admin-front`）、`logo`、`favicon`、`loginTitle`、`loginDescription`、`copyright`。未设置的字段为空字符串。

## 修改

`POST /system/setting/update`，需要按钮权限 `manage_setting:update`。提交全部字段，整体覆盖。

```json
{"appName":"运营后台","logo":"data:image/png;base64,...","favicon":"data:image/png;base64,...","loginTitle":"欢迎使用","loginDescription":"运营管理平台","copyright":"© 2026 某某公司"}
```

`appName` 必填最长 32；`loginTitle`、`loginDescription`、`copyright` 最长 128；`logo` 最长 280000 个字符，`favicon` 最长 100000 个字符，为空表示使用默认图标，否则必须是图片 data URL（支持 png / jpeg / webp / gif / ico / svg）。成功返回修改后的设置。

错误：`100001` 参数不合法，`100003` 未登录，`100016` 无权访问，`100039` 图片格式不支持或超过大小限制。
