package com.example.schoolquery.query.model;

/**
 * Logical field reference in a Module SQL plan.
 *
 * <p>FieldId is the globally unique logical field identity. A FieldId belongs to exactly one
 * Module and therefore has a fixed business meaning and a fixed physical table/column mapping.
 * ModuleId is retained here as the module-tree context used to validate and quickly locate the
 * query's semantic tree; it is not part of the field's identity.</p>
 */
public record LogicalFieldRef(Long moduleId, Long fieldId) {
    public LogicalFieldRef {
        if (moduleId == null || fieldId == null) {
            throw new IllegalArgumentException("moduleId and fieldId are required");
        }
    }
}
