/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jps.incremental

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.SmartList
import junit.framework.TestCase
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.LocalFileKotlinClass
import org.jetbrains.kotlin.incremental.ProtoCompareGenerated
import org.jetbrains.kotlin.incremental.difference
import org.jetbrains.kotlin.incremental.storage.ProtoMapValue
import org.jetbrains.kotlin.incremental.utils.TestMessageCollector
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.js.facade.K2JSTranslator
import org.jetbrains.kotlin.js.incremental.IncrementalJsService
import org.jetbrains.kotlin.js.incremental.IncrementalJsServiceImpl
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.NameResolverImpl
import org.jetbrains.kotlin.serialization.jvm.BitEncoding
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.MockLibraryUtil
import org.jetbrains.kotlin.utils.Printer
import org.junit.Assert
import java.io.File
import java.util.*

abstract class AbstractJsProtoComparisonTest : AbstractProtoComparisonTest() {
    override fun doTest(testDataPath: String) {
        val testDir = KotlinTestUtils.tmpDir("testDirectory")
        val oldClassFiles = compileFileAndGetClasses(testDataPath, testDir, "old")
        val newClassFiles = compileFileAndGetClasses(testDataPath, testDir, "new")
        val oldParts = oldClassFiles.packageParts

        val newParts = newClassFiles.packageParts

        val sb = StringBuilder()
        val p = Printer(sb)

        comparePackageParts(oldParts.first().proto, newParts.first().proto)
        val c = 0
    }

    fun comparePackageParts(old: ProtoBuf.PackageFragment, new: ProtoBuf.PackageFragment) {
        val oldNameResolver = NameResolverImpl(old.strings, old.qualifiedNames)
        val newNameResolver = NameResolverImpl(new.strings, new.qualifiedNames)
        val compare = ProtoCompareGenerated(oldNameResolver, newNameResolver)

        val packageDiff = compare.difference(old.`package`, old.`package`)

        val oldClasses = old.class_List.map { oldNameResolver.getClassId(it.fqName) to it }.toMap()
        val newClasses = new.class_List.map { newNameResolver.getClassId(it.fqName) to it }.toMap()

        val addedClasses = arrayListOf<ClassId>()
        val removedClasses = arrayListOf<ClassId>()
        val modifiedClasses = hashMapOf<ClassId, EnumSet<ProtoCompareGenerated.ProtoBufClassKind>>()

        for (classId in oldClasses.keys + newClasses.keys) {
            val oldProto = oldClasses[classId]
            val newProto = newClasses[classId]

            when {
                oldProto == null -> {
                    addedClasses.add(classId)
                }
                newProto == null -> {
                    removedClasses.add(classId)
                }
                else -> {
                    modifiedClasses[classId] = compare.difference(oldProto, newProto)
                }
            }
        }

        val c = 0
    }

    private fun compileFileAndGetClasses(testPath: String, testDir: File, prefix: String): IncrementalJsServiceImpl {
        val files = File(testPath).listFiles { it -> it.name.startsWith(prefix) }!!
        val sourcesDirectory = testDir.createSubDirectory("sources")
        val outputDir = testDir.createSubDirectory("out-$prefix")
        val sourcesToCompile = arrayListOf<String>()

        files.forEach { file ->
            val targetFile = File(sourcesDirectory, file.name.replaceFirst(prefix, "main"))
            FileUtil.copy(file, targetFile)
            sourcesToCompile.add(targetFile.canonicalPath)
        }

        val incrementalService = IncrementalJsServiceImpl()
        // todo: find out if it is safe to call directly
        val services = Services.Builder().run {
            register(IncrementalJsService::class.java, incrementalService)
            build()
        }

        val messageCollector = TestMessageCollector()
        val args = K2JSCompilerArguments().apply {
            outputFile = File(outputDir, "out.js").canonicalPath
            metaInfo = true
            freeArgs.addAll(sourcesToCompile)
        }
        val exitCode = K2JSCompiler().exec(messageCollector, services, args)
        val expectedOutput = "OK"
        val actualOutput = (listOf(exitCode.name) + messageCollector.errors).joinToString("\n")
        Assert.assertEquals(expectedOutput, actualOutput)
        return incrementalService
    }
}

abstract class AbstractProtoComparisonTest : UsefulTestCase() {
    open  fun doTest(testDataPath: String) {
        val testDir = KotlinTestUtils.tmpDir("testDirectory")

        val oldClassFiles = compileFileAndGetClasses(testDataPath, testDir, "old")
        val newClassFiles = compileFileAndGetClasses(testDataPath, testDir, "new")


        val oldClassMap = oldClassFiles.map { LocalFileKotlinClass.create(it)!!.let { it.classId to it } }.toMap()
        val newClassMap = newClassFiles.map { LocalFileKotlinClass.create(it)!!.let { it.classId to it } }.toMap()

        val sb = StringBuilder()
        val p = Printer(sb)

        val oldSetOfNames = oldClassFiles.map { it.name }.toSet()
        val newSetOfNames = newClassFiles.map { it.name }.toSet()

        val removedNames = (oldClassMap.keys - newClassMap.keys).map { it.toString() }.sorted()
        removedNames.forEach {
            p.println("REMOVED: class $it")
        }

        val addedNames = (newClassMap.keys - oldClassMap.keys).map { it.toString() }.sorted()
        addedNames.forEach {
            p.println("ADDED: class $it")
        }

        val commonNames = oldClassMap.keys.intersect(newClassMap.keys).sortedBy { it.toString() }

        for(name in commonNames) {
            p.printDifference(oldClassMap[name]!!, newClassMap[name]!!)
        }

        KotlinTestUtils.assertEqualsToFile(File(testDataPath + File.separator + "result.out"), sb.toString())
    }

    private fun compileFileAndGetClasses(testPath: String, testDir: File, prefix: String): List<File> {
        val files = File(testPath).listFiles { it -> it.name.startsWith(prefix) }!!
        val sourcesDirectory = testDir.createSubDirectory("sources")
        val classesDirectory = testDir.createSubDirectory("$prefix.src")

        files.forEach { file ->
            FileUtil.copy(file, File(sourcesDirectory, file.name.replaceFirst(prefix, "main")))
        }
        MockLibraryUtil.compileKotlin(sourcesDirectory.path, classesDirectory)

        return File(classesDirectory, "test").listFiles() { it -> it.name.endsWith(".class") }?.sortedBy { it.name }!!
    }

    private fun Printer.printDifference(oldClass: LocalFileKotlinClass, newClass: LocalFileKotlinClass) {
        fun KotlinJvmBinaryClass.readProto(): ProtoMapValue? {
            assert(classHeader.metadataVersion.isCompatible()) { "Incompatible class ($classHeader): $location" }

            val bytes by lazy { BitEncoding.decodeBytes(classHeader.data!!) }
            val strings by lazy { classHeader.strings!! }

            return when (classHeader.kind) {
                KotlinClassHeader.Kind.CLASS -> {
                    ProtoMapValue(false, bytes, strings)
                }
                KotlinClassHeader.Kind.FILE_FACADE,
                KotlinClassHeader.Kind.MULTIFILE_CLASS_PART -> {
                    ProtoMapValue(true, bytes, strings)
                }
                else -> {
                    println("skip $classId")
                    null
                }
            }
        }

        val diff = difference(oldClass.readProto() ?: return,
                              newClass.readProto() ?: return)

        val changes = SmartList<String>()

        if (diff.isClassAffected) {
            changes.add("CLASS_SIGNATURE")
        }

        if (diff.changedMembersNames.isNotEmpty()) {
            changes.add("MEMBERS\n    ${diff.changedMembersNames.sorted()}")
        }

        if (changes.isEmpty()) {
            changes.add("NONE")
        }

        println("changes in ${oldClass.classId}: ${changes.joinToString()}")
    }

    protected fun File.createSubDirectory(relativePath: String): File {
        val directory = File(this, relativePath)
        FileUtil.createDirectory(directory)
        return directory
    }
}
