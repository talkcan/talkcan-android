package io.talkcan.service

import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.OpaqueJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RsmAnnouncementTextResolverTest {
    @Test
    fun menuEntryResolvesFixedChannelsLiteralFromLiveCatalogue() {
        assertEquals(
            "Channels",
            resolveRsmAnnouncementText(
                "sys.menu.channels",
                catalogue(definition(id = "journal", name = "Journal")),
            ),
        )
    }

    @Test
    fun emptyCatalogueSuppressesEveryAnnouncementText() {
        val emptyCatalogue = ChannelCatalogueSnapshot(definitions = emptyList(), activeChannelId = "")

        listOf(
            "sys.menu.channels",
            "chan.journal.name",
            "chan.journal.selected",
        ).forEach { key ->
            assertNull(resolveRsmAnnouncementText(key, emptyCatalogue))
        }
    }

    @Test
    fun currentCataloguePreservesChannelNameSpecialCharactersForNameAndSelection() {
        val catalogue = catalogue(
            definition(
                id = "field-ops",
                name = "Núñez / “Café” #1 — 50% & <test>",
            ),
        )

        assertEquals(
            "Núñez / “Café” #1 — 50% & <test>",
            resolveRsmAnnouncementText("chan.field-ops.name", catalogue),
        )
        assertEquals(
            "Núñez / “Café” #1 — 50% & <test> Selected",
            resolveRsmAnnouncementText("chan.field-ops.selected", catalogue),
        )
    }

    @Test
    fun renamedChannelResolvesNameFromLatestCatalogueSnapshot() {
        val initialCatalogue = catalogue(definition(id = "journal", name = "Journal"))
        val renamedCatalogue = catalogue(definition(id = "journal", name = "Field Journal"))

        assertEquals("Journal", resolveRsmAnnouncementText("chan.journal.name", initialCatalogue))
        assertEquals("Field Journal", resolveRsmAnnouncementText("chan.journal.name", renamedCatalogue))
        assertEquals(
            "Field Journal Selected",
            resolveRsmAnnouncementText("chan.journal.selected", renamedCatalogue),
        )
    }

    @Test
    fun missingOrStaleChannelIdProducesNoAnnouncementText() {
        val beforeRemoval = catalogue(definition(id = "retired", name = "Retired Channel"))
        val currentCatalogue = catalogue(definition(id = "active", name = "Active Channel"))

        assertEquals(
            "Retired Channel",
            resolveRsmAnnouncementText("chan.retired.name", beforeRemoval),
        )
        assertNull(resolveRsmAnnouncementText("chan.retired.name", currentCatalogue))
        assertNull(resolveRsmAnnouncementText("chan.retired.selected", currentCatalogue))
        assertNull(resolveRsmAnnouncementText("chan.unknown.name", currentCatalogue))
    }

    private fun catalogue(vararg definitions: ChannelDefinition): ChannelCatalogueSnapshot =
        ChannelCatalogueSnapshot(
            definitions = definitions.toList(),
            activeChannelId = definitions.firstOrNull()?.id.orEmpty(),
        )

    private fun definition(id: String, name: String): ChannelDefinition =
        ChannelDefinition(
            id = id,
            name = name,
            implementationId = ChannelImplementationId("test:resolver"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
        )
}
