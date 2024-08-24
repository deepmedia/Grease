package io.deepmedia.tools.grease

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

/**
 * Locates a task by [name] and [type], without triggering its creation or configuration.
 */
internal fun <T : Task> Project.locateTask(name: String, type: Class<T>): TaskProvider<T>? = tasks.locateTask(name, type)

internal fun Project.locateTask(name: String): TaskProvider<Task>? = tasks.locateTask(name)

/**
 * Locates a task by [name] and [type], without triggering its creation or configuration.
 */
internal fun <T : Task> TaskContainer.locateTask(name: String, type: Class<T>): TaskProvider<T>? =
    if (names.contains(name)) named(name, type) else null

internal fun TaskContainer.locateTask(name: String): TaskProvider<Task>? =
    if (names.contains(name)) named(name) else null

/**
 * Locates a task by [name] and [type], without triggering its creation or configuration or registers new task
 * with [name], type [T] and initialization script [body]
 */
internal fun <T : Task> Project.locateOrRegisterTask(
    name: String,
    type: Class<T>,
    body: T.() -> (Unit)
): TaskProvider<T> {
    return project.locateTask(name, type) ?: project.registerTask(name, type, body = body)
}

internal fun <T : Task> TaskContainer.locateOrRegisterTask(
    name: String,
    type: Class<T>,
    body: T.() -> (Unit)
): TaskProvider<T> {
    return locateTask(name, type) ?: registerTask(name, type, body = body)
}

internal fun TaskContainer.locateOrRegisterTask(name: String, body: Task.() -> (Unit)): TaskProvider<Task> {
    return (locateTask(name, DefaultTask::class.java) ?: registerTask(name, DefaultTask::class.java, body = body)) as TaskProvider<Task>
}

internal fun <T : Task> Project.registerTask(
    name: String,
    type: Class<T>,
    constructorArgs: List<Any> = emptyList(),
    body: (T.() -> (Unit))? = null
): TaskProvider<T> {
    return project.tasks.registerTask(name, type, constructorArgs, body)
}

internal fun <T : Task> TaskContainer.registerTask(
    name: String,
    type: Class<T>,
    constructorArgs: List<Any> = emptyList(),
    body: (T.() -> (Unit))? = null
): TaskProvider<T> {
    val resultProvider = register(name, type, *constructorArgs.toTypedArray())
    if (body != null) {
        resultProvider.configure(body)
    }
    return resultProvider
}