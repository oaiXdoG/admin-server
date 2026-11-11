package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.OpsApp;
import com.oaixdog.adminbackground.entity.OpsHost;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.OpsAppMapper;
import com.oaixdog.adminbackground.mapper.OpsHostMapper;
import com.oaixdog.adminbackground.protocol.OpsHostInfo;
import com.oaixdog.adminbackground.protocol.OpsHostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 主机：在后台手动添加（IP、SSH 端口），管理后台以 root 通过 SSH 管理。
 * 「初始化」执行 ops-scripts/host_init.sh，安装程序运行需要的 JDK、unzip 和监控用的 node_exporter、process-exporter
 * （主机列表由 {@link OpsPrometheusTargets} 写给 Prometheus）；主机列表通过 SSH 读取实时信息（每台主机一次 SSH，结果缓存 10 秒）。
 * <p>
 * 查看需要 ops_host（或 ops_app）；下拉选项 ops_host、ops_app、ops_monitor 任一即可；添加、编辑、删除、测试连接、初始化需要 ops_host:manage。
 */
@Service
@RequiredArgsConstructor
public class OpsHostService {

    private static final String MENU = "ops_host";
    private static final String APP_MENU = "ops_app";
    private static final String MONITOR_MENU = "ops_monitor";
    private static final int REMARK_MAX_LENGTH = 128;
    private static final int HOSTNAME_MAX_LENGTH = 128;
    /** IPv4 或主机名 */
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,62}[A-Za-z0-9])?");
    private static final long PROBE_CACHE_MILLIS = 10_000;
    private static final long PROBE_TIMEOUT_SECONDS = 30;
    private static final long INIT_TIMEOUT_SECONDS = 900;
    private static final String INIT_SCRIPT = "host_init.sh";

    /**
     * 一次 SSH 读出主机信息，每行 key=value；CPU、网络取 1 秒内的变化。只用 POSIX sh（Ubuntu 的 /bin/sh 是 dash）。
     * running：正在运行的程序数（命令行带 -Dhost.app.dir= 的进程，见 app.sh）。
     */
    private static final String PROBE_SCRIPT = """
            . /etc/os-release 2>/dev/null
            echo "hostname=$(hostname)"
            echo "os=${PRETTY_NAME:-$(uname -s)} $(uname -r)"
            echo "cores=$(nproc)"
            net() { awk 'NR>2{sub(/^ +/,"");split($0,a,/: */);split(a[2],f," ");if(a[1]!="lo"){r+=f[1];t+=f[9]}}END{print r+0, t+0}' /proc/net/dev; }
            c1=$(head -1 /proc/stat); n1=$(net)
            sleep 1
            c2=$(head -1 /proc/stat); n2=$(net)
            echo "$c1|$c2" | awk -F'|' '{split($1,a," ");split($2,b," ");t=0;for(i=2;i<=9;i++)t+=b[i]-a[i];idle=(b[5]+b[6])-(a[5]+a[6]);printf "cpu=%.4f\\n",(t>0?(t-idle)/t:0)}'
            echo "$n1 $n2" | awk '{printf "net_in=%d\\nnet_out=%d\\n",$3-$1,$4-$2}'
            awk '/^MemTotal:/{printf "mem_total=%d\\n",$2*1024}/^MemAvailable:/{printf "mem_available=%d\\n",$2*1024}' /proc/meminfo
            echo "uptime=$(cut -d. -f1 /proc/uptime)"
            echo "running=$(pgrep -fc 'Dhost[.]app[.]dir=' || true)"
            df -B1 -x tmpfs -x devtmpfs -x overlay -x squashfs --output=source,target,size,used 2>/dev/null | awk 'NR>1{print "disk="$1" "$2" "$3" "$4}'
            """;

    private final PermissionService permissionService;
    private final SshRunner sshRunner;
    private final OpsTaskService opsTaskService;
    private final OpsHostMapper opsHostMapper;
    private final OpsAppMapper opsAppMapper;
    private final OpsPrometheusTargets prometheusTargets;
    private final ApplicationProperties properties;

    /** 主机 id → 最近一次读取的结果 */
    private final Map<Long, Probe> probes = new ConcurrentHashMap<>();

    private record Probe(long time, boolean online, OpsHostInfo info, long running, String error) {
    }

    public Map<String, Object> list(String authorization) {
        permissionService.requireAnyMenu(authorization, MENU, APP_MENU);
        List<OpsHost> hosts = opsHostMapper.selectList(Wrappers.<OpsHost>lambdaQuery().orderByAsc(OpsHost::getIp));
        Map<Long, Long> appCounts = opsAppMapper.selectList(Wrappers.<OpsApp>lambdaQuery()).stream()
                .collect(Collectors.groupingBy(OpsApp::getHostId, Collectors.counting()));
        refresh(hosts);
        List<OpsHostResponse> list = hosts.stream().map(host -> {
            OpsHostResponse response = OpsHostResponse.valueOf(host);
            response.setAppCount(appCounts.getOrDefault(host.getId(), 0L));
            Probe probe = probes.get(host.getId());
            if (probe != null) {
                response.setOnline(probe.online());
                response.setInfo(probe.info());
                response.setRunningCount(probe.running());
                response.setError(probe.error());
                if (probe.info() != null) {
                    response.setHostname(probe.info().getHostname());
                }
            }
            return response;
        }).toList();
        return Map.of("list", list);
    }

    /** 主机下拉选项：在线状态取最近一次读取的结果，不连接主机 */
    public Map<String, Object> options(String authorization) {
        permissionService.requireAnyMenu(authorization, MENU, APP_MENU, MONITOR_MENU);
        List<Map<String, Object>> list = opsHostMapper.selectList(Wrappers.<OpsHost>lambdaQuery().orderByAsc(OpsHost::getIp)).stream()
                .map(host -> {
                    Probe probe = probes.get(host.getId());
                    return Map.<String, Object>of("id", host.getId(), "ip", host.getIp(), "hostname", host.getHostname(),
                            "remark", host.getRemark(), "online", probe != null && probe.online());
                })
                .toList();
        return Map.of("list", list);
    }

    public OpsHostResponse create(String authorization, String ip, Integer sshPort, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        var host = new OpsHost();
        fill(host, ip, sshPort, remark);
        long now = System.currentTimeMillis();
        host.setHostname("");
        host.setInitializedAt(0L);
        host.setLastSeenAt(0L);
        host.setCreatedAt(now);
        host.setUpdatedAt(now);
        try {
            opsHostMapper.insert(host);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_HOST_EXISTS);
        }
        prometheusTargets.write();
        return OpsHostResponse.valueOf(host);
    }

    public void update(String authorization, Long hostId, String ip, Integer sshPort, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsHost host = requireHost(hostId);
        fill(host, ip == null ? host.getIp() : ip, sshPort == null ? host.getSshPort() : sshPort, remark);
        host.setUpdatedAt(System.currentTimeMillis());
        try {
            opsHostMapper.updateById(host);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_HOST_EXISTS);
        }
        probes.remove(host.getId());
        prometheusTargets.write();
    }

    public void remove(String authorization, Long hostId) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsHost host = requireHost(hostId);
        if (opsAppMapper.selectCount(Wrappers.<OpsApp>lambdaQuery().eq(OpsApp::getHostId, host.getId())) > 0) {
            throw new BusinessException(ErrorCode.OPS_HOST_IN_USE);
        }
        opsHostMapper.deleteById(host.getId());
        probes.remove(host.getId());
        prometheusTargets.write();
    }

    /** 测试 SSH 连接，返回主机名和系统 */
    public String test(String authorization, Long hostId) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsHost host = requireHost(hostId);
        String output = sshRunner.exec(host, ". /etc/os-release 2>/dev/null; echo \"$(hostname) · ${PRETTY_NAME:-$(uname -s)}\"", 30);
        String hostname = output.contains(" · ") ? output.substring(0, output.indexOf(" · ")) : output;
        seen(host, hostname);
        return "连接成功：" + output.strip();
    }

    /** 初始化：把 host_init.sh 送给 bash -s 执行（安装 JDK 21、unzip 和两个 exporter），返回任务 id */
    public long init(String authorization, Long hostId) {
        Account account = permissionService.requireMenu(authorization, MENU + ":manage");
        OpsHost host = requireHost(hostId);
        Path file = Path.of(properties.getOpsScriptDir(), INIT_SCRIPT);
        byte[] script;
        try {
            script = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPS_SSH_FAILED, "读取 " + file.toAbsolutePath() + " 失败：" + e.getMessage());
        }
        String command = "bash -s -- " + properties.getOpsNodeExporterPort() + " " + properties.getOpsProcessExporterPort();
        return opsTaskService.submit(account, "init", "初始化主机 " + host.getIp(), host.getId(), 0, "host:" + host.getId(), out -> {
            int code = sshRunner.run(host, command, SshRunner.Input.of(script), out, INIT_TIMEOUT_SECONDS);
            if (code == 0) {
                var done = new OpsHost();
                done.setId(host.getId());
                done.setInitializedAt(System.currentTimeMillis());
                opsHostMapper.updateById(done);
            }
            return code == 0;
        });
    }

    public OpsHost requireHost(Long hostId) {
        OpsHost host = hostId == null ? null : opsHostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException(ErrorCode.OPS_HOST_NOT_FOUND);
        }
        return host;
    }

    /** 最近一次读取到的 CPU 核数，读不到时为 1 */
    public int cpuCores(long hostId) {
        Probe probe = probes.get(hostId);
        return probe == null || probe.info() == null || probe.info().getCpuCores() <= 0 ? 1 : probe.info().getCpuCores();
    }

    /** 缓存过期的主机重新读取，多台主机并行执行 */
    private void refresh(List<OpsHost> hosts) {
        long now = System.currentTimeMillis();
        List<OpsHost> stale = hosts.stream().filter(host -> {
            Probe probe = probes.get(host.getId());
            return probe == null || now - probe.time() > PROBE_CACHE_MILLIS;
        }).toList();
        if (stale.isEmpty()) {
            return;
        }
        Map<Long, SshRunner.Result> results;
        try {
            results = sshRunner.execAll(stale, host -> "sh -s", SshRunner.Input.of(PROBE_SCRIPT.getBytes(StandardCharsets.UTF_8)),
                    PROBE_TIMEOUT_SECONDS);
        } catch (BusinessException exception) {
            stale.forEach(host -> probes.put(host.getId(), new Probe(now, false, null, 0, exception.getMessage())));
            return;
        }
        for (OpsHost host : stale) {
            SshRunner.Result result = results.get(host.getId());
            if (result == null) {
                probes.put(host.getId(), new Probe(now, false, null, 0, "没有返回结果"));
            } else if (!result.reachable()) {
                probes.put(host.getId(), new Probe(now, false, null, 0, "SSH 连接失败：" + result.output().strip()));
            } else if (result.exitCode() != 0) {
                probes.put(host.getId(), new Probe(now, true, null, 0, "读取主机信息失败：" + result.output().strip()));
            } else {
                Map<String, String> values = new HashMap<>();
                List<String> diskLines = new ArrayList<>();
                for (String line : result.output().split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq);
                    if (key.equals("disk")) {
                        diskLines.add(line.substring(eq + 1));
                    } else {
                        values.put(key, line.substring(eq + 1).trim());
                    }
                }
                OpsHostInfo info = info(values, diskLines);
                probes.put(host.getId(), new Probe(now, true, info, parseLong(values.get("running")), ""));
                seen(host, info.getHostname());
            }
        }
    }

    private static OpsHostInfo info(Map<String, String> values, List<String> diskLines) {
        var info = new OpsHostInfo();
        info.setHostname(values.getOrDefault("hostname", ""));
        info.setOs(values.getOrDefault("os", ""));
        info.setCpuCores((int) parseLong(values.get("cores")));
        info.setCpuUsage(parseDouble(values.get("cpu")));
        long total = parseLong(values.get("mem_total"));
        info.setMemoryTotal(total);
        info.setMemoryUsed(total - parseLong(values.get("mem_available")));
        info.setUptime(parseLong(values.get("uptime")));
        info.setNetIn(parseDouble(values.get("net_in")));
        info.setNetOut(parseDouble(values.get("net_out")));
        // 同一块设备挂在多个位置时只保留一个：优先 /，其次路径最短的
        Map<String, OpsHostInfo.Disk> byDevice = new LinkedHashMap<>();
        for (String line : diskLines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4 || parseLong(parts[2]) <= 0) {
                continue;
            }
            var disk = new OpsHostInfo.Disk(parts[1], parseLong(parts[2]), parseLong(parts[3]));
            byDevice.merge(parts[0], disk, (a, b) -> rank(b) < rank(a) ? b : a);
        }
        info.setDisks(List.copyOf(byDevice.values()));
        return info;
    }

    private static int rank(OpsHostInfo.Disk disk) {
        return "/".equals(disk.mount()) ? 0 : disk.mount().length();
    }

    private void fill(OpsHost host, String ip, Integer sshPort, String remark) {
        String address = ip == null ? "" : ip.trim();
        int port = sshPort == null ? 22 : sshPort;
        String value = remark == null ? "" : remark.trim();
        if (!ADDRESS.matcher(address).matches()) {
            throw new BusinessException(ErrorCode.OPS_HOST_INVALID, "IP 不合法");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.OPS_HOST_INVALID, "SSH 端口不合法");
        }
        if (value.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        host.setIp(address);
        host.setSshPort(port);
        host.setRemark(value);
    }

    /** 连上主机后更新最近在线时间和主机名 */
    private void seen(OpsHost host, String hostname) {
        var update = new OpsHost();
        update.setId(host.getId());
        update.setLastSeenAt(System.currentTimeMillis());
        if (hostname != null && !hostname.isBlank()) {
            String name = hostname.strip();
            update.setHostname(name.length() > HOSTNAME_MAX_LENGTH ? name.substring(0, HOSTNAME_MAX_LENGTH) : name);
        }
        opsHostMapper.updateById(update);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
