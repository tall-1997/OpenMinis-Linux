package com.openminis.app.ui.chat

/** Host authorship stays in the transcript, not in the model's text payload. */
internal fun hostReminderPartsJson(quotedText: String): String =
    """[{"type":"text","value":$quotedText},{"type":"receiptOrigin","value":"host"}]"""
