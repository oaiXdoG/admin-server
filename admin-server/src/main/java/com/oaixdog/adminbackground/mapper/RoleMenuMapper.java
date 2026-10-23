package com.oaixdog.adminbackground.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oaixdog.adminbackground.entity.RoleMenu;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface RoleMenuMapper extends BaseMapper<RoleMenu> {

    @Select("<script>select menu_id from role_menu where role_id in <foreach collection='roleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach></script>")
    List<Long> listMenuIds(@Param("roleIds") List<Long> roleIds);

    /** 一条语句写入角色的多个菜单，数据库在远端时比逐条插入快得多 */
    @Insert("<script>insert into role_menu (role_id, menu_id) values <foreach collection='menuIds' item='menuId' separator=','>(#{roleId}, #{menuId})</foreach></script>")
    int insertBatch(@Param("roleId") long roleId, @Param("menuIds") Collection<Long> menuIds);
}
