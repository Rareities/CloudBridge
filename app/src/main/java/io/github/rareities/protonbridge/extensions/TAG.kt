package io.github.rareities.protonbridge.extensions

fun Any.tag(): String { return this::class.java.simpleName }

// fragments do have a tag() function. Use TAG() instead.
fun Any.TAG(): String { return this::class.java.simpleName }