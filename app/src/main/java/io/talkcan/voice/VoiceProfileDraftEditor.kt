package io.talkcan.voice

/** Pure transactional lifecycle for a materialized voice-profile editor draft. */
internal object VoiceProfileDraftEditor {
    fun apply(
        draft: VoiceProfileDraft,
        operation: VoiceProfileTtlOperation,
    ): VoiceProfileDraft {
        // Compute and validate the complete candidate before constructing any new published state.
        val result = VoiceProfileTtlOperations.apply(draft.current.ttl, operation)
        val nextCurrent = VoiceProfileTensors(ttl = result.ttl, dp = draft.baseline.dp)
        val snapshots =
            if (draft.undoSnapshots.size == VoiceProfileLimits.MAX_UNDO_SNAPSHOTS) {
                draft.undoSnapshots.drop(1) + draft.current
            } else {
                draft.undoSnapshots + draft.current
            }
        val provenance =
            draft.provenance.copy(
                operations = draft.provenance.operations + result.provenance,
            )
        return draft.copy(
            current = nextCurrent,
            provenance = provenance,
            undoSnapshots = snapshots,
        )
    }

    fun undo(draft: VoiceProfileDraft): VoiceProfileDraft {
        require(draft.undoSnapshots.isNotEmpty()) { "Voice profile draft has no operation to undo" }
        require(draft.provenance.operations.size > draft.baselineOperationCount) {
            "Voice profile draft provenance has no editor operation to undo"
        }
        return draft.copy(
            current = draft.undoSnapshots.last(),
            provenance =
                draft.provenance.copy(
                    operations = draft.provenance.operations.dropLast(1),
                ),
            undoSnapshots = draft.undoSnapshots.dropLast(1),
        )
    }

    fun reset(draft: VoiceProfileDraft): VoiceProfileDraft =
        draft.copy(
            current = draft.baseline,
            provenance =
                draft.provenance.copy(
                    operations = draft.provenance.operations.take(draft.baselineOperationCount),
                ),
            undoSnapshots = emptyList(),
        )

    /**
     * Installs a newly materialized source/weight baseline. Edited state must be discarded explicitly;
     * old operations are never replayed against replacement tensors.
     */
    fun replaceBaseline(
        draft: VoiceProfileDraft,
        replacement: VoiceProfileDraft,
        discardLatentEdits: Boolean,
    ): VoiceProfileDraft {
        require(!draft.hasLatentEdits || discardLatentEdits) {
            "Changing voice profile sources or weights requires explicit edited-draft reset"
        }
        require(!replacement.hasLatentEdits && replacement.undoSnapshots.isEmpty()) {
            "Replacement voice profile draft must be a newly materialized baseline"
        }
        return replacement
    }

    fun fromSingleSource(source: VoiceProfileMixSource): VoiceProfileDraft {
        val provenance =
            VoiceProfileProvenance(
                sources =
                    listOf(
                        VoiceProfileSourceProvenance(
                            id = source.summary.id,
                            displayName = source.summary.displayName,
                            normalizedWeight = 1.0,
                        )
                    ),
                weightMode = VoiceProfileWeightMode.MANUAL,
            )
        return VoiceProfileDraft(
            baseline = source.tensors,
            current = source.tensors,
            provenance = provenance,
        )
    }
}
