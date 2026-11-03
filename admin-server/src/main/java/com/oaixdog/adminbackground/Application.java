package com.oaixdog.adminbackground;

import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.protocol.AccessMenuRequest;
import com.oaixdog.adminbackground.protocol.AccountCreateRequest;
import com.oaixdog.adminbackground.protocol.AccountListRequest;
import com.oaixdog.adminbackground.protocol.AccountRoleOptionsRequest;
import com.oaixdog.adminbackground.protocol.AccountRoleUpdateRequest;
import com.oaixdog.adminbackground.protocol.AccountStatusRequest;
import com.oaixdog.adminbackground.protocol.AccountUpdateRequest;
import com.oaixdog.adminbackground.protocol.BackupDownloadRequest;
import com.oaixdog.adminbackground.protocol.BackupGetRequest;
import com.oaixdog.adminbackground.protocol.BackupRemoveRequest;
import com.oaixdog.adminbackground.protocol.BackupRunRequest;
import com.oaixdog.adminbackground.protocol.BackupUpdateRequest;
import com.oaixdog.adminbackground.protocol.DataQueryExportRequest;
import com.oaixdog.adminbackground.protocol.DataQueryRequest;
import com.oaixdog.adminbackground.protocol.DataSourceCreateRequest;
import com.oaixdog.adminbackground.protocol.DataSourceListRequest;
import com.oaixdog.adminbackground.protocol.DataSourceQueriesRequest;
import com.oaixdog.adminbackground.protocol.DataSourceRemoveRequest;
import com.oaixdog.adminbackground.protocol.DataSourceTestRequest;
import com.oaixdog.adminbackground.protocol.DataSourceUpdateRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateCreateRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateListRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateRemoveRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateUpdateRequest;
import com.oaixdog.adminbackground.protocol.LogEventCreateRequest;
import com.oaixdog.adminbackground.protocol.LogEventListRequest;
import com.oaixdog.adminbackground.protocol.LogEventUpdateRequest;
import com.oaixdog.adminbackground.protocol.LogQueryRequest;
import com.oaixdog.adminbackground.protocol.LoginRequest;
import com.oaixdog.adminbackground.protocol.LogoutRequest;
import com.oaixdog.adminbackground.protocol.MenuCreateRequest;
import com.oaixdog.adminbackground.protocol.MenuListRequest;
import com.oaixdog.adminbackground.protocol.MenuRemoveRequest;
import com.oaixdog.adminbackground.protocol.MenuUpdateRequest;
import com.oaixdog.adminbackground.protocol.OperationLogListRequest;
import com.oaixdog.adminbackground.protocol.ProfileGetRequest;
import com.oaixdog.adminbackground.protocol.ProfilePasswordRequest;
import com.oaixdog.adminbackground.protocol.ProfileUpdateRequest;
import com.oaixdog.adminbackground.protocol.RoleCreateRequest;
import com.oaixdog.adminbackground.protocol.RoleListRequest;
import com.oaixdog.adminbackground.protocol.RoleMenuListRequest;
import com.oaixdog.adminbackground.protocol.RoleMenuUpdateRequest;
import com.oaixdog.adminbackground.protocol.RoleRemoveRequest;
import com.oaixdog.adminbackground.protocol.RoleUpdateRequest;
import com.oaixdog.adminbackground.protocol.SystemSettingGetRequest;
import com.oaixdog.adminbackground.protocol.SystemSettingUpdateRequest;
import com.zfoo.net.core.HostAndPort;
import com.zfoo.net.core.http.HttpServer;
import com.zfoo.net.packet.DecodedPacketInfo;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.ThreadUtils;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public class Application {
    public static void main(String[] args) {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("config.xml");

        ApplicationProperties properties = context.getBean(ApplicationProperties.class);
        context.registerShutdownHook();

        Function<FullHttpRequest, DecodedPacketInfo> requestResolver = rawRequest -> {
            HttpAttachment attachment = HttpAttachment.valueOf(rawRequest, HttpResponseStatus.OK);
            QueryStringDecoder uri = new QueryStringDecoder(rawRequest.uri());
            String route = rawRequest.method().name() + " " + uri.path();

            Object request = switch (route) {
                case "POST /login" -> parseLoginRequest(rawRequest);
                case "POST /account/create" -> parseRequest(rawRequest, AccountCreateRequest.class);
                case "POST /account/list" -> parseRequest(rawRequest, AccountListRequest.class);
                case "POST /account/update" -> parseRequest(rawRequest, AccountUpdateRequest.class);
                case "POST /logout" -> parseRequest(rawRequest, LogoutRequest.class);
                case "POST /account/status" -> parseRequest(rawRequest, AccountStatusRequest.class);
                case "POST /account/role/update" -> parseRequest(rawRequest, AccountRoleUpdateRequest.class);
                case "POST /profile/get" -> parseRequest(rawRequest, ProfileGetRequest.class);
                case "POST /profile/update" -> parseRequest(rawRequest, ProfileUpdateRequest.class);
                case "POST /profile/password" -> parseRequest(rawRequest, ProfilePasswordRequest.class);
                case "POST /system/setting/get" -> parseRequest(rawRequest, SystemSettingGetRequest.class);
                case "POST /system/setting/update" -> parseRequest(rawRequest, SystemSettingUpdateRequest.class);
                case "POST /account/role/options" -> parseRequest(rawRequest, AccountRoleOptionsRequest.class);
                case "POST /menu/list" -> parseRequest(rawRequest, MenuListRequest.class);
                case "POST /access/menu" -> parseRequest(rawRequest, AccessMenuRequest.class);
                case "POST /role/list" -> parseRequest(rawRequest, RoleListRequest.class);
                case "POST /role/create" -> parseRequest(rawRequest, RoleCreateRequest.class);
                case "POST /role/update" -> parseRequest(rawRequest, RoleUpdateRequest.class);
                case "POST /role/remove" -> parseRequest(rawRequest, RoleRemoveRequest.class);
                case "POST /role/menu/list" -> parseRequest(rawRequest, RoleMenuListRequest.class);
                case "POST /role/menu/update" -> parseRequest(rawRequest, RoleMenuUpdateRequest.class);
                case "POST /menu/create" -> parseRequest(rawRequest, MenuCreateRequest.class);
                case "POST /menu/update" -> parseRequest(rawRequest, MenuUpdateRequest.class);
                case "POST /menu/remove" -> parseRequest(rawRequest, MenuRemoveRequest.class);
                case "POST /data/source/list" -> parseRequest(rawRequest, DataSourceListRequest.class);
                case "POST /data/source/create" -> parseRequest(rawRequest, DataSourceCreateRequest.class);
                case "POST /data/source/update" -> parseRequest(rawRequest, DataSourceUpdateRequest.class);
                case "POST /data/source/remove" -> parseRequest(rawRequest, DataSourceRemoveRequest.class);
                case "POST /data/source/test" -> parseRequest(rawRequest, DataSourceTestRequest.class);
                case "POST /data/source/queries" -> parseRequest(rawRequest, DataSourceQueriesRequest.class);
                case "POST /data/template/list" -> parseRequest(rawRequest, DataTemplateListRequest.class);
                case "POST /data/template/create" -> parseRequest(rawRequest, DataTemplateCreateRequest.class);
                case "POST /data/template/update" -> parseRequest(rawRequest, DataTemplateUpdateRequest.class);
                case "POST /data/template/remove" -> parseRequest(rawRequest, DataTemplateRemoveRequest.class);
                case "POST /data/query" -> parseRequest(rawRequest, DataQueryRequest.class);
                case "POST /data/query/export" -> parseRequest(rawRequest, DataQueryExportRequest.class);
                case "POST /log/event/list" -> parseRequest(rawRequest, LogEventListRequest.class);
                case "POST /log/event/create" -> parseRequest(rawRequest, LogEventCreateRequest.class);
                case "POST /log/event/update" -> parseRequest(rawRequest, LogEventUpdateRequest.class);
                case "POST /log/query" -> parseRequest(rawRequest, LogQueryRequest.class);
                case "POST /system/operation-log/list" -> parseRequest(rawRequest, OperationLogListRequest.class);
                case "POST /system/backup/get" -> parseRequest(rawRequest, BackupGetRequest.class);
                case "POST /system/backup/update" -> parseRequest(rawRequest, BackupUpdateRequest.class);
                case "POST /system/backup/run" -> parseRequest(rawRequest, BackupRunRequest.class);
                case "POST /system/backup/download" -> parseRequest(rawRequest, BackupDownloadRequest.class);
                case "POST /system/backup/remove" -> parseRequest(rawRequest, BackupRemoveRequest.class);
                default -> new RuntimeException("Unknown route: " + route);
            };

            return DecodedPacketInfo.valueOf(request, attachment);
        };

        HttpServer server = new HttpServer(HostAndPort.valueOf(properties.getHost(), properties.getPort()), requestResolver);

        server.start();
        ThreadUtils.sleep(Long.MAX_VALUE);
    }

    private static LoginRequest parseLoginRequest(FullHttpRequest rawRequest) {
        return parseRequest(rawRequest, LoginRequest.class);
    }

    private static <T> T parseRequest(FullHttpRequest rawRequest, Class<T> requestClass) {
        try {
            return parseJsonBody(rawRequest, requestClass);
        } catch (RuntimeException exception) {
            try {
                return requestClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException reflectionException) {
                throw new IllegalStateException("无法创建请求对象: " + requestClass.getName(), reflectionException);
            }
        }
    }

    private static <T> T parseJsonBody(FullHttpRequest rawRequest, Class<T> requestClass) {
        String body = rawRequest.content().toString(StandardCharsets.UTF_8);
        return JsonUtils.string2Object(body, requestClass);
    }
}
