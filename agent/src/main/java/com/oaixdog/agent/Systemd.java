package com.oaixdog.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 托管进程的 systemd 操作：服务名 adm-<进程名>，服务文件在 /etc/systemd/system 下，内容由后台生成；
 * 环境变量文件 /etc/admin-agent/env/adm-<进程名>.env 内容为后台下发的 env 加上 ADM_REPORT_URL
 */
final class Systemd {

    private static final Path UNIT_DIR = Path.of("/etc/systemd/system");
    private static final Path ENV_DIR = Path.of("/etc/admin-agent/env");
    private static final int START_WAIT_SECONDS = 180;
    private static final int STABLE_SECONDS = 3;

    private Systemd() {
    }

    record Result(boolean success, String output) {
    }

    static String unitName(String processName) {
        return "adm-" + processName + ".service";
    }

    /** 服务文件内容不同时写入，返回是否写入 */
    static boolean writeUnit(String processName, String content) throws IOException {
        Path file = UNIT_DIR.resolve(unitName(processName));
        if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    static Path envFile(String processName) {
        return ENV_DIR.resolve("adm-" + processName + ".env");
    }

    /** 进程环境变量文件（服务文件用 EnvironmentFile=- 引用，里面有上报密钥，只允许 root 读写），内容不同时写入，返回是否写入 */
    static boolean writeEnv(String processName, Map<String, String> env) throws IOException {
        StringBuilder builder = new StringBuilder();
        env.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        String content = builder.toString();
        Path file = envFile(processName);
        if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.createDirectories(ENV_DIR);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    static void daemonReload() {
        run(30, "systemctl", "daemon-reload");
    }

    /** ActiveState 和 MainPID */
    static Map<String, String> show(String processName) {
        Map<String, String> result = new HashMap<>();
        String output = run(10, "systemctl", "show", unitName(processName), "-p", "ActiveState", "-p", "MainPID").output();
        for (String line : output.split("\n")) {
            int index = line.indexOf('=');
            if (index > 0) {
                result.put(line.substring(0, index), line.substring(index + 1).trim());
            }
        }
        return result;
    }

    /**
     * start 或 restart：启用开机自启后启动，最多等 180 秒（游戏服加载配置表可能较慢）确认运行中（配置了端口时同时确认端口可连），再观察 3 秒确认没有退出。
     * 进程启动后退出时 systemd 会自动重启（状态仍是 activating），所以同时看自动重启次数，增加了就判定失败；失败时停止服务，避免反复重启。
     */
    static Result start(String processName, String action, int port) {
        String unit = unitName(processName);
        StringBuilder output = new StringBuilder();
        output.append(run(30, "systemctl", "enable", unit).output());
        Result command = run(120, "systemctl", action, unit);
        output.append(command.output());
        if (!command.success()) {
            return fail(unit, output, "");
        }
        int restarts = restarts(unit);
        for (int second = 0; second < START_WAIT_SECONDS; second++) {
            String state = activeState(unit);
            if ("failed".equals(state) || "inactive".equals(state) || restarts(unit) > restarts) {
                return fail(unit, output, "进程启动后退出\n");
            }
            if ("active".equals(state) && (port <= 0 || portOpen(port))) {
                sleep(STABLE_SECONDS);
                if (!"active".equals(activeState(unit)) || restarts(unit) > restarts) {
                    return fail(unit, output, "进程启动后退出\n");
                }
                return new Result(true, output.append("已运行").append(port > 0 ? "，端口 " + port + " 可连接" : "").append('\n').append(diagnose(unit)).toString());
            }
            sleep(1);
        }
        return fail(unit, output, port > 0 ? START_WAIT_SECONDS + " 秒内端口 " + port + " 没有监听\n" : START_WAIT_SECONDS + " 秒内没有进入运行状态\n");
    }

    /** 启动失败：先取诊断信息，再停止服务，避免 systemd 反复重启 */
    private static Result fail(String unit, StringBuilder output, String reason) {
        output.append(reason).append(diagnose(unit));
        run(120, "systemctl", "stop", unit);
        return new Result(false, output.append("\n启动失败，已停止服务，避免反复重启\n").toString());
    }

    private static int restarts(String unit) {
        try {
            return Integer.parseInt(run(10, "systemctl", "show", "-p", "NRestarts", "--value", unit).output().trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 先发 SIGTERM，超过服务文件里的 TimeoutStopSec 再 SIGKILL */
    static Result stop(String processName) {
        String unit = unitName(processName);
        Result command = run(120, "systemctl", "stop", unit);
        String state = activeState(unit);
        boolean stopped = "inactive".equals(state) || "failed".equals(state);
        return new Result(command.success() && stopped, command.output() + (stopped ? "已停止\n" : "停止后状态：" + state + "\n") + diagnose(unit));
    }

    /** 停止、禁用并删除服务文件和环境变量文件，不删除工作目录 */
    static Result remove(String processName) throws IOException {
        String unit = unitName(processName);
        StringBuilder output = new StringBuilder();
        output.append(run(120, "systemctl", "stop", unit).output());
        output.append(run(30, "systemctl", "disable", unit).output());
        Files.deleteIfExists(UNIT_DIR.resolve(unit));
        Files.deleteIfExists(envFile(processName));
        daemonReload();
        return new Result(true, output.append("已删除服务 ").append(unit).append('\n').toString());
    }

    private static String activeState(String unit) {
        return run(10, "systemctl", "is-active", unit).output().trim();
    }

    private static String diagnose(String unit) {
        return "\n" + run(10, "systemctl", "status", unit, "--no-pager", "-n", "0").output()
                + "\n" + run(10, "journalctl", "-u", unit, "-n", "30", "--no-pager", "-o", "short-iso").output();
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /** 输出写到临时文件，避免输出多时管道写满卡住 */
    static Result run(int timeoutSeconds, String... command) {
        List<String> parts = new ArrayList<>(List.of(command));
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("admin-agent-", ".out");
            Process process = new ProcessBuilder(parts).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, String.join(" ", parts) + " 超时\n");
            }
            return new Result(process.exitValue() == 0, new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new Result(false, String.join(" ", parts) + " 执行失败：" + exception.getMessage() + "\n");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(false, "被中断\n");
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            // 临时文件删除失败不影响结果
        }
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
