package com.example.schoolquery.query.model;
import java.util.List;
public record SortPlan(List<SortItem> items){public SortPlan{items=items==null?List.of():List.copyOf(items);}public record SortItem(LogicalFieldRef field,Direction direction){}public enum Direction{ASC,DESC}}
