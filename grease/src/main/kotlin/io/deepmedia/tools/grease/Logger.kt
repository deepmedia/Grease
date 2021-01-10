package io.deepmedia.tools.grease

import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

internal class Logger(private val project: Project, private val tag: String) {
    companion object {
        val INFO = LogLevel.INFO
        val WARNING = LogLevel.WARN
        val ERROR = LogLevel.ERROR
    }

    fun log(level: LogLevel, message: () -> String?) {
        message.invoke()?.let {
            if (level == INFO) {
                println("$tag: $it")
            } else {
                project.logger.log(level, "$tag: $it")
            }
        }
    }

    fun i(message: () -> String?) = log(INFO, message)
    fun w(message: () -> String?) = log(WARNING, message)
    fun e(message: () -> String?) = log(ERROR, message)

    fun child(tag: String) = Logger(project, "${this.tag} > $tag")
}