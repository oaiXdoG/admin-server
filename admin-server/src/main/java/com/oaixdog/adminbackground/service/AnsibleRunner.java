package com.oaixdog.adminbackground.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.OpsHost;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行 Ansible：管理后台所在机器上的 ansible-playbook、ansible 命令，以 ops.ssh.user + ops.ssh.key 通过 SSH 连接主机。
 * <p>
 * 每次调用生成临时的 inventory（主机别名为 h&lt;id&gt;）和 extra vars 文件，参数逐个传入、不经过 shell。
 * SSH 复用连接（ControlPersist 60 秒），同一台主机连续操作时不用每次重新握手；第一次连接时自动记住主机指纹。
 */
@Service
@RequiredArgsConstructor
public class AnsibleRunner {

    private static final Logger log = LoggerFactory.getLogger(AnsibleRunner.class);

    /** minimal 输出里每台主机结果的开头：h3 | CHANGED | rc=0 >> 或 h3 | UNREACHABLE! => {...} */
    private static final Pattern RESULT_HEAD = Pattern.compile("^h(\\d+) \\| ([A-Z!]+)(?: \\| rc=(-?\\d+))? (>>|=>) ?(.*)$");

    private final ApplicationProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 一台主机上 shell 命令的结果；reachable 为 false 时 output 为连接失败的原因 */
    public record ShellResult(boolean reachable, int rc, String output) {
        public boolean ok() {
            return reachable && rc == 0;
        }
    }

    /**
     * 执行 playbook，输出逐行交给 out；返回退出码（0 成功）。
     *
     * @param playbook ansible 目录下的文件名，例如 app_deploy.yml
     */
    public int playbook(List<OpsHost> hosts, String playbook, Map<String, Object> vars, Consumer<String> out, long timeoutSeconds) {
        Path file = Path.of(properties.getOpsAnsibleDir(), playbook).toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.OPS_ANSIBLE_FAILED, "找不到 playbook：" + file);
        }
        Path inventory = null;
        Path varsFile = null;
        try {
            inventory = inventory(hosts);
            varsFile = Files.createTempFile("ops-vars-", ".json");
            Files.writeString(varsFile, objectMapper.writeValueAsString(vars == null ? Map.of() : vars), StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of("ansible-playbook", "-i", inventory.toString(),
                    "-u", properties.getOpsSshUser(), "--private-key", properties.getOpsSshKey(), "-e", "@" + varsFile, file.toString()));
            return run(command, Map.of(), out, timeoutSeconds);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPS_ANSIBLE_FAILED, e.getMessage());
        } finally {
            deleteQuietly(inventory);
            deleteQuietly(varsFile);
        }
    }

    /** 在多台主机上并行执行同一条 shell 命令，返回 主机 id → 结果；没有返回结果的主机不在 map 里 */
    public Map<Long, ShellResult> shell(List<OpsHost> hosts, String command, long timeoutSeconds) {
        if (hosts.isEmpty()) {
            return Map.of();
        }
        Path inventory = null;
        try {
            inventory = inventory(hosts);
            // 命令 base64 后在服务器上解码执行：ansible 会把命令里的 key=value 当成模块参数解析，引号、反斜杠也会被处理
            String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
            List<String> args = List.of("ansible", "all", "-i", inventory.toString(), "-m", "ansible.builtin.shell",
                    "-a", "echo " + encoded + " | base64 -d | sh",
                    "-u", properties.getOpsSshUser(), "--private-key", properties.getOpsSshKey(),
                    "-f", String.valueOf(Math.min(hosts.size(), 20)), "-T", "10");
            List<String> lines = new ArrayList<>();
            run(args, Map.of("ANSIBLE_STDOUT_CALLBACK", "minimal", "ANSIBLE_LOAD_CALLBACK_PLUGINS", "1"), lines::add, timeoutSeconds);
            return parseMinimal(lines);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPS_ANSIBLE_FAILED, e.getMessage());
        } finally {
            deleteQuietly(inventory);
        }
    }

    /** 单台主机执行 shell 命令；连不上或命令失败时抛出 OPS_ANSIBLE_FAILED，message 为输出 */
    public String shell(OpsHost host, String command, long timeoutSeconds) {
        ShellResult result = shell(List.of(host), command, timeoutSeconds).get(host.getId());
        if (result == null) {
            throw new BusinessException(ErrorCode.OPS_ANSIBLE_FAILED, "没有返回结果");
        }
        if (!result.ok()) {
            throw new BusinessException(ErrorCode.OPS_ANSIBLE_FAILED,
                    result.reachable() ? result.output() : "连接主机失败：" + result.output());
        }
        return result.output();
    }

    /** shell 命令里的参数：单引号括起来 */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private int run(List<String> command, Map<String, String> extraEnv, Consumer<String> out, long timeoutSeconds) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("ANSIBLE_NOCOLOR", "1");
        env.put("ANSIBLE_FORCE_COLOR", "0");
        env.put("ANSIBLE_RETRY_FILES_ENABLED", "0");
        env.put("ANSIBLE_DEPRECATION_WARNINGS", "0");
        env.put("ANSIBLE_PYTHON_INTERPRETER", "auto_silent");
        env.put("ANSIBLE_SSH_ARGS", "-o ControlMaster=auto -o ControlPersist=60s -o StrictHostKeyChecking=accept-new");
        env.put("LC_ALL", "C.UTF-8");
        env.putAll(extraEnv);
        Process process = builder.start();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.accept(line);
                }
            } catch (IOException e) {
                log.debug("读取 Ansible 输出中断", e);
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                out.accept("执行超过 " + timeoutSeconds + " 秒，已中断");
                return -1;
            }
            reader.join(5000);
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        }
    }

    private static Path inventory(List<OpsHost> hosts) throws IOException {
        StringBuilder text = new StringBuilder();
        for (OpsHost host : hosts) {
            text.append('h').append(host.getId()).append(" ansible_host=").append(host.getIp())
                    .append(" ansible_port=").append(host.getSshPort() == null ? 22 : host.getSshPort()).append('\n');
        }
        Path file = Files.createTempFile("ops-inventory-", ".ini");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** minimal 回调：每台主机一段，第一行为结果头，后面是命令输出（UNREACHABLE、FAILED! 时为 JSON） */
    private Map<Long, ShellResult> parseMinimal(List<String> lines) {
        Map<Long, ShellResult> results = new HashMap<>();
        Long current = null;
        String status = null;
        int rc = 0;
        StringBuilder output = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = RESULT_HEAD.matcher(line);
            if (matcher.matches()) {
                if (current != null) {
                    results.put(current, result(status, rc, output.toString()));
                }
                current = Long.parseLong(matcher.group(1));
                status = matcher.group(2);
                rc = matcher.group(3) == null ? ("SUCCESS".equals(status) || "CHANGED".equals(status) ? 0 : 1) : Integer.parseInt(matcher.group(3));
                output.setLength(0);
                if (!matcher.group(5).isEmpty()) {
                    output.append(matcher.group(5));
                }
            } else if (current != null) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        if (current != null) {
            results.put(current, result(status, rc, output.toString()));
        }
        return results;
    }

    private ShellResult result(String status, int rc, String output) {
        if (status.startsWith("UNREACHABLE")) {
            return new ShellResult(false, -1, jsonMessage(output));
        }
        if (status.startsWith("FAILED") && output.startsWith("{")) {
            return new ShellResult(true, rc == 0 ? 1 : rc, jsonMessage(output));
        }
        return new ShellResult(true, rc, output);
    }

    /** {"msg": "..."} 取 msg，其他原样返回 */
    private String jsonMessage(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (node.hasNonNull("msg")) {
                return node.get("msg").asText();
            }
        } catch (IOException e) {
            // 不是 JSON
        }
        return json;
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.debug("删除临时文件失败，file={}", file, e);
            }
        }
    }
}
