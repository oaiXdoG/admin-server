-- 去掉「运维任务」菜单。表 ops_task 仍给正在执行的操作写输出，历史改由操作日志记录。可以重复执行
delete from role_menu where menu_id = 40009;
delete from menu where id = 40009;
