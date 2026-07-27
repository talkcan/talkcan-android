package io.talkcan.profile

/**
 * 3.2: One exact profile-schema validator shared by package inspection,
 * profile mutation, package update, rollback, reinstall, and runtime grant
 * construction.
 *
 * The validator is pure and deterministic: it never executes Lua, touches
 * protected storage, or performs I/O. It validates candidate payloads against
 * a declared [ProfileSchema] and revalidates existing records when a schema
 * changes.
 */
public object ProfileSchemaValidator {

    /**
     * Validate a candidate scalar payload and secret-reference state against
     * a schema. Returns null on success, or a typed failure reason.
     *
     * Rules:
     * - Every required field must be present (scalar or secret reference).
     * - Scalar values must match their declared type exactly.
     * - String values with allowedValues must be in the set.
     * - Integer values must be within [minimum, maximum].
     * - Secret fields must carry a reference state, never a scalar value.
     * - Scalar fields must carry a scalar value, never a secret reference.
     * - No unknown field IDs.
     * - String values must not exceed byte bounds.
     */
    public fun validatePayload(
        schema: ProfileSchema,
        scalarPayload: Map<String, ProfileScalarValue>,
        secretReferences: Map<String, SecretReferenceState>,
    ): ProfileFailure? {
        val secretIds = schema.secretFieldIds()
        val scalarIds = schema.scalarFieldIds()

        // Reject unknown scalar keys
        for (key in scalarPayload.keys) {
            if (key !in scalarIds) {
                return ProfileFailure.ValidationFailed("Unknown scalar field: $key")
            }
        }
        // Reject unknown secret keys
        for (key in secretReferences.keys) {
            if (key !in secretIds) {
                return ProfileFailure.ValidationFailed("Unknown secret field: $key")
            }
        }
        // Scalar fields must not appear in secret references
        for (key in secretReferences.keys) {
            if (key in scalarIds) {
                return ProfileFailure.ValidationFailed("Scalar field '$key' must not carry a secret reference")
            }
        }
        // Secret fields must not appear in scalar payload
        for (key in scalarPayload.keys) {
            if (key in secretIds) {
                return ProfileFailure.ValidationFailed("Secret field '$key' must not carry a scalar value")
            }
        }

        for (field in schema.dataFields) {
            when (field) {
                is ProfileFieldDeclaration.SecretField -> {
                    val ref = secretReferences[field.id]
                    if (field.required) {
                        if (ref == null || ref is SecretReferenceState.Absent) {
                            return ProfileFailure.ValidationFailed("Required secret field '${field.id}' is missing")
                        }
                    }
                    // Optional absent secrets are fine; null or Absent both OK
                }
                is ProfileFieldDeclaration.StringField -> {
                    val value = scalarPayload[field.id]
                    if (value == null) {
                        if (field.required && field.default == null) {
                            return ProfileFailure.ValidationFailed("Required string field '${field.id}' is missing")
                        }
                        continue
                    }
                    if (value !is ProfileScalarValue.StringValue) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' expects string, got ${valueTypeName(value)}"
                        )
                    }
                    if (field.allowedValues != null && value.value !in field.allowedValues) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' value not in allowed values"
                        )
                    }
                }
                is ProfileFieldDeclaration.BooleanField -> {
                    val value = scalarPayload[field.id]
                    if (value == null) {
                        if (field.required && field.default == null) {
                            return ProfileFailure.ValidationFailed("Required boolean field '${field.id}' is missing")
                        }
                        continue
                    }
                    if (value !is ProfileScalarValue.BooleanValue) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' expects boolean, got ${valueTypeName(value)}"
                        )
                    }
                }
                is ProfileFieldDeclaration.IntegerField -> {
                    val value = scalarPayload[field.id]
                    if (value == null) {
                        if (field.required && field.default == null) {
                            return ProfileFailure.ValidationFailed("Required integer field '${field.id}' is missing")
                        }
                        continue
                    }
                    if (value !is ProfileScalarValue.IntegerValue) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' expects integer, got ${valueTypeName(value)}"
                        )
                    }
                    if (field.minimum != null && value.value < field.minimum) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' value ${value.value} below minimum ${field.minimum}"
                        )
                    }
                    if (field.maximum != null && value.value > field.maximum) {
                        return ProfileFailure.ValidationFailed(
                            "Field '${field.id}' value ${value.value} above maximum ${field.maximum}"
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Revalidate an existing profile record's payload against a (possibly
     * changed) schema. Returns null if compatible, or a typed failure.
     *
     * The record's payload is preserved unchanged regardless of outcome;
     * the caller projects [ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE]
     * on failure without coercing or defaulting.
     */
    public fun revalidateRecord(
        schema: ProfileSchema,
        record: ProfileRecord,
    ): ProfileFailure? = validatePayload(schema, record.scalarPayload, record.secretReferences)

    /**
     * Validate a schema declaration is structurally well-formed. The
     * [ProfileSchema] constructor already enforces invariants; this method
     * performs additional cross-checks suitable for package inspection
     * (e.g. total payload bound estimation).
     */
    public fun validateSchemaDeclaration(schema: ProfileSchema): ProfileFailure? {
        // Estimate maximum payload size from defaults and allowed values
        var estimatedBytes = 0
        for (field in schema.dataFields) {
            estimatedBytes += field.id.toByteArray(Charsets.UTF_8).size + 8 // key + type tag
            when (field) {
                is ProfileFieldDeclaration.StringField -> {
                    val maxVal = if (field.allowedValues != null) {
                        field.allowedValues.maxOf { it.toByteArray(Charsets.UTF_8).size }
                    } else {
                        ProfileLimits.MAX_STRING_VALUE_BYTES
                    }
                    estimatedBytes += maxVal
                }
                is ProfileFieldDeclaration.BooleanField -> estimatedBytes += 5
                is ProfileFieldDeclaration.IntegerField -> estimatedBytes += 20
                is ProfileFieldDeclaration.SecretField -> estimatedBytes += ProfileLimits.MAX_PROFILE_ID_BYTES
            }
        }
        if (estimatedBytes > ProfileLimits.MAX_DOCUMENT_BYTES) {
            return ProfileFailure.BoundsExceeded(
                "Estimated profile payload of $estimatedBytes bytes exceeds document bound"
            )
        }
        return null
    }

    private fun valueTypeName(value: ProfileScalarValue): String = when (value) {
        is ProfileScalarValue.StringValue -> "string"
        is ProfileScalarValue.BooleanValue -> "boolean"
        is ProfileScalarValue.IntegerValue -> "integer"
    }
}
