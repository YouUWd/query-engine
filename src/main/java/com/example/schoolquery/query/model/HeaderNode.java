package com.example.schoolquery.query.model;

import java.util.List;

/** @deprecated UI/BFF concern; use engine.ColumnMeta for platform contracts. */
@Deprecated(forRemoval = false)
public record HeaderNode(Long fieldId,Long moduleId,String label,String dataIndex,List<HeaderNode> children){
    public static HeaderNode leaf(long fieldId,String label,String dataIndex){return new HeaderNode(fieldId,null,label,dataIndex,null);}
    public static HeaderNode module(long moduleId,String label,List<HeaderNode> children){return new HeaderNode(null,moduleId,label,null,children);}
    public boolean isLeaf(){return fieldId!=null;}
}
