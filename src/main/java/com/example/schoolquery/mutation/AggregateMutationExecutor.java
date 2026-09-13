package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.*;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Transactional aggregate executor for one datasource. Parent inserts precede children; deletes reverse the order. */
public final class AggregateMutationExecutor {
    private final MetadataRegistry registry;
    private final RelationResolver relations;
    public AggregateMutationExecutor(MetadataRegistry registry, RelationResolver relations){this.registry=Objects.requireNonNull(registry);this.relations=Objects.requireNonNull(relations);}
    public int execute(DSLContext dsl, AggregateMutation mutation){return dsl.transactionResult(c->apply(c.dsl(),mutation,null,null));}
    private int apply(DSLContext dsl,AggregateMutation m,SysModule parent,Object parentKey){
        SysModule module=registry.module(m.moduleId());
        return switch(m.operation()){case INSERT->insert(dsl,m,module,parent,parentKey);case UPDATE->update(dsl,m,module,parent,parentKey);case DELETE->delete(dsl,m,module,parent,parentKey);};
    }
    private int insert(DSLContext dsl,AggregateMutation m,SysModule module,SysModule parent,Object parentKey){
        Map<SysModuleField,Object> values=values(module,m.values());
        if(parent!=null&&parentKey!=null) applyParentForeignKey(module,parent,parentKey,values);
        Table<?> table=table(name(module.primaryTable()));
        InsertSetMoreStep<?> step=null;
        for(var e:values.entrySet()) step=step==null?dsl.insertInto(table).set(field(e.getKey()),e.getValue()):step.set(field(e.getKey()),e.getValue());
        if(step==null)throw new IllegalArgumentException("Aggregate INSERT has no values for module "+module.id());
        Object key=generatedKey(step,module); int count=1;
        for(AggregateMutation child:m.children())count+=apply(dsl,child,module,key);
        return count;
    }
    private int update(DSLContext dsl,AggregateMutation m,SysModule module,SysModule parent,Object parentKey){
        Map<SysModuleField,Object> values=values(module,m.values()); SysModuleField pk=primaryKeyField(module); Object key=pk==null?null:values.get(pk); if(key==null)key=parentKey;
        if(key==null)throw new IllegalArgumentException("Aggregate UPDATE requires primary key"); values.remove(pk);
        Table<?> table=table(name(module.primaryTable())); UpdateSetMoreStep<?> step=null;
        for(var e:values.entrySet())step=step==null?dsl.update(table).set(field(e.getKey()),e.getValue()):step.set(field(e.getKey()),e.getValue());
        int count=step==null?0:step.where(field(pk).eq(key)).execute();
        for(AggregateMutation child:m.children())count+=apply(dsl,child,module,key);
        if(m.saveMode()==AggregateMutation.SaveMode.FULL_SYNC&&m.orphanRemoval())count+=removeOrphans(dsl,module,key,m.children());
        return count;
    }
    private int delete(DSLContext dsl,AggregateMutation m,SysModule module,SysModule parent,Object parentKey){
        SysModuleField pk=primaryKeyField(module); Map<SysModuleField,Object> values=values(module,m.values()); Object key=pk==null?parentKey:values.get(pk); if(key==null)throw new IllegalArgumentException("Aggregate DELETE requires primary key");
        int count=0; for(AggregateMutation child:m.children())count+=apply(dsl,child,module,key); count+=dsl.deleteFrom(table(name(module.primaryTable()))).where(field(pk).eq(key)).execute(); return count;
    }
    private void applyParentForeignKey(SysModule child,SysModule parent,Object parentKey,Map<SysModuleField,Object> values){
        if(child.primaryTable().equals(parent.primaryTable()))return; SysTableRelation rel=relations.relationOf(parent.primaryTable(),child.primaryTable()); boolean parentIsMain=rel.mainTable().equals(parent.primaryTable());
        values.putIfAbsent(findColumn(child,parentIsMain?rel.joinField():rel.mainField()),parentKey);
    }
    private int removeOrphans(DSLContext dsl,SysModule parent,Object parentKey,List<AggregateMutation> children){
        int count=0; for(AggregateMutation cm:children){SysModule child=registry.module(cm.moduleId());if(child.primaryTable().equals(parent.primaryTable()))continue;SysTableRelation rel=relations.relationOf(parent.primaryTable(),child.primaryTable());boolean parentIsMain=rel.mainTable().equals(parent.primaryTable());SysModuleField fk=findColumn(child,parentIsMain?rel.joinField():rel.mainField());SysModuleField pk=primaryKeyField(child);if(pk==null)continue;Set<Object> keep=new HashSet<>();for(AggregateMutation item:cm.children()){Object id=values(child,item.values()).get(pk);if(id!=null)keep.add(id);}Condition condition=field(fk).eq(parentKey);if(!keep.isEmpty())condition=condition.and(field(pk).notIn(keep));count+=dsl.deleteFrom(table(name(child.primaryTable()))).where(condition).execute();}return count;
    }
    private Object generatedKey(InsertSetMoreStep<?> step,SysModule module){SysModuleField pk=primaryKeyField(module);if(pk==null){step.execute();return null;}try{return step.returning(field(pk)).fetchOne(field(pk));}catch(Exception e){step.execute();return null;}}
    private SysModuleField primaryKeyField(SysModule module){for(var fs:registry.fieldsGroupedByTable(module.id()).values())for(SysModuleField f:fs)if("id".equalsIgnoreCase(f.columnName()))return f;return null;}
    private Map<SysModuleField,Object> values(SysModule module,Map<String,Object> input){Map<SysModuleField,Object> r=new LinkedHashMap<>();for(var e:input.entrySet())r.put(resolve(module,e.getKey()),e.getValue());return r;}
    private SysModuleField resolve(SysModule module,String key){String x=key.replace("`","").trim();if(x.matches("f\\d+")){SysModuleField f=registry.field(Long.parseLong(x.substring(1)));if(!registry.ancestorChain(f.moduleId()).contains(module.id()))throw new IllegalArgumentException("Field "+key+" is outside module "+module.id());return f;}return findColumn(module,x.contains(".")?x.substring(x.lastIndexOf('.')+1):x);}
    private SysModuleField findColumn(SysModule module,String column){List<SysModuleField> r=new ArrayList<>();for(var fs:registry.fieldsGroupedByTable(module.id()).values())for(SysModuleField f:fs)if(f.columnName().equalsIgnoreCase(column))r.add(f);if(r.size()!=1)throw new IllegalArgumentException("Unknown or ambiguous column '"+column+"' in module "+module.id());return r.get(0);}
    private Field<Object> field(SysModuleField meta){return field(name(meta.tableName(),meta.columnName()),Object.class);}
}
