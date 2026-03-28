package com.javaducker.server.model;

import java.util.List;

/**
 * Structured result from parsing a MithraObject or MithraInterface XML definition.
 */
public record ReladomoParseResult(
    String objectName,
    String packageName,
    String tableName,
    String objectType,       // transactional, read-only, embedded-value, interface
    String temporalType,     // none, business-date, processing-date, bitemporal
    String superClass,
    List<String> interfaces,
    String sourceAttributeName,
    String sourceAttributeType,
    List<ReladomoAttribute> attributes,
    List<ReladomoRelationship> relationships,
    List<ReladomoIndex> indices
) {
    public record ReladomoAttribute(
        String name,
        String javaType,
        String columnName,
        boolean nullable,
        boolean primaryKey,
        Integer maxLength,
        boolean trim,
        boolean truncate
    ) {}

    public record ReladomoRelationship(
        String name,
        String cardinality,      // one-to-one, one-to-many, many-to-many
        String relatedObject,
        String reverseRelationshipName,
        String parameters,
        String joinExpression
    ) {}

    public record ReladomoIndex(
        String name,
        String columns,          // comma-separated attribute names
        boolean unique
    ) {}
}
