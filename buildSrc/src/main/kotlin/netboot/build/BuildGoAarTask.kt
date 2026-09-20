package netboot.build

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
/** Builds a cacheable Go AAR; [VerifyGoAarTask] validates and installs it. */
@CacheableTask
abstract class BuildGoAarTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goModFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSumFile: RegularFileProperty

    @get:OutputFile
    abstract val aarOutput: RegularFileProperty

    @get:Input abstract val executablePath: Property<String>
    @get:Input abstract val sdkDirectory: Property<String>
    @get:Input abstract val ndkDirectory: Property<String>
    @get:Input abstract val javaHome: Property<String>
    @get:Input abstract val inheritedPath: Property<String>
    @get:Input abstract val targetAbis: Property<String>
    @get:Input abstract val androidApiLevel: Property<Int>
    @get:Input abstract val javaPackage: Property<String>
    @get:Input abstract val goPackage: Property<String>

    @TaskAction
    fun build() {
        val ndk = File(ndkDirectory.get())
        check(File(ndk, "meta/platforms.json").isFile) {
            "Android NDK at $ndk is missing or incomplete. " +
                "Install it with: sdkmanager --install \"ndk;${ndk.name}\""
        }
        val output = aarOutput.get().asFile
        output.parentFile.mkdirs()
        output.delete()
        val javaBin = "${javaHome.get()}${File.separator}bin"
        val basePath = "$javaBin${File.pathSeparator}${inheritedPath.get()}"

        // Install build-local gobind from the same go.mod revision as gomobile.
        val gobindDirectory = File(output.parentFile, "gobind")
        gobindDirectory.mkdirs()
        execOps.exec {
            workingDir(goSourceDirectory.get().asFile)
            executable(executablePath.get())
            environment("GOTOOLCHAIN", "auto")
            environment("GOBIN", gobindDirectory.absolutePath)
            environment("JAVA_HOME", javaHome.get())
            setPath(basePath)
            args("install", "golang.org/x/mobile/cmd/gobind")
        }
        check(listOf("gobind", "gobind.exe").any { File(gobindDirectory, it).isFile }) {
            "go install produced no gobind binary in $gobindDirectory"
        }

        execOps.exec {
            workingDir(goSourceDirectory.get().asFile)
            executable(executablePath.get())
            // Use the toolchain selected by go.mod.
            environment("GOTOOLCHAIN", "auto")
            environment("ANDROID_HOME", sdkDirectory.get())
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            environment("JAVA_HOME", javaHome.get())
            // The provisioned gobind must come first so a stale global copy can never win.
            setPath("${gobindDirectory.absolutePath}${File.pathSeparator}$basePath")
            args(
                "tool", "gomobile", "bind",
                "-target=${targetAbis.get()}",
                "-androidapi=${androidApiLevel.get()}",
                "-javapkg=${javaPackage.get()}",
                "-trimpath",
                "-o=${output.absolutePath}",
                goPackage.get(),
            )
        }
        check(output.isFile) { "gomobile bind reported success but ${output.name} is missing" }
    }

    /** Set both spellings: POSIX requires PATH, while Windows names are case-insensitive. */
    private fun ExecSpec.setPath(value: String) {
        environment("PATH", value)
        environment("Path", value)
    }
}
