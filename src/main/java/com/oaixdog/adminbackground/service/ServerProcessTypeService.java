package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.ServerProcess;
import com.oaixdog.adminbackground.entity.ServerProcessType;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.ServerProcessMapper;
import com.oaixdog.adminbackground.mapper.ServerProcessTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 进程类型：项目内自定义的分类（例如 登录服、游戏服），进程必须选一个类型；code 为业务类型编码，与业务服上报的 type 对应，项目内非空时唯一 */
@Service
@RequiredArgsConstructor
public class ServerProcessTypeService {

    private static final String MENU = "server_process";
    private static final int NAME_MAX_LENGTH = 32;
    /** 业务类型编码：空，或小写字母、数字、下划线，最长 32 */
    private static final Pattern CODE = Pattern.compile("^[a-z0-9_]{0,32}$");

    private final PermissionService permissionService;
    private final ServerProcessTypeMapper typeMapper;
    private final ServerProcessMapper processMapper;

    public List<Map<String, Object>> list(String authorization, Long projectId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU);
        return typeMapper.selectList(Wrappers.<ServerProcessType>lambdaQuery().eq(ServerProcessType::getProjectId, access.projectId())
                .orderByAsc(ServerProcessType::getId)).stream().map(this::toResponse).toList();
    }

    public Map<String, Object> save(String authorization, Long projectId, Long typeId, String name, String code) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":update");
        String typeName = name == null ? "" : name.trim();
        String typeCode = code == null ? "" : code.trim();
        if (typeName.isEmpty() || typeName.length() > NAME_MAX_LENGTH || !CODE.matcher(typeCode).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        long now = System.currentTimeMillis();
        ServerProcessType type = typeId == null ? new ServerProcessType() : require(access.projectId(), typeId);
        if (typeId == null) {
            type.setProjectId(access.projectId());
            type.setCreatedAt(now);
        }
        type.setName(typeName);
        type.setCode(typeCode);
        type.setUpdatedAt(now);
        if (typeMapper.selectCount(Wrappers.<ServerProcessType>lambdaQuery().eq(ServerProcessType::getProjectId, access.projectId())
                .eq(ServerProcessType::getName, typeName).ne(typeId != null, ServerProcessType::getId, typeId)) > 0) {
            throw new BusinessException(ErrorCode.PROCESS_TYPE_NAME_EXISTS);
        }
        if (!typeCode.isEmpty() && typeMapper.selectCount(Wrappers.<ServerProcessType>lambdaQuery().eq(ServerProcessType::getProjectId, access.projectId())
                .eq(ServerProcessType::getCode, typeCode).ne(typeId != null, ServerProcessType::getId, typeId)) > 0) {
            throw new BusinessException(ErrorCode.PROCESS_TYPE_CODE_EXISTS);
        }
        try {
            if (typeId == null) {
                typeMapper.insert(type);
            } else {
                typeMapper.updateById(type);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.PROCESS_TYPE_NAME_EXISTS);
        }
        return toResponse(type);
    }

    public void remove(String authorization, Long projectId, Long typeId) {
        PermissionService.ProjectAccess access = permissionService.requireProjectMenu(authorization, projectId, MENU + ":update");
        ServerProcessType type = require(access.projectId(), typeId);
        if (processMapper.selectCount(Wrappers.<ServerProcess>lambdaQuery().eq(ServerProcess::getTypeId, type.getId())) > 0) {
            throw new BusinessException(ErrorCode.PROCESS_TYPE_IN_USE);
        }
        typeMapper.deleteById(type.getId());
    }

    public ServerProcessType require(long projectId, Long typeId) {
        ServerProcessType type = typeId == null ? null : typeMapper.selectById(typeId);
        if (type == null || !Objects.equals(type.getProjectId(), projectId)) {
            throw new BusinessException(ErrorCode.PROCESS_TYPE_NOT_FOUND);
        }
        return type;
    }

    private Map<String, Object> toResponse(ServerProcessType type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", type.getId());
        result.put("name", type.getName());
        result.put("code", type.getCode());
        result.put("processCount", processMapper.selectCount(Wrappers.<ServerProcess>lambdaQuery().eq(ServerProcess::getTypeId, type.getId())));
        return result;
    }
}
