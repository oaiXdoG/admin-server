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
     * 判断的是当前账号没有项目管理菜单权限，不能创建项目。
     * 不表示令牌失效，也不表示项目名不合法。
     */
    PROJECT_CREATE_FORBIDDEN(100004, "无权创建项目"),

    /**
     * 判断的是目标项目没有挂在当前账号上，不能把它记为当前项目。
     * 项目不存在和未挂载是同一个码，不区分。不表示令牌失效。
     */
    PROJECT_SWITCH_FORBIDDEN(100005, "不能切换到该项目"),

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
    PROJECT_APP_KEY_EXISTS(100024, "App Key 已存在"),
    PROJECT_NOT_FOUND(100025, "项目不存在或已关闭"),
    MENU_ID_EXISTS(100026, "菜单 ID 已存在"),
    PROJECT_CONTEXT_MISMATCH(100027, "请求项目与当前项目不一致，请切换到正确项目后重试"),
    ACCOUNT_NOT_FOUND(100028, "账号不存在"),
    ACCOUNT_PROJECT_INVALID(100029, "项目关联不合法"),
    MENU_ROUTE_NAME_EXISTS(100030, "路由名称已被其他菜单使用"),
    ACCOUNT_DISABLED(100031, "账号已禁用"),
    MENU_SCOPE_CHANGE_FORBIDDEN(100032, "有子项的顶级目录不能修改范围"),
    MEMBER_EXISTS(100033, "该账号已经是项目成员"),
    MEMBER_NOT_FOUND(100034, "项目成员不存在"),
    SELF_OPERATION_FORBIDDEN(100035, "不能对自己执行该操作"),
    SUPER_ADMIN_REQUIRED(100036, "只有超级管理员可以分配超级管理员角色"),
    MENU_BLOCK_FULL(100037, "该顶级目录下的菜单数量已达上限"),
    PASSWORD_INCORRECT(100038, "原密码不正确"),
    IMAGE_INVALID(100039, "图片格式不支持或超过大小限制"),
    PROJECT_APP_KEY_IMMUTABLE(100040, "项目创建后 App Key 不能修改"),
    SECRET_KEY_MISSING(100041, "服务器未配置加密密钥 security.secret-key"),
    LOG_STORAGE_NOT_CONFIGURED(100042, "当前项目还没有日志源，请在「日志中心 → 日志源」中添加"),
    LOG_STORAGE_UNAVAILABLE(100043, "日志存储暂时不可用，请稍后重试"),
    LOG_STORAGE_ADMIN_MISSING(100044, "未配置日志存储管理账号，不能修改保留期"),
    LOG_EVENT_INVALID(100047, "事件说明配置不合法"),
    QUERY_CONDITION_INVALID(100048, "查询条件不合法"),

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
    LOG_SOURCE_FAILED(100064, "日志源连接失败"),
    SERVER_NOT_FOUND(100065, "服务器不存在"),
    SERVER_NAME_EXISTS(100066, "服务器名称已存在"),
    SERVER_IN_USE(100067, "服务器下还有进程，不能删除"),
    SERVER_OFFLINE(100068, "服务器离线，不能执行指令"),
    PROCESS_NOT_FOUND(100069, "进程不存在"),
    PROCESS_NAME_EXISTS(100070, "该服务器上已有同名进程"),
    PROCESS_INVALID(100071, "进程配置不合法"),
    PROCESS_BUSY(100072, "进程还有未完成的指令"),
    PROCESS_NOT_STOPPED(100073, "进程未停止，不能删除"),
    JVM_TEMPLATE_NOT_FOUND(100074, "JVM 参数模板不存在"),
    JVM_TEMPLATE_NAME_EXISTS(100075, "JVM 参数模板名称已存在"),
    JVM_TEMPLATE_IN_USE(100076, "JVM 参数模板正在被进程使用，不能删除"),
    AGENT_TOKEN_INVALID(100077, "安装令牌无效或已过期，请在后台重新生成安装命令"),
    AGENT_SIGNATURE_INVALID(100078, "Agent 签名无效"),
    SERVER_METRIC_UNAVAILABLE(100079, "监控数据不可用，请在「系统设置 → 日志保留期」配置 ClickHouse 管理账号"),
    PROCESS_TYPE_NOT_FOUND(100080, "进程类型不存在"),
    PROCESS_TYPE_NAME_EXISTS(100081, "进程类型名称已存在"),
    PROCESS_TYPE_IN_USE(100082, "进程类型正在被进程使用，不能删除"),
    PACKAGE_UPLOAD_INVALID(100083, "发布包上传无效或已过期，请重新上传"),
    PACKAGE_INVALID(100084, "发布包不是有效的 zip 压缩包或为空"),
    FILE_OP_NOT_FOUND(100085, "文件操作不存在或已过期"),
    ZONE_NOT_AVAILABLE(100087, "没有可用的区服（游戏服都不在线，或没有提供查询接口）"),
    NODE_REPORT_DENIED(100088, "节点信息上报验签失败"),
    PROCESS_TYPE_CODE_EXISTS(100089, "进程类型编码已存在");

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
