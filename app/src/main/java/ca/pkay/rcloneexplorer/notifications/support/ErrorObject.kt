package ca.pkay.rcloneexplorer.notifications.support

import ca.pkay.rcloneexplorer.util.StructuredDiagnosticPolicy

class ErrorObject(rawErrorObject: String?, rawErrorMessage: String?) {
    val mErrorObject: String
    val mErrorMessage: String

    init {
        val safe = StructuredDiagnosticPolicy.sanitize(rawErrorObject, rawErrorMessage)
        mErrorObject = safe.objectName
        mErrorMessage = safe.message
    }
}
