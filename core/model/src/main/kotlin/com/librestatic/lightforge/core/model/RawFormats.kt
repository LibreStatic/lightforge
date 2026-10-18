package com.librestatic.lightforge.core.model

/**
 * Single source of truth for camera RAW detection, shared by the decoder, the grid badge and the
 * library RAW filter so a format the developer can open is also badged and filtered as RAW.
 */
object RawFormats {
    val mimeTypes: Set<String> = setOf(
        "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef",
        "image/x-sony-arw", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2",
        "image/x-pentax-pef", "image/x-samsung-srw", "image/x-canon-crw", "image/x-epson-erf",
        "image/x-hasselblad-3fr", "image/x-kodak-dcr", "image/x-kodak-kdc", "image/x-leaf-mos",
        "image/x-leica-rwl", "image/x-minolta-mrw", "image/x-phaseone-iiq", "image/x-sigma-x3f",
    )

    /** Lowercase extensions without the dot; every entry matches `[a-z0-9]+`. */
    val extensions: Set<String> = setOf(
        "3fr", "ari", "arw", "bay", "bmq", "cap", "cine", "cr2", "cr3", "crw", "cs1", "dc2",
        "dcr", "dng", "drf", "eip", "erf", "fff", "iiq", "k25", "kdc", "mdc", "mef", "mos",
        "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d", "raf", "raw", "rdc",
        "rw2", "rwl", "rwz", "sr2", "srf", "srw", "x3f",
    )

    fun isRawExtension(displayName: String?): Boolean =
        displayName?.substringAfterLast('.', "")?.lowercase().orEmpty() in extensions

    fun isRaw(mimeType: String?, displayName: String?): Boolean {
        val mime = mimeType?.lowercase().orEmpty()
        return mime in mimeTypes || mime.contains("raw") || isRawExtension(displayName)
    }
}
