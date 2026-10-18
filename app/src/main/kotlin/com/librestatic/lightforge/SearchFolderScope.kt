package com.librestatic.lightforge

/** A LIKE pattern (escape `\`) for "this folder or below it" over a relative path, escaping the wildcards in the name. */
internal fun likePrefixPattern(folderPath: String): String =
    folderPath.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
