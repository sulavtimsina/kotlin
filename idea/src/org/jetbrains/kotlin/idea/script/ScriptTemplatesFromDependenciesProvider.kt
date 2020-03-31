/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.script

import com.intellij.ProjectTopics
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction.nonBlocking
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.StandardFileSystems.JAR_PROTOCOL
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionSourceAsContributor
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.jetbrains.kotlin.idea.core.script.loadDefinitionsFromTemplates
import org.jetbrains.kotlin.idea.util.ProgressIndicatorUtils
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.scripting.definitions.SCRIPT_DEFINITION_MARKERS_EXTENSION_WITH_DOT
import org.jetbrains.kotlin.scripting.definitions.SCRIPT_DEFINITION_MARKERS_PATH
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.getEnvironment
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

class ScriptTemplatesFromDependenciesProvider(private val project: Project) : ScriptDefinitionSourceAsContributor {
    override val id = "ScriptTemplatesFromDependenciesProvider"

    override fun isReady(): Boolean = _definitions != null

    override val definitions: Sequence<ScriptDefinition>
        get() {
            definitionsLock.read {
                if (_definitions != null) {
                    return _definitions!!.asSequence()
                }
            }

            forceStartUpdate = false
            asyncRunUpdateScriptTemplates()
            return emptySequence()
        }

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(
            ProjectTopics.PROJECT_ROOTS,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    if (project.isInitialized) {
                        forceStartUpdate = true
                        asyncRunUpdateScriptTemplates()
                    }
                }
            },
        )
    }

    private fun asyncRunUpdateScriptTemplates() {
        definitionsLock.read {
            if (!forceStartUpdate && _definitions != null) return
        }

        inProgressLock.write {
            if (!inProgress) {
                inProgress = true

                runBackgroundableTask("Kotlin: scanning dependencies for script definitions...", project, false) { indicator ->
                    indicator.isIndeterminate = true

                    loadScriptDefinitions(indicator)
                }
            }
        }
    }

    private var _definitions: List<ScriptDefinition>? = null
    private val definitionsLock = ReentrantReadWriteLock()

    private val visitedRoots = hashSetOf<String>()

    private var oldTemplates: TemplatesWithCp? = null

    private data class TemplatesWithCp(
        val templates: List<String>,
        val classpath: List<File>,
    )

    private var inProgress = false
    private val inProgressLock = ReentrantReadWriteLock()

    @Volatile
    private var forceStartUpdate = false

    @Volatile
    private var updateAborted = false

    private fun loadScriptDefinitions(indicator: ProgressIndicator) {
        if (ApplicationManager.getApplication().isUnitTestMode || project.isDefault) {
            val needReload = definitionsLock.write {
                val newDefinitions = emptyList<ScriptDefinition>()
                if (newDefinitions != _definitions) {
                    _definitions = newDefinitions
                    return@write true
                }
                return@write false
            }

            if (needReload) {
                ScriptDefinitionsManager.getInstance(project).reloadDefinitionsBy(this@ScriptTemplatesFromDependenciesProvider)
            }
        }

        val templates = LinkedHashSet<String>()
        val classpath = LinkedHashSet<File>()

        fun addTemplatesFromRoot(vfile: VirtualFile): Boolean {
            val root = when {
                vfile.isDirectory -> vfile
                vfile.extension == JAR_PROTOCOL -> JarFileSystem.getInstance().getJarRootForLocalFile(vfile)
                else -> return false
            }

            if (root == null || !root.isValid) return false

            val path = root.path
            if (!visitedRoots.add(path)) return false

            indicator.text2 = path

            var templatesFound = false
            root.findFileByRelativePath(SCRIPT_DEFINITION_MARKERS_PATH)?.children?.forEach {
                if (it.isValid && !it.isDirectory) {
                    templates.add(it.name.removeSuffix(SCRIPT_DEFINITION_MARKERS_EXTENSION_WITH_DOT))
                    templatesFound = true
                }
            }
            return templatesFound
        }

        ProgressIndicatorUtils.awaitWithCheckCanceled(
            nonBlocking<List<Module>> { return@nonBlocking project.allModules() }
                .inSmartMode(project)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess { allModules ->

                    allModules.map {
                        it to OrderEnumerator.orderEntries(it).withoutDepModules().withoutLibraries().withoutSdk().sourceRoots.toHashSet()
                    }.forEach { (module, sourceRoots) ->
                        if (indicator.isCanceled) {
                            updateAborted = true
                            return@onSuccess
                        }

                        sourceRoots.forEach { root ->
                            if (addTemplatesFromRoot(root)) {

                                // assuming that all libraries are placed into classes roots
                                // TODO: extract exact library dependencies instead of putting all module dependencies into classpath
                                // minimizing the classpath needed to use the template by taking cp only from modules with new templates found
                                // on the other hand the approach may fail if some module contains a template without proper classpath, while
                                // the other has properly configured classpath, so assuming that the dependencies are set correctly everywhere
                                classpath.addAll(
                                    OrderEnumerator.orderEntries(module).withoutSdk().classesRoots.mapNotNull {
                                        it.canonicalPath?.removeSuffix("!/").let(::File)
                                    },
                                )
                            }
                        }
                    }

                    allModules
                        .flatMap { OrderEnumerator.orderEntries(it).withoutSdk().classesRoots.toHashSet() }
                        .forEach { root ->
                            if (indicator.isCanceled) {
                                updateAborted = true
                                return@onSuccess
                            }

                            if (addTemplatesFromRoot(root)) {
                                classpath.add(root.canonicalPath?.removeSuffix("!/").let(::File))
                            }
                        }
                }
                .onProcessed {
                    inProgressLock.write {
                        inProgress = false
                    }
                },

            )

        if (updateAborted) return

        val newTemplates = TemplatesWithCp(templates.toList(), classpath.toList())
        if (newTemplates == oldTemplates) {
            return
        }

        val hostConfiguration = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            getEnvironment {
                mapOf(
                    "projectRoot" to (project.basePath ?: project.baseDir.canonicalPath)?.let(::File),
                )
            }
        }

        val newDefinitions = loadDefinitionsFromTemplates(
            templateClassNames = newTemplates.templates,
            templateClasspath = newTemplates.classpath,
            baseHostConfiguration = hostConfiguration,
        )

        val needReload = definitionsLock.write {
            if (newDefinitions != _definitions) {
                _definitions = newDefinitions
                return@write true
            }
            return@write false
        }

        if (needReload) {
            ScriptDefinitionsManager.getInstance(project).reloadDefinitionsBy(this@ScriptTemplatesFromDependenciesProvider)
        }
    }
}