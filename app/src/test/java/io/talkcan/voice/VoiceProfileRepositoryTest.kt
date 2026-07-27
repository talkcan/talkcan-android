package io.talkcan.voice

import io.talkcan.audio.onnx.SupertonicStyleException
import io.talkcan.audio.onnx.SupertonicVoiceStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceProfileRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var builtInDir: File
    private lateinit var profilesDir: File
    private var idCounter = 0

    @Before
    fun setUp() {
        idCounter = 0
        builtInDir = folder.newFolder("builtins")
        profilesDir = folder.newFolder("profiles")
        for (token in listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")) {
            File(builtInDir, "$token.json").writeText(VoiceProfileCodec.encode(tensors(), MODEL, null))
        }
    }

    // ── 2.1 Codec ────────────────────────────────────────────────────────────

    @Test
    fun nestedRoundTripPreservesTensorsModelAndProvenance() {
        val prov = provenance(
            "builtin:F1" to 0.5,
            "builtin:F2" to 0.5,
            weightMode = VoiceProfileWeightMode.RANDOM,
            randomSeed = 123L,
            operations = listOf(
                VoiceProfileOperationRecord(VoiceProfileOperationKind.INVERT),
                VoiceProfileOperationRecord(
                    VoiceProfileOperationKind.JITTER,
                    parameters = mapOf("amount" to 0.25),
                    seed = 77L,
                ),
            ),
        )
        val source = tensors(ttl = 0.5f, dp = -0.25f)

        val decoded = VoiceProfileCodec.decode(VoiceProfileCodec.encode(source, MODEL, prov))

        assertEquals(source, decoded.tensors)
        assertEquals(MODEL, decoded.model)
        assertEquals(prov, decoded.provenance)
    }

    @Test
    fun encodedDocumentRoundTripsThroughSupertonicVoiceStyle() {
        val json = VoiceProfileCodec.encode(tensors(), MODEL, null)

        val style = SupertonicVoiceStyle.parse(json)

        assertEquals(1, style.ttlDims.dim0)
        assertEquals(50, style.ttlDims.dim1)
        assertEquals(256, style.ttlDims.dim2)
        assertEquals(1, style.dpDims.dim0)
        assertEquals(8, style.dpDims.dim1)
        assertEquals(16, style.dpDims.dim2)
    }

    @Test
    fun flatTensorDataIsRejected() {
        val flat = JSONArray()
        for (i in 0 until Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) flat.put(0.0)
        val root = JSONObject()
            .put("style_ttl", JSONObject().put("dims", JSONArray(listOf(1, 50, 256))).put("data", flat))
            .put("style_dp", nestedComponent(1, 8, 16))

        val error = assertThrows(SupertonicStyleException::class.java) {
            VoiceProfileCodec.decode(root.toString())
        }
        assertTrue(error.message!!.contains("flat"))
    }

    @Test
    fun wrongTensorShapesAreRejected() {
        val root = JSONObject()
            .put("style_ttl", nestedComponent(1, 8, 16))
            .put("style_dp", nestedComponent(1, 8, 16))

        assertThrows(SupertonicStyleException::class.java) {
            VoiceProfileCodec.decode(root.toString())
        }
    }

    // ── 2.2/2.3 Store atomicity & reconciliation ─────────────────────────────

    @Test
    fun successfulCommitLeavesNoTempFiles() {
        val repo = newRepo()

        val result = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Clean")

        assertTrue(result is VoiceProfileMutation.Saved)
        assertTrue(profilesDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(File(profilesDir, "index.json").isFile)
    }

    @Test
    fun interruptedProfileWritePublishesNothing() {
        val repo = newRepo(injector = failingAt(VoiceStoreBoundary.PROFILE_TEMP_WRITE))

        val result = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Doomed")

        assertTrue(result is VoiceProfileMutation.Failed)
        assertTrue(repo.currentCatalogue.custom.isEmpty())
        assertTrue(profilesDir.listFiles()!!.none { it.name.endsWith(".json") })
        assertTrue(profilesDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun interruptedIndexWriteLeavesNoPartialProfile() {
        val repo = newRepo(injector = failingAt(VoiceStoreBoundary.INDEX_RENAME))

        val result = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Doomed")

        assertTrue(result is VoiceProfileMutation.Failed)
        assertTrue(repo.currentCatalogue.custom.isEmpty())
        // The written document is rolled back; no orphaned profile or index survives.
        assertTrue(profilesDir.listFiles()!!.none { it.name.endsWith(".json") })
    }

    @Test
    fun reconciliationSweepsUnindexedOrphanDocuments() {
        val store = makeStore()
        val orphanId = VoiceProfileId("orphan")
        store.writeProfileDocument(orphanId, VoiceProfileCodec.encode(tensors(), MODEL, null))
        assertTrue(store.profileFile(orphanId).isFile)

        val records = store.reconcile(MODEL)

        assertTrue(records.isEmpty())
        assertFalse(store.profileFile(orphanId).isFile)
    }

    @Test
    fun reconciliationClassifiesMissingCorruptIncompatibleAndUnverified() {
        val store = makeStore()
        val valid = VoiceProfileIndexEntry(VoiceProfileId("valid"), "Valid", VoiceProfileKind.MIXED, MODEL.family, MODEL.version)
        val unverified = VoiceProfileIndexEntry(VoiceProfileId("unverified"), "Untagged", VoiceProfileKind.IMPORTED, "", "")
        val missing = VoiceProfileIndexEntry(VoiceProfileId("missing"), "Missing", VoiceProfileKind.MIXED, MODEL.family, MODEL.version)
        val corrupt = VoiceProfileIndexEntry(VoiceProfileId("corrupt"), "Corrupt", VoiceProfileKind.MIXED, MODEL.family, MODEL.version)
        val otherModel = VoiceProfileModelMetadata(MODEL.family, "supertonic-2-2020")
        val incompatible = VoiceProfileIndexEntry(VoiceProfileId("incompat"), "Old", VoiceProfileKind.MIXED, otherModel.family, otherModel.version)

        store.writeProfileDocument(valid.id, VoiceProfileCodec.encode(tensors(), MODEL, null))
        store.writeProfileDocument(unverified.id, VoiceProfileCodec.encode(tensors(), null, null))
        store.writeProfileDocument(missing.id, VoiceProfileCodec.encode(tensors(), MODEL, null))
        store.writeProfileDocument(incompatible.id, VoiceProfileCodec.encode(tensors(), otherModel, null))
        store.writeProfileDocument(corrupt.id, "{ not valid json")
        store.writeIndex(VoiceProfileIndex(VoiceProfileLimits.STORE_VERSION, listOf(valid, unverified, missing, corrupt, incompatible)))
        assertTrue(store.profileFile(missing.id).delete())

        val byId = store.reconcile(MODEL).associateBy { it.entry.id.value }

        assertTrue(byId.getValue("valid") is VoiceProfileRecordState.Available)
        assertTrue(byId.getValue("unverified") is VoiceProfileRecordState.Unverified)
        val missingState = byId.getValue("missing") as VoiceProfileRecordState.Unavailable
        assertEquals(VoiceProfileUnavailableReason.MISSING_FILE, missingState.reason)
        val corruptState = byId.getValue("corrupt") as VoiceProfileRecordState.Unavailable
        assertEquals(VoiceProfileUnavailableReason.CORRUPT_DOCUMENT, corruptState.reason)
        val incompatibleState = byId.getValue("incompat") as VoiceProfileRecordState.Unavailable
        assertEquals(VoiceProfileUnavailableReason.INCOMPATIBLE_MODEL, incompatibleState.reason)
    }

    @Test
    fun reloadSurfacesCorruptionWithoutPublishingPartialProfile() {
        val store = makeStore()
        val repo = repoFor(store)
        val saved = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Fragile") as VoiceProfileMutation.Saved
        assertTrue(saved.summary.selectable)

        store.profileFile(saved.summary.id).writeText("{ truncated")
        repo.reload()

        val summary = repo.currentCatalogue.custom.single()
        val unavailable = summary.availability as VoiceProfileAvailability.Unavailable
        assertEquals(VoiceProfileUnavailableReason.CORRUPT_DOCUMENT, unavailable.reason)
        assertFalse(summary.selectable)
    }

    // ── 2.4 Catalogue ────────────────────────────────────────────────────────

    @Test
    fun cataloguePublishesReadOnlyVerifiedBuiltIns() {
        val repo = newRepo()

        val builtIn = repo.currentCatalogue.builtIn

        assertEquals(10, builtIn.size)
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"), builtIn.map { it.displayName })
        assertTrue(builtIn.all { it.readOnly })
        assertTrue(builtIn.all { it.kind == VoiceProfileKind.BUILT_IN })
        assertTrue(builtIn.all { it.compatibility == VoiceProfileCompatibility.VERIFIED })
        assertTrue(builtIn.all { it.availability is VoiceProfileAvailability.Available })
        assertTrue(builtIn.all { it.selectable })
        assertNotNull(repo.loadTensors(VoiceProfileId("builtin:F1")))
    }

    @Test
    fun missingBuiltInIsPublishedUnavailableButRetained() {
        assertTrue(File(builtInDir, "M5.json").delete())

        val repo = newRepo()

        val m5 = repo.currentCatalogue.builtIn.first { it.displayName == "M5" }
        assertTrue(m5.availability is VoiceProfileAvailability.Unavailable)
        assertFalse(m5.selectable)
        assertEquals(10, repo.currentCatalogue.builtIn.size)
    }

    // ── 2.5 Lifecycle & quotas ───────────────────────────────────────────────

    @Test
    fun saveAsNewPublishesImmediatelyUnderStableIdFilename() {
        val repo = newRepo()

        val saved = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Fresh") as VoiceProfileMutation.Saved

        assertEquals("profile-0", saved.summary.id.value)
        assertTrue(File(profilesDir, "profile-0.json").isFile)
        assertEquals(saved.summary, repo.currentCatalogue.summaryFor(saved.summary.id))
        assertTrue(saved.summary.selectable)
        assertEquals(VoiceProfileKind.MIXED, saved.summary.kind)
    }

    @Test
    fun saveAsNewAllocatesNewIdEvenWhenSourceIsCustom() {
        val repo = newRepo()
        val source = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Source") as VoiceProfileMutation.Saved

        val saved = repo.saveAsNew(
            tensors(),
            provenance(source.summary.id.value to 0.5, "builtin:F2" to 0.5),
            "Child",
        ) as VoiceProfileMutation.Saved

        assertFalse(saved.summary.id == source.summary.id)
        assertTrue(repo.currentCatalogue.custom.size == 2)
    }

    @Test
    fun editedDraftSavesAsEditedKind() {
        val repo = newRepo()
        val prov = provenance(
            "builtin:F1" to 1.0,
            operations = listOf(VoiceProfileOperationRecord(VoiceProfileOperationKind.INVERT)),
        )

        val saved = repo.saveAsNew(tensors(), prov, "Edited") as VoiceProfileMutation.Saved

        assertEquals(VoiceProfileKind.EDITED, saved.summary.kind)
    }

    @Test
    fun profileCountLimitIsEnforced() {
        val repo = newRepo()
        for (i in 0 until VoiceProfileLimits.MAX_CUSTOM_PROFILES) {
            val result = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "p$i")
            assertTrue("save $i should succeed", result is VoiceProfileMutation.Saved)
        }

        val overflow = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "overflow")

        val failed = overflow as VoiceProfileMutation.Failed
        assertTrue(failed.failure is VoiceProfileFailure.ProfileLimitReached)
        assertEquals(VoiceProfileLimits.MAX_CUSTOM_PROFILES, repo.currentCatalogue.custom.size)
    }

    @Test
    fun displayNameByteLimitIsEnforced() {
        val repo = newRepo()
        val exactly128 = "a".repeat(VoiceProfileLimits.MAX_DISPLAY_NAME_BYTES)
        val oneTooMany = "a".repeat(VoiceProfileLimits.MAX_DISPLAY_NAME_BYTES + 1)
        // 33 four-byte emoji = 132 UTF-8 bytes despite only 33 characters.
        val multibyteOverflow = "\uD83D\uDE00".repeat(33)

        assertTrue(repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), exactly128) is VoiceProfileMutation.Saved)
        assertThrows(IllegalArgumentException::class.java) {
            repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), oneTooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), multibyteOverflow)
        }
    }

    @Test
    fun aggregateStorageLimitIsEnforced() {
        val repo = newRepo(aggregateLimitBytes = 1024)

        val result = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "TooBig")

        val failed = result as VoiceProfileMutation.Failed
        assertTrue(failed.failure is VoiceProfileFailure.AggregateLimitReached)
        assertTrue(repo.currentCatalogue.custom.isEmpty())
    }

    @Test
    fun namesAreUniqueCaseInsensitivelyForSaveAndRename() {
        val repo = newRepo()
        repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "My Voice")
        val other = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Other") as VoiceProfileMutation.Saved

        val duplicateSave = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "mY vOiCe")
        assertTrue((duplicateSave as VoiceProfileMutation.Failed).failure is VoiceProfileFailure.DuplicateName)

        val duplicateRename = repo.rename(other.summary.id, "MY VOICE")
        assertTrue((duplicateRename as VoiceProfileMutation.Failed).failure is VoiceProfileFailure.DuplicateName)
    }

    @Test
    fun renamePreservesIdentityTensorsAndFilename() {
        val repo = newRepo()
        val saved = repo.saveAsNew(tensors(ttl = 0.75f), provenance("builtin:F1" to 1.0), "Original") as VoiceProfileMutation.Saved

        val renamed = repo.rename(saved.summary.id, "Renamed") as VoiceProfileMutation.Saved

        assertEquals(saved.summary.id, renamed.summary.id)
        assertEquals("Renamed", renamed.summary.displayName)
        assertTrue(File(profilesDir, "${saved.summary.id.value}.json").isFile)
        assertEquals(0.75f, repo.loadTensors(saved.summary.id)!!.ttl[0], 0.0f)
        assertEquals("Renamed", repo.currentCatalogue.summaryFor(saved.summary.id)!!.displayName)
    }

    @Test
    fun assignedProfileDeletionIsRefusedWithDependentChannels() {
        val target = VoiceProfileId("assigned")
        val repo = newRepo(
            newId = { "assigned" },
            assignedChannels = { if (it == target) listOf(
                VoiceProfileChannelDependency("chan-1", "Channel One"),
                VoiceProfileChannelDependency("chan-2", "Channel Two"),
            ) else emptyList() },
        )
        repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Locked")

        val result = repo.delete(target)

        val failed = result as VoiceProfileMutation.Failed
        val assigned = failed.failure as VoiceProfileFailure.ProfileAssigned
        assertEquals(
            listOf(
                VoiceProfileChannelDependency("chan-1", "Channel One"),
                VoiceProfileChannelDependency("chan-2", "Channel Two"),
            ),
            assigned.dependentChannels,
        )
        assertTrue(assigned.diagnostic.contains("Channel One"))
        assertTrue(assigned.diagnostic.contains("Channel Two"))
        assertNotNull(repo.currentCatalogue.summaryFor(target))
    }

    @Test
    fun unassignedProfileDeletionSucceedsAndRemovesDocument() {
        val repo = newRepo()
        val saved = repo.saveAsNew(tensors(), provenance("builtin:F1" to 1.0), "Doomed") as VoiceProfileMutation.Saved

        val result = repo.delete(saved.summary.id)

        assertTrue(result is VoiceProfileMutation.Deleted)
        assertNull(repo.currentCatalogue.summaryFor(saved.summary.id))
        assertFalse(File(profilesDir, "${saved.summary.id.value}.json").exists())
    }

    @Test
    fun descendantSurvivesSourceDeletionAndRetainsProvenance() {
        val store = makeStore()
        val repo = repoFor(store)
        val sourceA = repo.saveAsNew(tensors(ttl = 1f), provenance("builtin:F1" to 1.0), "A") as VoiceProfileMutation.Saved
        repo.saveAsNew(tensors(ttl = 2f), provenance("builtin:F2" to 1.0), "B")
        val descendant = repo.saveAsNew(
            tensors(ttl = 1.5f),
            provenance(sourceA.summary.id.value to 0.5, "profile-1" to 0.5),
            "C",
        ) as VoiceProfileMutation.Saved

        val deletion = repo.delete(sourceA.summary.id)

        assertTrue(deletion is VoiceProfileMutation.Deleted)
        val surviving = repo.currentCatalogue.summaryFor(descendant.summary.id)
        assertNotNull(surviving)
        assertTrue(surviving!!.selectable)
        assertNotNull(repo.loadTensors(descendant.summary.id))
        val retained = store.readProfileDocument(descendant.summary.id)!!.provenance!!
        assertTrue(retained.sources.any { it.id == sourceA.summary.id })
    }

    // ── 2.6 Import / export ──────────────────────────────────────────────────

    @Test
    fun immediateSourceProvenanceSurvivesImport() {
        val store = makeStore()
        val repo = repoFor(store)
        val prov = provenance(
            "builtin:F1" to 0.5,
            "builtin:M1" to 0.5,
            weightMode = VoiceProfileWeightMode.RANDOM,
            randomSeed = 4242L,
        )
        val doc = VoiceProfileCodec.encode(tensors(), MODEL, prov)

        val saved = repo.importFromStream(ByteArrayInputStream(doc.toByteArray()), "Provenanced")
            as VoiceProfileMutation.Saved

        val retainedProvenance = store.readProfileDocument(saved.summary.id)!!.provenance!!
        assertEquals(prov, retainedProvenance)
        assertEquals(4242L, retainedProvenance.randomSeed)
    }

    @Test
    fun importDocumentSizeLimitIsEnforcedBeforeParsing() {
        val repo = newRepo()
        val oversized = ByteArrayInputStream(ByteArray(VoiceProfileLimits.MAX_IMPORT_DOCUMENT_BYTES + 1))

        val result = repo.importFromStream(oversized, "Huge")

        val failed = result as VoiceProfileMutation.Failed
        assertTrue(failed.failure is VoiceProfileFailure.ImportTooLarge)
        assertTrue(repo.currentCatalogue.custom.isEmpty())
    }

    @Test
    fun flatImportIsRejectedAsCorrupt() {
        val repo = newRepo()
        val flat = JSONArray()
        for (i in 0 until Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) flat.put(0.0)
        val doc = JSONObject()
            .put("style_ttl", JSONObject().put("dims", JSONArray(listOf(1, 50, 256))).put("data", flat))
            .put("style_dp", nestedComponent(1, 8, 16))

        val result = repo.importFromStream(ByteArrayInputStream(doc.toString().toByteArray()), "Flat")

        assertTrue((result as VoiceProfileMutation.Failed).failure is VoiceProfileFailure.CorruptDocument)
    }

    @Test
    fun untaggedImportIsStoredUnverifiedButSelectable() {
        val repo = newRepo()
        val doc = VoiceProfileCodec.encode(tensors(), null, null)

        val saved = repo.importFromStream(ByteArrayInputStream(doc.toByteArray()), "Mystery") as VoiceProfileMutation.Saved

        assertEquals(VoiceProfileCompatibility.UNVERIFIED, saved.summary.compatibility)
        assertEquals(VoiceProfileKind.IMPORTED, saved.summary.kind)
        assertTrue(saved.summary.selectable)
    }

    @Test
    fun taggedMatchingImportIsStoredVerified() {
        val repo = newRepo()
        val doc = VoiceProfileCodec.encode(tensors(), MODEL, null)

        val saved = repo.importFromStream(ByteArrayInputStream(doc.toByteArray()), "Known") as VoiceProfileMutation.Saved

        assertEquals(VoiceProfileCompatibility.VERIFIED, saved.summary.compatibility)
    }

    @Test
    fun declaredModelMismatchIsRejected() {
        val repo = newRepo()
        val foreign = VoiceProfileModelMetadata("supertonic-2", "2020-01-01")
        val doc = VoiceProfileCodec.encode(tensors(), foreign, null)

        val result = repo.importFromStream(ByteArrayInputStream(doc.toByteArray()), "Foreign")

        val failed = result as VoiceProfileMutation.Failed
        assertTrue(failed.failure is VoiceProfileFailure.IncompatibleModel)
        assertTrue(repo.currentCatalogue.custom.isEmpty())
    }

    @Test
    fun exportIsCanonicalNestedAndReimportable() {
        val repo = newRepo()
        val saved = repo.saveAsNew(tensors(ttl = 0.3f), provenance("builtin:F1" to 1.0), "Exportable")
            as VoiceProfileMutation.Saved
        val out = ByteArrayOutputStream()

        val exported = repo.exportToStream(saved.summary.id, out)

        assertTrue(exported is VoiceProfileMutation.Saved)
        val style = SupertonicVoiceStyle.parse(out.toString("UTF-8"))
        assertEquals(50, style.ttlDims.dim1)
        assertEquals(256, style.ttlDims.dim2)

        val reimported = repo.importFromStream(ByteArrayInputStream(out.toByteArray()), "Reimported")
        assertTrue(reimported is VoiceProfileMutation.Saved)
        assertEquals(
            VoiceProfileCompatibility.VERIFIED,
            (reimported as VoiceProfileMutation.Saved).summary.compatibility,
        )
    }

    @Test
    fun builtInProfileExportsAsCanonicalDocument() {
        val repo = newRepo()
        val out = ByteArrayOutputStream()

        val exported = repo.exportToStream(VoiceProfileId("builtin:M1"), out)

        assertTrue(exported is VoiceProfileMutation.Saved)
        val style = SupertonicVoiceStyle.parse(out.toString("UTF-8"))
        assertEquals(8, style.dpDims.dim1)
        assertEquals(16, style.dpDims.dim2)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun makeStore(injector: VoiceStoreFaultInjector = VoiceStoreFaultInjector.NONE): VoiceProfileStore =
        VoiceProfileStore(profilesDir, injector)

    private fun repoFor(
        store: VoiceProfileStore,
        aggregateLimitBytes: Long = VoiceProfileLimits.MAX_AGGREGATE_STORAGE_BYTES,
        assignedChannels: (VoiceProfileId) -> List<VoiceProfileChannelDependency> = { emptyList() },
        newId: () -> String = { "profile-${idCounter++}" },
    ): VoiceProfileRepository =
        VoiceProfileRepository(store, builtInDir, MODEL, newId, assignedChannels, aggregateLimitBytes)

    private fun newRepo(
        aggregateLimitBytes: Long = VoiceProfileLimits.MAX_AGGREGATE_STORAGE_BYTES,
        assignedChannels: (VoiceProfileId) -> List<VoiceProfileChannelDependency> = { emptyList() },
        newId: () -> String = { "profile-${idCounter++}" },
        injector: VoiceStoreFaultInjector = VoiceStoreFaultInjector.NONE,
    ): VoiceProfileRepository = repoFor(makeStore(injector), aggregateLimitBytes, assignedChannels, newId)

    private fun failingAt(boundary: VoiceStoreBoundary): VoiceStoreFaultInjector =
        VoiceStoreFaultInjector { if (it == boundary) throw java.io.IOException("injected failure at $boundary") }

    private fun tensors(ttl: Float = 0.5f, dp: Float = 0.25f): VoiceProfileTensors =
        VoiceProfileTensors(
            ttl = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) { ttl },
            ),
            dp = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.DP_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.DP_ELEMENT_COUNT) { dp },
            ),
        )

    private fun provenance(
        vararg sources: Pair<String, Double>,
        weightMode: VoiceProfileWeightMode = VoiceProfileWeightMode.MANUAL,
        randomSeed: Long? = null,
        operations: List<VoiceProfileOperationRecord> = emptyList(),
    ): VoiceProfileProvenance =
        VoiceProfileProvenance(
            sources = sources.map { (id, weight) ->
                VoiceProfileSourceProvenance(VoiceProfileId(id), "Display $id", weight)
            },
            weightMode = weightMode,
            randomSeed = randomSeed,
            operations = operations,
        )

    private fun nestedComponent(batch: Int, rows: Int, columns: Int): JSONObject {
        val data = JSONArray()
        for (b in 0 until batch) {
            val rowArray = JSONArray()
            for (r in 0 until rows) {
                val columnArray = JSONArray()
                for (c in 0 until columns) columnArray.put(0.0)
                rowArray.put(columnArray)
            }
            data.put(rowArray)
        }
        return JSONObject().put("dims", JSONArray(listOf(batch, rows, columns))).put("data", data)
    }

    companion object {
        private val MODEL = VoiceProfileModelMetadata(VoiceProfileCodec.MODEL_FAMILY, "supertonic-3-2026-06-24")
    }
}
