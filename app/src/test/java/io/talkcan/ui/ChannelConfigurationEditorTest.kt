package io.talkcan.ui

import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.DynamicConfigurationChoiceSourceRegistry
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.DynamicConfigurationReferenceState
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.referenceState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigurationEditorTest {

    private val hostProfileField = ChannelConfigurationField.DynamicChoiceField(
        id = "host_profile",
        label = "Host profile",
        source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
    )
    private val fields = listOf<ChannelConfigurationField>(hostProfileField)
    private val platformField = ChannelConfigurationField.DynamicChoiceField(
        id = "host_os",
        label = "Host operating system",
        source = DynamicConfigurationChoiceSourceId("keyboard-output-platforms"),
    )
    private val layoutField = ChannelConfigurationField.DynamicChoiceField(
        id = "host_layout",
        label = "Host layout",
        source = DynamicConfigurationChoiceSourceId("keyboard-output-layouts"),
        dependsOnFieldId = "host_os",
    )
    private val chainedProfileField = ChannelConfigurationField.DynamicChoiceField(
        id = "host_profile",
        label = "Host profile",
        source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
        dependsOnFieldId = "host_layout",
    )
    private val chainFields = listOf<ChannelConfigurationField>(platformField, layoutField, chainedProfileField)

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    @Test
    fun `available source presents bounded choices and the selected label`() {
        val choices = listOf(
            DynamicConfigurationChoice("linux:us", "us [linux]"),
            DynamicConfigurationChoice("windows:us", "us [windows]"),
        )

        val presentation = dynamicChoicePresentation(
            hostProfileField.label,
            DynamicConfigurationChoiceResolution.Available(choices),
            selectedValue = "linux:us",
            dependencyValue = null,
        )

        assertEquals(choices, presentation.choices)
        assertEquals("us [linux]", presentation.selectedLabel)
        assertTrue(presentation.selectionEnabled)
        assertNull(presentation.statusText)
        assertFalse(presentation.statusIsError)
    }

    @Test
    fun `empty source enables selection without choices and preserves the scalar label`() {
        val presentation = dynamicChoicePresentation(
            hostProfileField.label,
            DynamicConfigurationChoiceResolution.Available(emptyList()),
            selectedValue = "linux:us",
            dependencyValue = null,
        )

        assertTrue(presentation.choices.isEmpty())
        assertTrue(presentation.selectionEnabled)
        assertEquals("Unavailable: linux:us", presentation.selectedLabel)
    }

    @Test
    fun `missing failed timed out duplicate and malformed registry sources render bounded unavailable editor state`() = runTest {
        val registry = DynamicConfigurationChoiceSourceRegistry()
        val failed = DynamicConfigurationChoiceSourceId("probe-failed")
        val slow = DynamicConfigurationChoiceSourceId("probe-slow")
        val duplicate = DynamicConfigurationChoiceSourceId("probe-duplicate")
        val malformed = DynamicConfigurationChoiceSourceId("probe-malformed")
        registry.register(failed, 5.seconds) { _ -> error("backend failure") }
        registry.register(slow, 50.milliseconds) { _ ->
            delay(10_000) // virtual time; the source deadline fires first
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "us [linux]")),
            )
        }
        registry.register(duplicate, 5.seconds) { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us [linux]"),
                    DynamicConfigurationChoice("linux:us", "us [linux] copy"),
                ),
            )
        }
        registry.register(malformed, 5.seconds) { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "a".repeat(129))),
            )
        }

        assertBoundedUnavailable(
            editorState(registry, DynamicConfigurationChoiceSourceId("probe-missing")),
            "Choices are currently unavailable.",
        )
        assertBoundedUnavailable(
            editorState(registry, failed),
            "Could not load choices. Try again.",
        )
        assertBoundedUnavailable(
            editorState(registry, slow),
            "Loading choices timed out. Try again.",
        )
        assertBoundedUnavailable(
            editorState(registry, duplicate),
            "Choices are currently unavailable.",
        )
        assertBoundedUnavailable(
            editorState(registry, malformed),
            "Choices are currently unavailable.",
        )
    }

    @Test
    fun `dependency unavailable message reflects the missing dependency`() {
        val presentation = dynamicChoicePresentation(
            "Model",
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
            ),
            selectedValue = null,
            dependencyValue = null,
        )

        assertEquals("Choose the required dependency first.", presentation.statusText)
        assertTrue(presentation.statusIsError)
        assertFalse(presentation.selectionEnabled)
    }

    @Test
    fun `stale persisted scalar is preserved unchanged through edit submission and restart reparse`() {
        val stored = opaque("""{"host_profile":"linux:de","vendor_extension":{"keep":true}}""")

        val initial = initialFieldValue(hostProfileField, stored.toJsonObject())
        assertEquals("linux:de", initial)
        val stalePresentation = dynamicChoicePresentation(
            hostProfileField.label,
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "us [linux]")),
            ),
            selectedValue = initial,
            dependencyValue = null,
        )
        assertEquals("Unavailable: linux:de", stalePresentation.selectedLabel)

        // Submitting without touching the field keeps the scalar and unknown keys verbatim.
        val submitted = payloadWithFieldValues(stored, fields, mapOf("host_profile" to initial))
        assertEquals("linux:de", submitted.toJsonObject().getString("host_profile"))
        assertTrue(submitted.toJsonObject().getJSONObject("vendor_extension").getBoolean("keep"))

        // Restart reparse recovers the identical scalar; the detached reference state follows
        // source availability without retaining any host profile object.
        val restored = OpaqueJsonObject.parse(submitted.toJsonString()).getOrThrow()
        val restoredScalar = initialFieldValue(hostProfileField, restored.toJsonObject())
        assertEquals("linux:de", restoredScalar)
        assertEquals(
            DynamicConfigurationReferenceState.UNAVAILABLE,
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "us [linux]")),
            ).referenceState(restoredScalar),
        )
        assertEquals(
            DynamicConfigurationReferenceState.AVAILABLE,
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:de", "de [linux]")),
            ).referenceState(restoredScalar),
        )
    }

    @Test
    fun `independent instances keep scalar profiles separate and payloads free of host objects`() {
        val instanceA = opaque("""{"host_profile":"linux:us"}""")
        val instanceB = opaque("""{"host_profile":"linux:de"}""")

        val editedA = payloadWithFieldValues(instanceA, fields, mapOf("host_profile" to "windows:us"))

        assertEquals("windows:us", editedA.toJsonObject().getString("host_profile"))
        assertEquals("linux:us", instanceA.toJsonObject().getString("host_profile"))
        assertEquals("linux:de", instanceB.toJsonObject().getString("host_profile"))

        // Only stable scalar IDs are persisted; no resolver, profile, or keymap object survives.
        listOf(instanceA, instanceB, editedA).forEach { payload ->
            val json = payload.toJsonObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                assertTrue(json.get(keys.next()) is String)
            }
        }
    }

    @Test
    fun `three stage chain resolves each child with the currently persisted parent scalar`() = runTest {
        val platformDependencies = mutableListOf<String?>()
        val layoutDependencies = mutableListOf<String?>()
        val profileDependencies = mutableListOf<String?>()
        val registry = DynamicConfigurationChoiceSourceRegistry()
        registry.register(platformField.source, 5.seconds) { request ->
            platformDependencies += request.dependencyValue
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux", "Linux"),
                    DynamicConfigurationChoice("windows", "Windows"),
                ),
            )
        }
        registry.register(layoutField.source, 5.seconds) { request ->
            layoutDependencies += request.dependencyValue
            when (request.dependencyValue) {
                "linux" -> DynamicConfigurationChoiceResolution.Available(
                    listOf(
                        DynamicConfigurationChoice("linux:us", "us [linux]"),
                        DynamicConfigurationChoice("linux:de", "de [linux]"),
                    ),
                )
                "windows" -> DynamicConfigurationChoiceResolution.Available(
                    listOf(
                        DynamicConfigurationChoice("windows:us", "us [windows]"),
                        DynamicConfigurationChoice("windows:de", "de [windows]"),
                    ),
                )
                else -> DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                )
            }
        }
        registry.register(chainedProfileField.source, 5.seconds) { request ->
            profileDependencies += request.dependencyValue
            when (request.dependencyValue) {
                "linux:us" -> DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("linux:us:default", "Default [us/linux]")),
                )
                "windows:us" -> DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("windows:us:default", "Default [us/windows]")),
                )
                else -> DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                )
            }
        }

        val stored = opaque("""{"host_os":"linux","host_layout":"linux:us","host_profile":"linux:us:default"}""")
        val values = editorValues(stored, chainFields).toMutableMap()

        // The root stage carries no dependency.
        val platforms = registry.resolve(editorChoiceRequest(platformField, values))
        assertEquals(listOf<String?>(null), platformDependencies)
        assertEquals(2, (platforms as DynamicConfigurationChoiceResolution.Available).choices.size)

        // Each child receives the currently persisted scalar of its declared parent only.
        val layouts = registry.resolve(editorChoiceRequest(layoutField, values))
        assertEquals(listOf("linux"), layoutDependencies)
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us [linux]"),
                    DynamicConfigurationChoice("linux:de", "de [linux]"),
                ),
            ),
            layouts,
        )
        val profiles = registry.resolve(editorChoiceRequest(chainedProfileField, values))
        assertEquals(listOf("linux:us"), profileDependencies)
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us:default", "Default [us/linux]")),
            ),
            profiles,
        )

        // Editing the platform redirects the layout request to the current scalar while the
        // profile request still carries the untouched layout scalar.
        values["host_os"] = "windows"
        registry.resolve(editorChoiceRequest(layoutField, values))
        assertEquals(listOf("linux", "windows"), layoutDependencies)
        registry.resolve(editorChoiceRequest(chainedProfileField, values))
        assertEquals(listOf("linux:us", "linux:us"), profileDependencies)
    }

    @Test
    fun `explicit parent edit clears every transitive descendant and preserves unrelated values`() {
        val unrelatedField = ChannelConfigurationField.TextField(id = "system_prompt", label = "System prompt")
        val editorFields = chainFields + unrelatedField
        val stored = opaque(
            """{"host_os":"linux","host_layout":"linux:us","host_profile":"linux:us:default","system_prompt":"keep me","vendor_extension":{"keep":true}}""",
        )
        val values = editorValues(stored, editorFields).toMutableMap()

        // The user explicitly repicks the platform parent: both chained descendants are cleared,
        // while the unrelated text scalar and unknown payload keys survive untouched.
        applyExplicitFieldEdit(editorFields, values, "host_os", "windows")
        assertEquals("windows", values["host_os"])
        assertNull(values["host_layout"])
        assertNull(values["host_profile"])
        assertEquals("keep me", values["system_prompt"])

        // The immediate child re-renders an empty "Select ..." button rather than a stale scalar.
        val windowsLayouts = DynamicConfigurationChoiceResolution.Available(
            listOf(
                DynamicConfigurationChoice("windows:us", "us [windows]"),
                DynamicConfigurationChoice("windows:de", "de [windows]"),
            ),
        )
        val clearedLayout = dynamicChoicePresentation(
            layoutField.label,
            windowsLayouts,
            selectedValue = values["host_layout"],
            dependencyValue = values["host_os"],
        )
        assertNull(clearedLayout.selectedLabel)
        assertTrue(clearedLayout.selectionEnabled)
        assertNull(clearedLayout.statusText)

        // The grandchild lost its dependency and falls back to typed guidance, not "Unavailable: <id>".
        val clearedProfile = dynamicChoicePresentation(
            chainedProfileField.label,
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
            ),
            selectedValue = values["host_profile"],
            dependencyValue = values["host_layout"],
        )
        assertNull(clearedProfile.selectedLabel)
        assertEquals("Choose the required dependency first.", clearedProfile.statusText)

        // Submission drops the cleared descendants to null while keeping the parent and unrelated keys.
        val submitted = payloadWithFieldValues(stored, editorFields, values).toJsonObject()
        assertEquals("windows", submitted.getString("host_os"))
        assertTrue(submitted.isNull("host_layout"))
        assertTrue(submitted.isNull("host_profile"))
        assertEquals("keep me", submitted.getString("system_prompt"))
        assertTrue(submitted.getJSONObject("vendor_extension").getBoolean("keep"))
    }

    @Test
    fun `explicit middle edit clears only the final descendant and preserves the parent`() {
        val stored = opaque(
            """{"host_os":"linux","host_layout":"linux:us","host_profile":"linux:us:default"}""",
        )
        val values = editorValues(stored, chainFields).toMutableMap()

        applyExplicitFieldEdit(chainFields, values, "host_layout", "windows:us")
        assertEquals("linux", values["host_os"])
        assertEquals("windows:us", values["host_layout"])
        assertNull(values["host_profile"])
    }

    @Test
    fun `passive loading and unavailability projections never mutate editor values`() {
        val stored = opaque(
            """{"host_os":"linux","host_layout":"linux:us","host_profile":"linux:us:default"}""",
        )
        val values = editorValues(stored, chainFields).toMutableMap()
        val snapshot = HashMap(values)

        chainFields.filterIsInstance<ChannelConfigurationField.DynamicChoiceField>().forEach { field ->
            dynamicChoicePresentation(
                field.label,
                DynamicConfigurationChoiceResolution.Loading,
                selectedValue = values[field.id],
                dependencyValue = field.dependsOnFieldId?.let(values::get),
            )
            dynamicChoicePresentation(
                field.label,
                DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
                ),
                selectedValue = values[field.id],
                dependencyValue = field.dependsOnFieldId?.let(values::get),
            )
        }

        assertEquals(snapshot, values)
    }

    @Test
    fun `missing parent dependency disables child selection with typed guidance`() = runTest {
        val registry = DynamicConfigurationChoiceSourceRegistry()
        registry.register(layoutField.source, 5.seconds) { request ->
            when (request.dependencyValue) {
                null -> DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                )
                else -> DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("${request.dependencyValue}:us", "us")),
                )
            }
        }
        registry.register(chainedProfileField.source, 5.seconds) { request ->
            when (request.dependencyValue) {
                "windows:us" -> DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("windows:us:default", "Default [us/windows]")),
                )
                else -> DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                )
            }
        }

        // No platform persisted: the layout request carries a null dependency.
        val emptyValues = editorValues(opaque("{}"), chainFields)
        val layoutPresentation = editorPresentation(
            layoutField,
            registry.resolve(editorChoiceRequest(layoutField, emptyValues)),
            emptyValues,
        )
        assertFalse(layoutPresentation.selectionEnabled)
        assertTrue(layoutPresentation.statusIsError)
        assertEquals("Choose the required dependency first.", layoutPresentation.statusText)
        assertNull(layoutPresentation.selectedLabel)
        assertTrue(layoutPresentation.choices.isEmpty())

        // Platform persisted but no layout: the profile request carries a null dependency.
        val platformOnly = editorValues(opaque("""{"host_os":"linux"}"""), chainFields)
        val profilePresentation = editorPresentation(
            chainedProfileField,
            registry.resolve(editorChoiceRequest(chainedProfileField, platformOnly)),
            platformOnly,
        )
        assertFalse(profilePresentation.selectionEnabled)
        assertEquals("Choose the required dependency first.", profilePresentation.statusText)

        // Layout persisted with a scalar the profile source no longer publishes: the typed
        // message distinguishes an unusable dependency from an absent one, and the stale
        // scalar remains displayed while selection stays disabled.
        val staleParent = editorValues(
            opaque("""{"host_os":"windows","host_layout":"linux:us","host_profile":"linux:us:default"}"""),
            chainFields,
        )
        val stalePresentation = editorPresentation(
            chainedProfileField,
            registry.resolve(editorChoiceRequest(chainedProfileField, staleParent)),
            staleParent,
        )
        assertFalse(stalePresentation.selectionEnabled)
        assertEquals("The selected dependency is unavailable.", stalePresentation.statusText)
        assertEquals("Unavailable: linux:us:default", stalePresentation.selectedLabel)
    }

    @Test
    fun `editor projection carries no source identity across the keyboard hierarchy`() {
        val fields = listOf(platformField, layoutField, chainedProfileField)
        assertEquals(3, fields.map { it.source.value }.toSet().size)

        val choices = listOf(DynamicConfigurationChoice("a", "A"), DynamicConfigurationChoice("b", "B"))
        val availableProjections = fields.map { field ->
            dynamicChoicePresentation(
                field.label,
                DynamicConfigurationChoiceResolution.Available(choices),
                selectedValue = "a",
                dependencyValue = "dependency",
            )
        }
        availableProjections.forEach { projection ->
            assertEquals(choices, projection.choices)
            assertEquals("A", projection.selectedLabel)
            assertTrue(projection.selectionEnabled)
            assertNull(projection.statusText)
            assertFalse(projection.statusIsError)
        }

        val unavailableProjection = dynamicChoicePresentation(
            "Reference",
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
            ),
            selectedValue = "a",
            dependencyValue = null,
        )
        fields.forEach { field ->
            assertEquals(
                unavailableProjection,
                dynamicChoicePresentation(
                    field.label,
                    DynamicConfigurationChoiceResolution.Unavailable(
                        DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                    ),
                    selectedValue = "a",
                    dependencyValue = null,
                ),
            )
        }
    }

    private suspend fun editorState(
        registry: DynamicConfigurationChoiceSourceRegistry,
        source: DynamicConfigurationChoiceSourceId,
    ): DynamicChoicePresentation {
        val resolution = registry.resolve(DynamicConfigurationChoiceRequest(source))
        return dynamicChoicePresentation(
            hostProfileField.label,
            resolution,
            selectedValue = "linux:de",
            dependencyValue = null,
        )
    }

    /** Mirrors ChannelConfigurationScreen: one scalar is seeded per persisted declared field. */
    private fun editorValues(payload: OpaqueJsonObject, fields: List<ChannelConfigurationField>): Map<String, String?> {
        val json = payload.toJsonObject()
        return fields.mapNotNull { field ->
            if (json.has(field.id)) field.id to initialFieldValue(field, json) else null
        }.toMap()
    }

    /** Mirrors ChannelConfigurationScreen: a child request carries only its parent scalar. */
    private fun editorChoiceRequest(
        field: ChannelConfigurationField.DynamicChoiceField,
        values: Map<String, String?>,
    ): DynamicConfigurationChoiceRequest =
        DynamicConfigurationChoiceRequest(field.source, field.dependsOnFieldId?.let(values::get))

    /** Mirrors ChannelConfigurationScreen: the projection reads selection and dependency from editor state. */
    private fun editorPresentation(
        field: ChannelConfigurationField.DynamicChoiceField,
        resolution: DynamicConfigurationChoiceResolution,
        values: Map<String, String?>,
    ): DynamicChoicePresentation = dynamicChoicePresentation(
        field.label,
        resolution,
        selectedValue = values[field.id],
        dependencyValue = field.dependsOnFieldId?.let(values::get),
    )

    private fun assertBoundedUnavailable(presentation: DynamicChoicePresentation, expectedMessage: String) {
        assertTrue(presentation.choices.isEmpty())
        assertFalse(presentation.selectionEnabled)
        assertTrue(presentation.statusIsError)
        assertEquals(expectedMessage, presentation.statusText)
        // The persisted scalar remains displayed and submittable while the source is unusable.
        assertEquals("Unavailable: linux:de", presentation.selectedLabel)
    }

    @Test
    fun `explicit upstream edit clears every transitive dynamic dependent draft scalar`() {
        val values: MutableMap<String, String?> = mutableMapOf(
            "host_os" to "linux",
            "host_layout" to "linux:us",
            "host_profile" to "linux:us",
        )

        applyExplicitFieldEdit(chainFields, values, "host_os", "windows")

        assertEquals("windows", values["host_os"])
        assertNull("layout depends on the edited platform and must clear", values["host_layout"])
        assertNull("profile transitively depends on the edited platform and must clear", values["host_profile"])
    }

    @Test
    fun `editor suppresses a late predecessor result and publishes only the active request result`() {
        val oldRequest = DynamicConfigurationChoiceRequest(hostProfileField.source, dependencyValue = "profile-1")
        val newRequest = DynamicConfigurationChoiceRequest(hostProfileField.source, dependencyValue = "profile-2")
        val staleChoices = DynamicConfigurationChoiceResolution.Available(
            listOf(DynamicConfigurationChoice("model-old", "Old model")),
        )
        val freshChoices = DynamicConfigurationChoiceResolution.Available(
            listOf(DynamicConfigurationChoice("model-new", "New model")),
        )

        val state = DynamicChoiceEditorState(
            activeRequest = null,
            resolution = DynamicConfigurationChoiceResolution.Loading,
        ).onRequestStarted(oldRequest).onRequestStarted(newRequest)

        val afterStale = state.onResultArrived(oldRequest, staleChoices)
        assertEquals(
            "a result for the predecessor dependency must not populate the current field",
            DynamicConfigurationChoiceResolution.Loading,
            afterStale.resolution,
        )

        assertEquals(freshChoices, afterStale.onResultArrived(newRequest, freshChoices).resolution)
    }
}
