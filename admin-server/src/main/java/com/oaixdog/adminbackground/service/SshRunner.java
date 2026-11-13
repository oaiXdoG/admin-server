package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 通过 SSH 在主机上执行命令：调用管理后台所在机器的 ssh 命令，ssh -p &lt;sshPort&gt; root@&lt;ip&gt;，和在这台机器上手动执行一样，
 * 用运行管理后台的用户的 ~/.ssh 默认私钥和 ~/.ssh/config。
 * 命令原样交给远程的 shell 执行，参数用 {@link #quote} 括起来；需要时从标准输入送数据（脚本、配置文件内容、程序包）。
 * 第一次连接时自动记住主机指纹（StrictHostKeyChecking=accept-new）。
 */
@Service
@RequiredArgsConstructor
public class SshRunner {

    private static final Logger log = LoggerFactory.getLogger(SshRunner.class);

    /** ssh 自己出错（连不上、认证失败）时的退出码 */
    private static final int SSH_ERROR = 255;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 要登录的机器：ssh -p port root@ip */
    public record Target(String ip, int port) {
    }

    /** reachable 为 false 时 output 为连接失败的原因 */
    public record Result(boolean reachable, int exitCode, String output) {
        public boolean ok() {
            return reachable && exitCode == 0;
        }
    }

    /** 送给远程命令标准输入的内容 */
    public interface Input {
        void writeTo(OutputStream out) throws IOException;

        static Input of(byte[] bytes) {
            return out -> out.write(bytes);
        }

        static Input of(Path file) {
            return out -> Files.copy(file, out);
        }
    }

    /** 执行命令，输出（含 stderr）逐行交给 out，返回退出码；连不上时为 255 */
    public int run(Target host, String command, Input input, Consumer<String> out, long timeoutSeconds) {
        return execute(host, command, input, out, timeoutSeconds).exitCode();
    }

    /** 执行命令并返回完整输出；连不上或退出码不为 0 时抛出 OPS_SSH_FAILED，message 为输出 */
    public String exec(Target host, String command, Input input, long timeoutSeconds) {
        Result result = execute(host, command, input, null, timeoutSeconds);
        if (!result.ok()) {
            String output = result.output().strip();
            throw new BusinessException(ErrorCode.OPS_SSH_FAILED, result.reachable() ? output : "连接主机失败：" + output);
        }
        return result.output();
    }

    public String exec(Target host, String command, long timeoutSeconds) {
        return exec(host, command, null, timeoutSeconds);
    }

    /** shell 命令里的参数：单引号括起来 */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** lineOut 为 null 时收集全部输出（原样保留字节，例如配置文件内容）放进 Result.output */
    private Result execute(Target host, String command, Input input, Consumer<String> lineOut, long timeoutSeconds) {
        List<String> args = new ArrayList<>(List.of("ssh",
                "-p", String.valueOf(host.port()),
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=" + CONNECT_TIMEOUT_SECONDS,
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "-o", "LogLevel=ERROR",
                "root@" + host.ip(),
                command));
        Process process;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPS_SSH_FAILED, "执行 ssh 失败：" + e.getMessage());
        }
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                if (lineOut == null) {
                    in.transferTo(collected);
                } else {
                    BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = lines.readLine()) != null) {
                        lineOut.accept(line);
                    }
                }
            } catch (IOException e) {
                log.debug("读取 ssh 输出中断", e);
            }
        });
        Thread writer = Thread.ofVirtual().start(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) {
                    input.writeTo(stdin);
                }
            } catch (IOException e) {
                log.debug("写入 ssh 标准输入中断", e);
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                String message = "执行超过 " + timeoutSeconds + " 秒，已中断";
                if (lineOut != null) {
                    lineOut.accept(message);
                }
                return new Result(true, -1, collected.toString(StandardCharsets.UTF_8) + message);
            }
            reader.join(5000);
            writer.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(true, -1, "已中断");
        }
        int code = process.exitValue();
        return new Result(code != SSH_ERROR, code, collected.toString(StandardCharsets.UTF_8));
    }
}
