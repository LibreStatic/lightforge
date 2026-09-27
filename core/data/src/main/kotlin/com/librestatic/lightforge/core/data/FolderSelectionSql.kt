package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.preferences.FolderSelectionTarget

internal object FolderSelectionSql {
    fun predicate(
        alias: String,
        defaultSelected: Boolean,
        rules: Map<FolderSelectionTarget, Boolean>,
        args: MutableList<Any>,
    ): String? {
        if (rules.isEmpty()) return if (defaultSelected) null else "0"

        val buckets = rules.entries
            .filter { it.key is FolderSelectionTarget.Bucket }
            .sortedWith(compareBy({ it.key.volumeName }, { (it.key as FolderSelectionTarget.Bucket).bucketId }))
        val paths = rules.entries
            .filter { it.key is FolderSelectionTarget.Path }
            .sortedWith(
                compareByDescending<Map.Entry<FolderSelectionTarget, Boolean>> {
                    (it.key as FolderSelectionTarget.Path).relativePath.length
                }.thenBy { it.key.volumeName }
                    .thenBy { (it.key as FolderSelectionTarget.Path).relativePath },
            )
        val branches = buildList {
            buckets.forEach { (target, selected) ->
                target as FolderSelectionTarget.Bucket
                args += target.volumeName
                args += target.bucketId
                add("WHEN $alias.volumeName=? AND $alias.bucketId=? THEN ${selected.asSqlInt()}")
            }
            paths.forEach { (target, selected) ->
                target as FolderSelectionTarget.Path
                args += target.volumeName
                args += target.relativePath
                add(
                    "WHEN $alias.volumeName=? AND " +
                        "instr(COALESCE($alias.relativePath,''),?)=1 THEN ${selected.asSqlInt()}",
                )
            }
        }
        return "(CASE ${branches.joinToString(" ")} ELSE ${defaultSelected.asSqlInt()} END)=1"
    }

    private fun Boolean.asSqlInt() = if (this) 1 else 0
}
