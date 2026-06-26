package com.vonage.clientlibrary

/**
 * Marks declarations that are part of the Silent Auth Advanced (SAA) API.
 *
 * SAA is currently in alpha. The API surface is subject to change as carrier
 * adoption of the GSMA TS.43 standard matures. Code using this API must opt in
 * explicitly by annotating the call site with `@OptIn(ExperimentalSaaApi::class)`
 * or by propagating the annotation to the enclosing declaration.
 */
@RequiresOptIn(
    message = "The Silent Auth Advanced API is experimental and subject to change. " +
        "Opt in with @OptIn(ExperimentalSaaApi::class) to use it.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR
)
annotation class ExperimentalSaaApi
