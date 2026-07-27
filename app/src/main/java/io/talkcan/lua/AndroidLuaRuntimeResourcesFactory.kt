package io.talkcan.lua

import android.content.ContentResolver
import android.net.Uri
import io.talkcan.audiofile.AudioFileAdapter
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.vfs.AndroidSafDocumentGateway
import io.talkcan.mount.saf.vfs.SafMountLeaseRevalidator
import io.talkcan.mount.saf.vfs.SafVfsMountFactory
import io.talkcan.mount.saf.vfs.SafVfsMountResolver
import io.talkcan.resource.MountBindingState
import io.talkcan.resource.MountBindingStore
import io.talkcan.storage.LeaseOwner
import io.talkcan.storage.MountLeaseRegistry
import io.talkcan.storage.MountedFilesystem
import java.util.UUID

/** Production composition for one installed Lua runtime generation. */
internal class AndroidLuaRuntimeResourcesFactory(
    private val contentResolver: ContentResolver,
    private val bindings: MountBindingStore,
    private val grants: SafGrantController,
) : LuaRuntimeResourcesFactory {
    override fun create(
        request: io.talkcan.model.ChannelRuntimeConstructionRequest,
        declarations: PackageResourcesDeclaration,
    ): LuaRuntimeResources {
        val identity = request.capabilities.identity
        val implementationId = request.definition.implementationId
        val instanceId = request.definition.id
        val generation = identity.runtimeGeneration.value
        val mountFactory = SafVfsMountFactory(grants) { treeUri ->
            AndroidSafDocumentGateway(contentResolver, Uri.parse(treeUri))
        }
        val resolver = SafVfsMountResolver(
            store = bindings,
            factory = mountFactory,
            channelInstanceId = instanceId,
            implementationId = implementationId,
            generationProvider = { generation },
        )
        val leases = MountLeaseRegistry(
            owner = LeaseOwner(
                stateId = UUID.randomUUID().toString(),
                instanceId = instanceId,
                generation = generation,
            ),
            resolver = resolver,
            revalidator = SafMountLeaseRevalidator(
                store = bindings,
                grants = grants,
                implementationId = implementationId,
            ),
        )
        val filesystem = MountedFilesystem(leases)
        val audioFiles = LuaAudioFilePortFactory { recordings ->
            AudioFileAdapter(
                leases = leases,
                recordings = recordings,
            )
        }
        val readiness = LuaMountReadinessStatus { declarationId ->
            val declared = declarations.mounts.any { it.id == declarationId }
            val binding = if (declared) {
                bindings.currentBinding(instanceId, implementationId, declarationId)
            } else {
                null
            }
            if (binding?.state == MountBindingState.ACTIVE) {
                binding.status.portable
            } else {
                "unavailable"
            }
        }
        return LuaRuntimeResources(
            storagePort = filesystem,
            audioFilePortFactory = audioFiles,
            mountReadinessStatus = readiness,
            close = {
                filesystem.close()
            },
        )
    }
}
