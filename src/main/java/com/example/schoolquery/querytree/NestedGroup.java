package com.example.schoolquery.querytree;

import com.example.schoolquery.plan.ResolvedRelationPlan;

/** A 1:N nested child with its relation already resolved by logical module identity. */
public record NestedGroup(long childModuleId, ResolvedRelationPlan relation, FlatGroup group) {}
