package com.oaixdog.adminbackground.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oaixdog.adminbackground.entity.AccountRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AccountRoleMapper extends BaseMapper<AccountRole> {

    @Select("select role_id from account_role where account_id = #{accountId}")
    List<Long> listRoleIds(@Param("accountId") long accountId);
}
