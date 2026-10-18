package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.ServerHost;
import com.oaixdog.adminbackground.entity.ServerJvmTemplate;
import com.oaixdog.adminbackground.entity.ServerProcess;
import com.oaixdog.adminbackground.entity.ServerProcessType;
import com.oaixdog.adminbackground.entity.ServerTask;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.ProjectMapper;
import com.oaixdog.adminbackground.mapper.ServerHostMapper;
import com.oaixdog.adminbackground.mapper.ServerJvmTemplateMapper;
import com.oaixdog.adminbackground.mapper.ServerProcessMapper;
import com.oaixdog.adminbackground.mapper.ServerProcessTypeMapper;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 托管进程：每个进程对应 Agent 生成的 systemd 服务 adm-<name>，ExecStart 为 java + 模板参数 + 进程参数 + 启动目标 + 程序参数，
 * 启动目标为 -jar <jar>，或填了主类时为 -cp <classpath> <主类>（systemd 不经过 shell，lib/* 由 java 自己展开）。
 * 修改定义后 configVersion 加 1，重启后生效；运行中且启动时的版本较低的进程显示待重启。
 * 服务绑定：Agent 给进程注入 ADM_INSTANCE_ID=<进程 ID>，业务服把它作为 instanceId 上报（NodeInfoReportService），列表按它找到进程的上报，显示服务状态。
 */
@Service
@RequiredArgsConstructor
public class ServerProcessService {

    private static final String MENU = "server_process";
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");
    private static final Pattern DIRECTORY = Pattern.compile("^/[^\\s\"'\\\\%;]{0,255}$");
    private static final Pattern FILE = Pattern.compile("^[^\\s\"'\\\\%;]{1,256}$");
    private static final Pattern USER = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");
    private static final Pattern CLASSPATH = Pattern.compile("^[^\\s\"'\\\\%;]{1,1024}$");
    private static final Pattern MAIN_CLASS = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");
    private static final Pattern ENV = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=[^\"\\\\]*$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    private static final Set<String> ACTIONS = Set.of(ServerTaskService.ACTION_START, ServerTaskService.ACTION_STOP, ServerTaskService.ACTION_RESTART);
    private static final Set<String> STOPPED = Set.of("inactive", "failed");
    private static final int OPTIONS_MAX_LENGTH = 4096;
    private static final int ARGS_MAX_LENGTH = 1024;
    private static final int REMARK_MAX_LENGTH = 128;
    private static final int OPERATE_MAX = 50;
    /** Agent 写入的进程环境变量文件目录（ADM_INSTANCE_ID、ADM_REPORT_SECRET、ADM_REPORT_URL）；进程表单里的环境变量不允许 ADM_ 开头 */
    private static final String ENV_DIR = "/etc/admin-agent/env/";
    private static final String RESERVED_ENV_PREFIX = "ADM_";

    private final PermissionService permissionService;
    private final ServerProcessMapper processMapper;
    private final ServerHostMapper hostMapper;
    private final ServerJvmTemplateMapper templateMapper;
    private final ServerHostService hostService;
    private final ServerTemplateService templateService;
    private final ServerProcessTypeService typeService;
    private final ServerProcessTypeMapper typeMapper;
    private final ServerTaskService taskService;
    private final ServerMetricService metricService;
    private final NodeInfoReportService nodeInfoReportService;
    private final ProjectMapper projectMapper;
    private final ApplicationProperties properties;

    /** 进程表单里除 name、serverId 之外的字段 */
    public record Definition(Long typeId, String remark, String workDir, String javaPath, Long templateId, String jvmOptions, String jar, String classpath,
                             String mainClass, String programArgs, String env, String runUser, Integer port) {
    }

    public List<Map<String, Object>> list(String authorization, Long projectId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU);
        List<ServerProcess> processes = processMapper.selectList(Wrappers.<ServerProcess>lambdaQuery().eq(ServerProcess::getProjectId, access.projectId())
                .orderByAsc(ServerProcess::getServerId).orderByAsc(ServerProcess::getName));
        Map<Long, ServerHost> servers = hostMapper.selectList(Wrappers.<ServerHost>lambdaQuery().eq(ServerHost::getProjectId, access.projectId()))
                .stream().collect(Collectors.toMap(ServerHost::getId, Function.identity()));
        Map<Long, String> templates = templateMapper.selectList(Wrappers.<ServerJvmTemplate>lambdaQuery().eq(ServerJvmTemplate::getProjectId, access.projectId()))
                .stream().collect(Collectors.toMap(ServerJvmTemplate::getId, ServerJvmTemplate::getName));
        Map<Long, ServerProcessType> types = typeMapper.selectList(Wrappers.<ServerProcessType>lambdaQuery().eq(ServerProcessType::getProjectId, access.projectId()))
                .stream().collect(Collectors.toMap(ServerProcessType::getId, Function.identity()));
        Map<String, NodeInfoReportService.Node> nodes = nodeInfoReportService.nodes(projectMapper.selectById(access.projectId()).getAppKey()).stream()
                .collect(Collectors.toMap(NodeInfoReportService.Node::instanceId, Function.identity()));
        List<Long> ids = processes.stream().map(ServerProcess::getId).toList();
        Map<Long, Map<String, Object>> metrics = metricService.processMetrics(ids);
        Map<Long, ServerTask> tasks = taskService.unfinished(ids);
        Map<Long, ServerTask> deploys = taskService.latestDeploys(ids);
        Map<Long, Boolean> online = servers.keySet().stream().collect(Collectors.toMap(Function.identity(), metricService::isOnline));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerProcess process : processes) {
            ServerHost server = servers.get(process.getServerId());
            boolean serverOnline = online.getOrDefault(process.getServerId(), false);
            Map<String, Object> metric = serverOnline ? metrics.get(process.getId()) : null;
            String state = metric == null ? "unknown" : String.valueOf(metric.get("state"));
            Object startedVersion = metric == null ? null : metric.get("configVersion");
            boolean pendingRestart = "active".equals(state) && startedVersion instanceof Number number && number.intValue() < process.getConfigVersion();
            Map<String, Object> item = toResponse(process);
            item.put("serverName", server == null ? "" : server.getName());
            item.put("serverStatus", server == null ? "pending" : ServerHostService.status(server, serverOnline));
            item.put("templateName", process.getTemplateId() == null ? "" : templates.getOrDefault(process.getTemplateId(), ""));
            ServerProcessType type = process.getTypeId() == null ? null : types.get(process.getTypeId());
            String typeCode = type == null ? "" : type.getCode();
            item.put("typeName", type == null ? "" : type.getName());
            item.put("typeCode", typeCode);
            item.put("service", service(typeCode, state, nodes.get(String.valueOf(process.getId())), now));
            ServerTask deploy = deploys.get(process.getId());
            item.put("deployedAt", deploy == null ? null : deploy.getFinishedAt());
            item.put("deployRemark", deploy == null ? "" : deploy.getRemark());
            item.put("state", state);
            item.put("pendingRestart", pendingRestart);
            item.put("metric", metric);
            ServerTask task = tasks.get(process.getId());
            item.put("task", task == null ? null : ServerTaskService.toResponse(task, now));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> create(String authorization, Long projectId, Long serverId, String name, Definition definition) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":create");
        ServerHost server = hostService.require(access.projectId(), serverId);
        String processName = name == null ? "" : name.trim();
        if (!NAME.matcher(processName).matches()) {
            throw new BusinessException(ErrorCode.PROCESS_INVALID);
        }
        ServerProcess process = new ServerProcess();
        process.setProjectId(access.projectId());
        process.setServerId(server.getId());
        process.setName(processName);
        fill(process, access.projectId(), definition);
        long now = System.currentTimeMillis();
        process.setConfigVersion(1);
        process.setCreatedAt(now);
        process.setUpdatedAt(now);
        if (processMapper.selectCount(Wrappers.<ServerProcess>lambdaQuery().eq(ServerProcess::getServerId, server.getId()).eq(ServerProcess::getName, processName)) > 0) {
            throw new BusinessException(ErrorCode.PROCESS_NAME_EXISTS);
        }
        try {
            processMapper.insert(process);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.PROCESS_NAME_EXISTS);
        }
        return toResponse(process);
    }

    public Map<String, Object> update(String authorization, Long projectId, Long processId, Definition definition) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":update");
        ServerProcess process = require(access.projectId(), processId);
        fill(process, access.projectId(), definition);
        process.setConfigVersion(process.getConfigVersion() + 1);
        process.setUpdatedAt(System.currentTimeMillis());
        processMapper.updateById(process);
        return toResponse(process);
    }

    /** 从未接入的服务器上的进程直接删除；否则只能删除已停止的进程，并通知 Agent 删除 systemd 服务（不删除工作目录） */
    public void remove(String authorization, Long projectId, Long processId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":remove");
        ServerProcess process = require(access.projectId(), processId);
        ServerHost server = hostService.require(access.projectId(), process.getServerId());
        if (StringUtils.isNotBlank(server.getSecret())) {
            if (!metricService.isOnline(server.getId())) {
                throw new BusinessException(ErrorCode.SERVER_OFFLINE);
            }
            Map<String, Object> metric = metricService.processMetrics(List.of(process.getId())).get(process.getId());
            if (metric != null && !STOPPED.contains(String.valueOf(metric.get("state")))) {
                throw new BusinessException(ErrorCode.PROCESS_NOT_STOPPED);
            }
            taskService.requireIdle(List.of(process.getId()));
            taskService.create(process, ServerTaskService.ACTION_REMOVE, access.account().getAccount());
        }
        processMapper.deleteById(process.getId());
    }

    public List<Long> operate(String authorization, Long projectId, List<Long> processIds, String action) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":operate");
        Set<Long> ids = processIds == null ? Set.of() : new LinkedHashSet<>(processIds);
        if (ids.isEmpty() || ids.size() > OPERATE_MAX || ids.contains(null) || !ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        List<ServerProcess> processes = ids.stream().map(id -> require(access.projectId(), id)).toList();
        for (Long serverId : processes.stream().map(ServerProcess::getServerId).collect(Collectors.toSet())) {
            if (!metricService.isOnline(serverId)) {
                throw new BusinessException(ErrorCode.SERVER_OFFLINE);
            }
        }
        taskService.requireIdle(ids);
        return processes.stream().map(process -> taskService.create(process, action, access.account().getAccount())).toList();
    }

    public Map<String, Object> tasks(String authorization, Long projectId, Long processId, Integer page, Integer pageSize) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU);
        if (processId != null) {
            require(access.projectId(), processId);
        }
        return taskService.page(access.projectId(), processId, page, pageSize);
    }

    /** 未托管服务：本项目上报里 instanceId 对应不到本项目任何进程的记录，按最近上报倒序；serverName 按 innerHost 匹配本项目服务器的内网 IP */
    public List<Map<String, Object>> unmanaged(String authorization, Long projectId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU);
        Set<String> processIds = processMapper.selectList(Wrappers.<ServerProcess>lambdaQuery().select(ServerProcess::getId).eq(ServerProcess::getProjectId, access.projectId()))
                .stream().map(process -> String.valueOf(process.getId())).collect(Collectors.toSet());
        Map<String, String> serverNames = new LinkedHashMap<>();
        for (ServerHost server : hostMapper.selectList(Wrappers.<ServerHost>lambdaQuery().eq(ServerHost::getProjectId, access.projectId()))) {
            String privateIp = server.getPrivateIp() == null ? "" : server.getPrivateIp();
            Arrays.stream(privateIp.split(",")).map(String::trim).filter(ip -> !ip.isEmpty()).forEach(ip -> serverNames.putIfAbsent(ip, server.getName()));
        }
        long now = System.currentTimeMillis();
        return nodeInfoReportService.nodes(projectMapper.selectById(access.projectId()).getAppKey()).stream().filter(node -> !processIds.contains(node.instanceId()))
                .sorted(Comparator.comparingLong(NodeInfoReportService.Node::reportTime).reversed()).map(node -> {
                    Map<String, Object> item = nodeResponse(node);
                    item.put("online", node.online(now));
                    item.put("serverName", serverNames.getOrDefault(node.innerHost(), ""));
                    return item;
                }).toList();
    }

    /** 上报配置：App Key、上报地址（agent.base-url + /api/node/report，未配置时为空）、上报密钥明文（还没有时生成） */
    public Map<String, Object> reportConfig(String authorization, Long projectId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":update");
        return reportConfig(access.projectId(), nodeInfoReportService.reportSecret(access.projectId()));
    }

    /** 重新生成上报密钥；托管进程的 Agent 下次心跳时更新环境变量文件，进程重启后生效，手动配置的服务要改配置 */
    public Map<String, Object> resetReportConfig(String authorization, Long projectId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":update");
        return reportConfig(access.projectId(), nodeInfoReportService.resetReportSecret(access.projectId()));
    }

    private Map<String, Object> reportConfig(long projectId, String secret) {
        String baseUrl = properties.getAgentBaseUrl() == null ? "" : properties.getAgentBaseUrl().trim().replaceAll("/+$", "");
        String reportUrl = baseUrl.isEmpty() ? "" : baseUrl + "/api/node/report";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appKey", projectMapper.selectById(projectId).getAppKey());
        result.put("reportUrl", reportUrl);
        result.put("secret", secret);
        return result;
    }

    /** 进程的服务状态；类型没有编码时为 null。运行中 + 30 秒内有上报为 online（上报 type 与类型编码不同为 mismatch），运行中无上报为 not_ready，未运行为 offline */
    private static Map<String, Object> service(String typeCode, String state, NodeInfoReportService.Node node, long now) {
        if (StringUtils.isBlank(typeCode)) {
            return null;
        }
        boolean online = node != null && node.online(now);
        String status = !"active".equals(state) ? "offline" : !online ? "not_ready" : typeCode.equals(node.type()) ? "online" : "mismatch";
        Map<String, Object> result = node == null ? emptyNode() : nodeResponse(node);
        result.put("status", status);
        return result;
    }

    private static Map<String, Object> nodeResponse(NodeInfoReportService.Node node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", node.type());
        result.put("nodeId", node.nodeId());
        result.put("instanceId", node.instanceId());
        result.put("serverId", node.serverId());
        result.put("host", node.host());
        result.put("port", node.port());
        result.put("innerHost", node.innerHost());
        result.put("gmPort", node.gmPort());
        result.put("reportTime", node.reportTime());
        result.put("raw", node.raw());
        return result;
    }

    private static Map<String, Object> emptyNode() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "");
        result.put("nodeId", "");
        result.put("instanceId", "");
        result.put("serverId", null);
        result.put("host", "");
        result.put("port", 0);
        result.put("innerHost", "");
        result.put("gmPort", 0);
        result.put("reportTime", 0L);
        result.put("raw", null);
        return result;
    }

    /**
     * Agent 心跳返回的进程定义：systemd 服务文件内容由后台生成，Agent 内容不同时写入并 daemon-reload；
     * env 由 Agent 写入 /etc/admin-agent/env/adm-<name>.env（再补上 ADM_REPORT_URL），服务文件用 EnvironmentFile 引用
     */
    public List<Map<String, Object>> agentDefinitions(ServerHost server) {
        List<ServerProcess> processes = processMapper.selectList(Wrappers.<ServerProcess>lambdaQuery().eq(ServerProcess::getServerId, server.getId()));
        String reportSecret = processes.isEmpty() ? "" : nodeInfoReportService.reportSecret(server.getProjectId());
        Map<Long, String> templates = templateMapper.selectList(Wrappers.<ServerJvmTemplate>lambdaQuery().eq(ServerJvmTemplate::getProjectId, server.getProjectId()))
                .stream().collect(Collectors.toMap(ServerJvmTemplate::getId, ServerJvmTemplate::getOptions));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerProcess process : processes) {
            String templateOptions = process.getTemplateId() == null ? "" : templates.getOrDefault(process.getTemplateId(), "");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("processId", process.getId());
            item.put("name", process.getName());
            item.put("configVersion", process.getConfigVersion());
            item.put("port", process.getPort());
            item.put("workDir", process.getWorkDir());
            item.put("runUser", process.getRunUser());
            item.put("unit", unit(server, process, templateOptions));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("ADM_INSTANCE_ID", String.valueOf(process.getId()));
            env.put("ADM_REPORT_SECRET", reportSecret);
            item.put("env", env);
            result.add(item);
        }
        return result;
    }

    /** JVM 参数：去掉空行和首尾空白，按行保存；含控制字符或超长时返回 null */
    public static String normalizeOptions(String options) {
        String text = options == null ? "" : options;
        if (text.length() > OPTIONS_MAX_LENGTH || CONTROL.matcher(text.replace("\r", "")).find()) {
            return null;
        }
        return Arrays.stream(text.split("\\r?\\n")).map(String::trim).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
    }

    private static String unit(ServerHost server, ServerProcess process, String templateOptions) {
        List<String> command = new ArrayList<>();
        command.add(process.getJavaPath());
        command.addAll(lines(templateOptions));
        command.addAll(lines(process.getJvmOptions()));
        if (StringUtils.isNotBlank(process.getMainClass())) {
            command.add("-cp");
            command.add(process.getClasspath());
            command.add(process.getMainClass());
        } else {
            command.add("-jar");
            command.add(process.getJar());
        }
        if (StringUtils.isNotBlank(process.getProgramArgs())) {
            command.add(process.getProgramArgs());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("[Unit]\n");
        builder.append("Description=").append(escape(server.getName() + " " + process.getName())).append(" (admin-agent)\n");
        builder.append("After=network.target\n\n");
        builder.append("[Service]\n");
        builder.append("Type=simple\n");
        if (StringUtils.isNotBlank(process.getRunUser())) {
            builder.append("User=").append(process.getRunUser()).append('\n');
        }
        builder.append("WorkingDirectory=").append(process.getWorkDir()).append('\n');
        builder.append("EnvironmentFile=-").append(ENV_DIR).append("adm-").append(process.getName()).append(".env\n");
        for (String line : lines(process.getEnv())) {
            builder.append("Environment=\"").append(escape(line)).append("\"\n");
        }
        builder.append("ExecStart=").append(escape(String.join(" ", command))).append('\n');
        builder.append("Restart=on-failure\n");
        builder.append("RestartSec=5\n");
        builder.append("TimeoutStopSec=60\n");
        builder.append("SuccessExitStatus=143\n\n");
        builder.append("[Install]\n");
        builder.append("WantedBy=multi-user.target\n");
        return builder.toString();
    }

    /** systemd 会把 % 当作占位符 */
    private static String escape(String text) {
        return text.replace("%", "%%");
    }

    private static List<String> lines(String text) {
        return StringUtils.isBlank(text) ? List.of() : Arrays.stream(text.split("\n")).filter(StringUtils::isNotBlank).toList();
    }

    public ServerProcess require(long projectId, Long processId) {
        ServerProcess process = processId == null ? null : processMapper.selectById(processId);
        if (process == null || !Objects.equals(process.getProjectId(), projectId)) {
            throw new BusinessException(ErrorCode.PROCESS_NOT_FOUND);
        }
        return process;
    }

    private void fill(ServerProcess process, long projectId, Definition definition) {
        if (definition == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String remark = definition.remark() == null ? "" : definition.remark().trim();
        String workDir = definition.workDir() == null ? "" : definition.workDir().trim();
        String javaPath = StringUtils.isBlank(definition.javaPath()) ? "java" : definition.javaPath().trim();
        String jar = definition.jar() == null ? "" : definition.jar().trim();
        String classpath = definition.classpath() == null ? "" : definition.classpath().trim();
        String mainClass = definition.mainClass() == null ? "" : definition.mainClass().trim();
        boolean classpathMode = !mainClass.isEmpty();
        boolean target = classpathMode ? CLASSPATH.matcher(classpath).matches() && MAIN_CLASS.matcher(mainClass).matches() && mainClass.length() <= 256
                : FILE.matcher(jar).matches();
        String programArgs = definition.programArgs() == null ? "" : definition.programArgs().trim();
        String runUser = definition.runUser() == null ? "" : definition.runUser().trim();
        int port = definition.port() == null ? 0 : definition.port();
        String jvmOptions = normalizeOptions(definition.jvmOptions());
        String env = normalizeOptions(definition.env());
        boolean valid = remark.length() <= REMARK_MAX_LENGTH && DIRECTORY.matcher(workDir).matches() && FILE.matcher(javaPath).matches()
                && target && programArgs.length() <= ARGS_MAX_LENGTH && !CONTROL.matcher(programArgs).find() && !programArgs.contains("\n")
                && (runUser.isEmpty() || USER.matcher(runUser).matches()) && port >= 0 && port <= 65535 && jvmOptions != null && env != null
                && lines(env).stream().allMatch(line -> ENV.matcher(line).matches());
        if (!valid) {
            throw new BusinessException(ErrorCode.PROCESS_INVALID);
        }
        if (definition.templateId() != null) {
            templateService.require(projectId, definition.templateId());
        }
        if (definition.typeId() == null || lines(env).stream().anyMatch(line -> line.startsWith(RESERVED_ENV_PREFIX))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        typeService.require(projectId, definition.typeId());
        process.setTypeId(definition.typeId());
        process.setRemark(remark);
        process.setWorkDir(workDir);
        process.setJavaPath(javaPath);
        process.setTemplateId(definition.templateId());
        process.setJvmOptions(jvmOptions);
        process.setJar(classpathMode ? "" : jar);
        process.setClasspath(classpathMode ? classpath : "");
        process.setMainClass(mainClass);
        process.setProgramArgs(programArgs);
        process.setEnv(env);
        process.setRunUser(runUser);
        process.setPort(port);
    }

    private static Map<String, Object> toResponse(ServerProcess process) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", process.getId());
        result.put("serverId", process.getServerId());
        result.put("typeId", process.getTypeId());
        result.put("name", process.getName());
        result.put("remark", process.getRemark());
        result.put("workDir", process.getWorkDir());
        result.put("javaPath", process.getJavaPath());
        result.put("templateId", process.getTemplateId());
        result.put("jvmOptions", process.getJvmOptions());
        result.put("jar", process.getJar());
        result.put("classpath", process.getClasspath());
        result.put("mainClass", process.getMainClass());
        result.put("programArgs", process.getProgramArgs());
        result.put("env", process.getEnv());
        result.put("runUser", process.getRunUser());
        result.put("port", process.getPort());
        result.put("configVersion", process.getConfigVersion());
        result.put("updatedAt", process.getUpdatedAt());
        return result;
    }
}
