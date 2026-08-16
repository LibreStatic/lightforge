package com.ugallery.core.ml

data class BundledModel(
    val id: String,
    val version: String,
    val assetPath: String,
    val sha256: String,
    val licenseSpdx: String,
    val purpose: String,
)

/** Release models must be immutable, bundled, licensed, and hash-verified. */
class ModelRegistry(models: List<BundledModel>) {
    val models = models.associateBy(BundledModel::id)

    init {
        require(this.models.size == models.size) { "Model IDs must be unique" }
        models.forEach {
            require(it.sha256.matches(Regex("[a-f0-9]{64}")))
            require(it.assetPath.startsWith("models/"))
        }
    }
}

