package com.example.schoolquery.querytree;

import com.example.schoolquery.model.SysTableRelation;

/**
 * 一个 1:N 嵌套子级：
 *
 * @param childModuleId 触发这次嵌套的具体子模块 id（渲染成 JSON 时，这个 id 就是嵌套数组的 key，
 *                      比如 "103"）。注意 group 内部可能因为 SAME_ENTITY 又合并了别的模块，
 *                      但对外暴露的 key 固定用这个入口模块 id。
 * @param relation      父子表之间的关系（决定 JOIN/过滤字段）
 * @param group         子级自己的 FlatGroup（内部可能还有更深的嵌套）
 */
public record NestedGroup(long childModuleId, SysTableRelation relation, FlatGroup group) {}
