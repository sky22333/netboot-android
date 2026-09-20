package netboot.build

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Reject missing or unexpected ABIs before installing the AAR into app/libs. */
@CacheableTask
abstract class VerifyGoAarTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aarInput: RegularFileProperty

    @get:OutputFile
    abstract val installedAar: RegularFileProperty

    @get:Input
    abstract val requiredAbis: ListProperty<String>

    @get:Input
    abstract val forbiddenAbis: ListProperty<String>

    @TaskAction
    fun verify() {
        val source = aarInput.get().asFile
        val nativeEntries = mutableListOf<String>()
        ZipFile(source).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.name.startsWith("jni/")) nativeEntries += entry.name
            }
            requiredAbis.get().forEach { abi ->
                val path = "jni/$abi/libgojni.so"
                val entry = zip.getEntry(path)
                    ?: throw GradleException(
                        "AAR ${source.name} is missing $path. Native entries present: ${nativeEntries.sorted()}",
                    )
                if (entry.size <= 0L) throw GradleException("AAR entry $path is empty")
            }
        }
        forbiddenAbis.get().forEach { abi ->
            val path = "jni/$abi/"
            if (nativeEntries.any { it.startsWith(path) }) {
                throw GradleException("AAR ${source.name} must not contain $path for a phone release")
            }
        }
        val destination = installedAar.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
        logger.lifecycle("netboot-core.aar installed at ${destination.absolutePath}")
    }
}
