package io.deepmedia.tools.grease

import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

internal class Logger(private val project: Project, private val tag: String) {

    fun log(level: LogLevel, message: () -> String?) {
        message.invoke()?.let {
            project.logger.log(level, "$tag: $it")
        }
    }

    fun i(message: () -> String?) = log(LogLevel.INFO, message)
    fun d(message: () -> String?) = log(LogLevel.DEBUG, message)
    fun w(message: () -> String?) = log(LogLevel.WARN, message)
    fun e(message: () -> String?) = log(LogLevel.ERROR, message)

    fun child(tag: String) = Logger(project, "${this.tag} > $tag")
}