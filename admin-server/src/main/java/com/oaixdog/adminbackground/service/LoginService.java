package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.Status;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录会话：令牌对应账号，会话里保存当前项目；同一账号的令牌集中记录，禁用或改密码时一起失效。
 * 会话 12 小时没有操作才失效（每次请求续期）。同一账号最近 15 分钟失败 5 次、同一 IP 失败 20 次后暂停登录。
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final Duration TOKEN_TTL = Duration.ofHours(12);
    private static final String TOKEN_KEY_PREFIX = "login:token:";
    private static final String ACCOUNT_TOKENS_KEY_PREFIX = "login:account:";
    private static final String FAIL_ACCOUNT_KEY_PREFIX = "login:fail:account:";
    private static final String FAIL_IP_KEY_PREFIX = "login:fail:ip:";
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(15);
    private static final int ACCOUNT_FAIL_LIMIT = 5;
    private static final int IP_FAIL_LIMIT = 20;

    private final AccountMapper accountMapper;
    private final RedisService redisService;
    private final PasswordEncoder passwordEncoder;

    public LoginResult login(String account, String password, String ip) {
        String loginAccount = account == null ? "" : account.trim();
        if (StringUtils.isBlank(loginAccount) || loginAccount.length() > 64 || StringUtils.isBlank(password) || password.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        // 锁定期间即使密码正确也不登录，避免被逐个试出来
        String failAccountKey = FAIL_ACCOUNT_KEY_PREFIX + loginAccount.toLowerCase();
        String failIpKey = FAIL_IP_KEY_PREFIX + (ip == null ? "" : ip);
        if (failCount(failAccountKey) >= ACCOUNT_FAIL_LIMIT || failCount(failIpKey) >= IP_FAIL_LIMIT) {
            throw new BusinessException(ErrorCode.LOGIN_LOCKED);
        }
        Account found = accountMapper.selectOne(Wrappers.<Account>lambdaQuery().eq(Account::getAccount, loginAccount));
        if (found == null || !passwordEncoder.matches(password, found.getPasswordHash())) {
            recordFailure(failAccountKey);
            recordFailure(failIpKey);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        redisService.delete(failAccountKey);
        if (found.getStatus() == null || found.getStatus() != Status.ENABLED) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redisService.set(TOKEN_KEY_PREFIX + token, String.valueOf(found.getId()), TOKEN_TTL);
        redisService.addToSet(ACCOUNT_TOKENS_KEY_PREFIX + found.getId(), token, TOKEN_TTL);
        accountMapper.update(null, Wrappers.<Account>lambdaUpdate().eq(Account::getId, found.getId())
                .set(Account::getLastLoginAt, System.currentTimeMillis()));
        return new LoginResult(token, found.getId(), found.getAccount(), found.getUsername());
    }

    public void logout(String authorization) {
        String token = token(authorization);
        if (token == null) {
            return;
        }
        Long accountId = findAccountId(authorization);
        redisService.delete(TOKEN_KEY_PREFIX + token);
        if (accountId != null) {
            redisService.removeFromSet(ACCOUNT_TOKENS_KEY_PREFIX + accountId, token);
        }
    }

    /** 让账号的全部会话失效，用于禁用账号和修改密码 */
    public void invalidateAccount(long accountId) {
        String key = ACCOUNT_TOKENS_KEY_PREFIX + accountId;
        for (String token : redisService.members(key)) {
            redisService.delete(TOKEN_KEY_PREFIX + token);
            }
        redisService.delete(key);
    }

    /** 让账号除当前会话以外的会话失效，用于本人修改密码 */
    public void invalidateOtherSessions(long accountId, String authorization) {
        String current = token(authorization);
        String key = ACCOUNT_TOKENS_KEY_PREFIX + accountId;
        for (String token : redisService.members(key)) {
            if (token.equals(current)) {
                continue;
            }
            redisService.delete(TOKEN_KEY_PREFIX + token);
                redisService.removeFromSet(key, token);
        }
    }

    public Long findAccountId(String authorization) {
        String token = token(authorization);
        if (token == null) {
            return null;
        }
        String accountId = redisService.get(TOKEN_KEY_PREFIX + token);
        if (StringUtils.isBlank(accountId)) {
            return null;
        }
        try {
            return Long.valueOf(accountId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 有操作就续期：令牌和账号的令牌集合都延长到 12 小时 */
    public void touch(String authorization, long accountId) {
        String token = token(authorization);
        if (token == null) {
            return;
        }
        redisService.expire(TOKEN_KEY_PREFIX + token, TOKEN_TTL);
        redisService.expire(ACCOUNT_TOKENS_KEY_PREFIX + accountId, TOKEN_TTL);
    }

    private long failCount(String key) {
        String value = redisService.get(key);
        try {
            return StringUtils.isBlank(value) ? 0 : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 失败次数从第一次失败开始计时 15 分钟 */
    private void recordFailure(String key) {
        if (redisService.increment(key) == 1) {
            redisService.expire(key, FAIL_WINDOW);
        }
    }

    private String token(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    public record LoginResult(String token, long id, String account, String username) {
    }
}
