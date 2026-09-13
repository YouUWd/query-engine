package com.example.schoolquery.plan;
public record PaginationPlan(int pageNo,int pageSize){public PaginationPlan{if(pageNo<1||pageSize<1)throw new IllegalArgumentException("invalid pagination");}public int offset(){return Math.multiplyExact(pageNo-1,pageSize);}}
