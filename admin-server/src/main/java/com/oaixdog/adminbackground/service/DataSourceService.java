package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.DataSource;
import com.oaixdog.adminbackground.entity.DataTemplate;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.DataSourceMapper;
import com.oaixdog.adminbackground.mapper.DataTemplateMapper;
import com.oaixdog.adminbackground.protocol.DataSourceResponse;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * 数据源：可以配置多个业务服务器查询接口（地址 + 签名密钥），密钥用 security.secret-key 加密保存，接口不返回明文。
 * 管理需要 data_source 下的按钮；查询模板页面选择数据源时只需要 data_template。
 */
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private static final String MENU = "data_source";
    private static final String TEMPLATE_MENU = "data_template";
    private static final int NAME_MAX_LENGTH = 64;
    private static final int URL_MAX_LENGTH = 256;
    private static final int SECRET_MAX_LENGTH = 256;
    private static final int REMARK_MAX_LENGTH = 128;
    private static final int TIMEOUT_MIN = 1_000;
    private static final int TIMEOUT_MAX = 60_000;
    private static final int TIMEOUT_DEFAULT = 10_000;
    public static final String QUERIES_PATH = "/admin/queries";
    public static final String QUERY_PATH = "/admin/query";

    private final PermissionService permissionService;
    private final DataSourceMapper dataSourceMapper;
    private final DataTemplateMapper dataTemplateMapper;
    private final SecretCipher secretCipher;
    private final BusinessClient businessClient;

    public Map<String, Object> list(String authorization) {
        permissionService.requireAnyMenu(authorization, MENU, TEMPLATE_MENU);
        List<DataSourceResponse> list = dataSourceMapper.selectList(Wrappers.<DataSource>lambdaQuery().orderByAsc(DataSource::getId))
                .stream().map(source -> DataSourceResponse.valueOf(source, templateCount(source.getId()))).toList();
        return Map.of("list", list);
    }

    public DataSourceResponse create(String authorization, String name, String url, String secret, Integer timeoutMs, String remark) {
        permissionService.requireMenu(authorization, MENU + ":create");
        if (StringUtils.isBlank(secret)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        DataSource source = new DataSource();
        fill(source, name, url, secret, timeoutMs, remark);
        long now = System.currentTimeMillis();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        requireUniqueName(source);
        try {
            dataSourceMapper.insert(source);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_NAME_EXISTS);
        }
        return DataSourceResponse.valueOf(source, 0);
    }

    /** 密钥留空表示沿用已保存的密钥 */
    public DataSourceResponse update(String authorization, Long sourceId, String name, String url, String secret, Integer timeoutMs, String remark) {
        permissionService.requireMenu(authorization, MENU + ":update");
        DataSource source = requireSource(sourceId);
        fill(source, name, url, secret, timeoutMs, remark);
        source.setUpdatedAt(System.currentTimeMillis());
        requireUniqueName(source);
        try {
            dataSourceMapper.updateById(source);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_NAME_EXISTS);
        }
        return DataSourceResponse.valueOf(source, templateCount(source.getId()));
    }

    public void remove(String authorization, Long sourceId) {
        permissionService.requireMenu(authorization, MENU + ":remove");
        DataSource source = requireSource(sourceId);
        if (templateCount(source.getId()) > 0) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_IN_USE);
        }
        dataSourceMapper.deleteById(source.getId());
    }

    /** 用表单里的值调用查询清单接口；成功返回查询数量，失败抛出带原因的业务错误 */
    public Map<String, Object> test(String authorization, Long sourceId, String url, String secret, Integer timeoutMs) {
        Account operator = permissionService.requireAnyMenu(authorization, MENU + ":create", MENU + ":update");
        String plainSecret = secret;
        if (StringUtils.isBlank(secret)) {
            plainSecret = sourceId == null ? null : secretCipher.decrypt(requireSource(sourceId).getSecret());
        }
        String targetUrl = url == null ? "" : url.trim();
        if (!validUrl(targetUrl) || StringUtils.isBlank(plainSecret)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        BusinessClient.Target target = new BusinessClient.Target(targetUrl, plainSecret, timeout(timeoutMs), operator.getAccount());
        Map<String, Object> data = businessClient.call(target, QUERIES_PATH, Map.of());
        Object queries = data.get("queries");
        return Map.of("queryCount", queries instanceof List<?> list ? list.size() : 0);
    }

    /** 数据源提供的查询清单，建查询模板时自动带出参数和结果字段；原样返回业务服务器的 queries */
    public Object queries(String authorization, Long sourceId) {
        Account operator = permissionService.requireMenu(authorization, TEMPLATE_MENU);
        Map<String, Object> data = businessClient.call(target(operator, requireSource(sourceId)), QUERIES_PATH, Map.of());
        Object queries = data.get("queries");
        return Map.of("queries", queries instanceof List<?> ? queries : List.of());
    }

    /** 调用业务服务器用的目标：解密密钥，带上操作人 */
    public BusinessClient.Target target(Account operator, DataSource source) {
        String secret = secretCipher.decrypt(source.getSecret());
        if (secret == null) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_FAILED, "密钥无法解密，请重新填写数据源密钥");
        }
        return new BusinessClient.Target(source.getUrl(), secret, source.getTimeoutMs(), operator.getAccount());
    }

    public DataSource requireSource(Long sourceId) {
        DataSource source = sourceId == null ? null : dataSourceMapper.selectById(sourceId);
        if (source == null) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_NOT_FOUND);
        }
        return source;
    }

    private void fill(DataSource source, String name, String url, String secret, Integer timeoutMs, String remark) {
        String sourceName = name == null ? "" : name.trim();
        String sourceUrl = url == null ? "" : url.trim();
        String sourceRemark = remark == null ? "" : remark.trim();
        if (!validUrl(sourceUrl) || sourceName.isEmpty() || sourceName.length() > NAME_MAX_LENGTH || sourceRemark.length() > REMARK_MAX_LENGTH
                || (secret != null && secret.length() > SECRET_MAX_LENGTH)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        source.setName(sourceName);
        source.setUrl(sourceUrl.replaceAll("/+$", ""));
        source.setTimeoutMs(timeout(timeoutMs));
        source.setRemark(sourceRemark);
        if (StringUtils.isNotBlank(secret)) {
            source.setSecret(secretCipher.encrypt(secret));
        }
    }

    private static int timeout(Integer timeoutMs) {
        int value = timeoutMs == null ? TIMEOUT_DEFAULT : timeoutMs;
        if (value < TIMEOUT_MIN || value > TIMEOUT_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return value;
    }

    private static boolean validUrl(String url) {
        if (url.isEmpty() || url.length() > URL_MAX_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && StringUtils.isNotBlank(uri.getHost())
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private void requireUniqueName(DataSource source) {
        if (dataSourceMapper.selectCount(Wrappers.<DataSource>lambdaQuery()
                .eq(DataSource::getName, source.getName()).ne(source.getId() != null, DataSource::getId, source.getId())) > 0) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_NAME_EXISTS);
        }
    }

    private long templateCount(Long sourceId) {
        return dataTemplateMapper.selectCount(Wrappers.<DataTemplate>lambdaQuery().eq(DataTemplate::getSourceId, sourceId));
    }
}
