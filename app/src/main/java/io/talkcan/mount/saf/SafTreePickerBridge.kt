package io.talkcan.mount.saf

import android.content.Intent
import io.talkcan.dependency.PackageMountDeclaration

/**
 * Minimal host composition for launching the SAF tree picker and parsing its
 * activity result into the generic [SafTreePickerOutcome].
 *
 * This is the only hook the generic editor needs: it owns the
 * `ActivityResultLauncher` (task 2.7), passes [launchIntent] to `launch`, and
 * feeds the returned data [Intent] to [outcomeFrom] before calling
 * [SafMountAdapter.completeSelection]. The legacy Journal raw-path picker in
 * `MainActivity` is deliberately untouched.
 */
class SafTreePickerBridge(
    private val intentFactory: AndroidSafTreeIntentFactory = AndroidSafTreeIntentFactory(),
) {

    /** Builds the exact system-picker intent for one declared mount (task 3.1). */
    fun launchIntent(declaration: PackageMountDeclaration): Intent {
        require(declaration.kind == io.talkcan.dependency.PackageMountKind.DIRECTORY_TREE) {
            "SAF selection supports only directory-tree mounts: ${declaration.kind.value}"
        }
        return intentFactory.toIntent(
            SafTreeSelection.intentSpec(SafRequestedAccess.from(declaration.access)),
        )
    }

    /** Normalizes an activity result; null/blank data means the user cancelled (task 3.5). */
    fun outcomeFrom(resultData: Intent?): SafTreePickerOutcome =
        SafTreePickerOutcome.fromActivityResult(resultData?.data?.toString())
}
