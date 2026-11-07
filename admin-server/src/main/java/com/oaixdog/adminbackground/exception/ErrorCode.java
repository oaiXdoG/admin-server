package com.oaixdog.adminbackground.exception;

/**
 * 业务错误码。
 * 只表示接口对这次请求做出的业务判断。
 * 数据库连不上、空指针、未知异常不在这里，接口也不把它们包装成错误码返回。
 */
public enum ErrorCode {

    /**
     * 判断的是入参本身：字段为空，或超出接口约定的长度。登录里是 account、password 为空或超长，以及请求体无法按登录请求读取。
     * 不表示账号不存在，也不表示服务器内部故障。
     */
    INVALID_REQUEST(100001, "请求参数不合法"),

    /**
     * 判断的是登录凭证：账号不存在，或密码与账号表 password 不一致。两种情况同一个码，不告诉调用方是哪一种。
     * 不表示查询过程中的数据库故障。
     */
    LOGIN_FAILED(100002, "账号或密码错误"),

    /**
     * 判断的是后续请求的令牌：没有携带，或已经失效。
     * 登录接口不抛这个码。不表示服务器拒绝连接。
     */
    UNAUTHENTICATED(100003, "未登录"),

    /**
     * 判断的是登录名已经存在，不能再创建一条相同的 account。
     * 不表示密码不合法，也不表示当前操作者不是管理员。
     */
    ACCOUNT_EXISTS(100006, "登录名已存在"),

    /**
     * 判断的是当前账号没有用户管理菜单权限，不能创建账号。
     * 不表示令牌失效，也不表示登录名或密码不合法。
     */
    ACCOUNT_CREATE_FORBIDDEN(100007, "无权创建账号"),

    /**
     * 判断的是当前账号没有菜单管理权限，不能维护菜单。
     * 不表示令牌失效，也不表示菜单字段不合法。
     */
    MENU_MANAGE_FORBIDDEN(100008, "无权管理菜单"),

    /**
     * 判断的是要操作的菜单不存在。
     * 不表示父级菜单不合法，也不表示菜单下还有内容。
     */
    MENU_NOT_FOUND(100009, "菜单不存在"),

    /**
     * 判断的是父级不能用：不存在、选了自己、选了自己的下级，或父级类型不对。
     * 目录和菜单只能挂在目录下面，按钮只能挂在菜单下面。
     */
    MENU_PARENT_INVALID(100010, "父级菜单不合法"),

    /**
     * 判断的是下面还有子项，不能删除。
     * 不表示菜单不存在。
     */
    MENU_NOT_EMPTY(100011, "下面还有子项，不能删除"),

    /**
     * 判断的是现有子项与新类型不匹配：目录下只能有目录和菜单，菜单下只能有按钮，按钮下不能有子项。
     * 不表示删除。
     */
    MENU_TYPE_CHANGE_FORBIDDEN(100015, "下面的子项与该类型不匹配，不能修改类型"),

    PERMISSION_DENIED(100016, "无权访问"),

    BUILT_IN_MENU_FORBIDDEN(100017, "内置菜单不能修改或删除"),

    ROLE_NOT_FOUND(100018, "角色不存在"),
    ROLE_NAME_EXISTS(100019, "角色名称已存在"),
    BUILT_IN_ROLE_FORBIDDEN(100020, "内置角色不能修改或删除"),
    ROLE_IN_USE(100021, "角色正在使用，不能删除"),
    ROLE_MENU_INVALID(100022, "角色菜单分配不合法"),
    ROLE_CODE_EXISTS(100023, "角色编码已存在"),
    MENU_ID_EXISTS(100026, "菜单 ID 已存在"),
    ACCOUNT_NOT_FOUND(100028, "账号不存在"),
    MENU_ROUTE_NAME_EXISTS(100030, "路由名称已被其他菜单使用"),
    ACCOUNT_DISABLED(100031, "账号已禁用"),
    SELF_OPERATION_FORBIDDEN(100035, "不能对自己执行该操作"),
    SUPER_ADMIN_REQUIRED(100036, "只有超级管理员可以分配超级管理员角色"),
    MENU_BLOCK_FULL(100037, "该顶级目录下的菜单数量已达上限"),
    PASSWORD_INCORRECT(100038, "原密码不正确"),
    IMAGE_INVALID(100039, "图片格式不支持或超过大小限制"),
    SECRET_KEY_MISSING(100041, "服务器未配置加密密钥 security.secret-key"),

    /**
     * 判断的是同一账号或同一 IP 最近 15 分钟登录失败次数过多，暂时不再校验密码。
     * 不表示账号被禁用。
     */
    LOGIN_LOCKED(100049, "登录失败次数过多，请 15 分钟后再试"),
    PASSWORD_TOO_WEAK(100050, "密码至少 8 位，并且同时包含字母和数字"),
    BACKUP_RUNNING(100051, "正在备份，请稍后再试"),
    BACKUP_NOT_FOUND(100052, "备份文件不存在"),
    BACKUP_TOO_LARGE(100053, "备份文件超过 50MB，请到服务器上直接复制"),
    DATA_SOURCE_NOT_FOUND(100054, "数据源不存在"),
    DATA_SOURCE_NAME_EXISTS(100055, "数据源名称已存在"),
    DATA_SOURCE_IN_USE(100056, "数据源正在被查询模板使用，不能删除"),
    DATA_SOURCE_FAILED(100057, "业务数据源请求失败"),
    DATA_TEMPLATE_NOT_FOUND(100058, "查询模板不存在"),
    DATA_TEMPLATE_NAME_EXISTS(100059, "查询模板名称已存在"),
    DATA_TEMPLATE_INVALID(100060, "查询模板配置不合法"),
    DATA_QUERY_PARAM_INVALID(100061, "查询参数不合法"),

    /**
     * 判断的是没有在线的日志服务（没有收到服务类型 log 的上报，或上报的连接已断开）。
     * 读取、保存日志事件和日志查询时检查。
     */
    LOG_SERVICE_UNAVAILABLE(100062, "当前没有可用的服务"),
    LOG_EVENT_NOT_FOUND(100063, "日志事件不存在"),
    LOG_EVENT_EXISTS(100064, "事件已存在"),
    LOG_EVENT_INVALID(100065, "日志事件配置不合法"),
    LOG_QUERY_CONDITION_INVALID(100066, "日志查询条件不合法"),
    LOG_QUERY_FAILED(100067, "日志查询失败"),
    OPS_HOST_NOT_FOUND(100068, "主机不存在"),

    OPS_HOST_IN_USE(100071, "主机还有程序绑定，不能删除"),
    OPS_APP_NOT_FOUND(100072, "程序不存在"),
    OPS_APP_DIR_EXISTS(100073, "这台主机上已有程序使用该目录"),
    OPS_APP_INVALID(100074, "程序配置不合法"),
    OPS_APP_RUNNING(100075, "程序正在运行，请先停止"),
    OPS_ANSIBLE_FAILED(100076, "执行 Ansible 失败"),
    OPS_HOST_EXISTS(100077, "该 IP 的主机已存在"),
    OPS_HOST_INVALID(100078, "主机配置不合法"),
    OPS_APP_NAME_EXISTS(100079, "这台主机上已有同名程序"),
    OPS_TASK_BUSY(100080, "有任务正在执行，请等它结束"),
    OPS_TASK_NOT_FOUND(100081, "任务不存在"),
    OPS_UPLOAD_NOT_FOUND(100082, "上传不存在或已过期，请重新上传");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
