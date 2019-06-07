package org.junit.build.stats.mavencentral

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.AbstractTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.PasswordAuthentication
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

open class DownloadMavenCentralStatistics @Inject constructor(@get:Internal val workerExecutor: WorkerExecutor, extension: MavenCentralStatisticsExtension) : AbstractTask() {

    @Input
    val baseUrl: Property<URI> = project.objects.property<URI>().convention(extension.baseUrl)

    @Input
    val username: Property<String> = project.objects.property<String>().convention(extension.username)

    @Input
    val password: Property<String> = project.objects.property<String>().convention(extension.password)

    @Input
    val startMonth: Property<YearMonth> = project.objects.property<YearMonth>().convention(YearMonth.now().minusMonths(1))

    @Input
    val endMonth: Property<YearMonth> = project.objects.property<YearMonth>().convention(YearMonth.now().minusMonths(1))

    @OutputDirectory
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("mavenCentralStats"))

    private val yearMonthFormatter = DateTimeFormatter.ofPattern("yyyyMM")

    @TaskAction
    fun downloadCsvFiles() {
        val authenticator = PasswordAuthenticator(username.get(), password.get())
        val baseUrl = baseUrl.get()
        getJson("$baseUrl/projects", authenticator) { json ->
            json.array<JsonObject>("data")?.forEach {
                val projectId = it.string("id")
                println("- project: ${it.string("name")}")
                getJson("$baseUrl/coord/$projectId", authenticator) { json ->
                    json.array<String>("data")?.forEach { groupId ->
                        println("  - group id: $groupId")
                        getJson("$baseUrl/coord/$projectId?g=$groupId", authenticator) { json ->
                            json.array<String>("data")?.forEach { artifactId ->
                                println("    - artifact id: $artifactId")
                                (startMonth.get()..endMonth.get()).forEach { month ->
                                    scheduleCsvDownload(projectId, groupId, artifactId, month)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scheduleCsvDownload(projectId: String?, groupId: String, artifactId: String, month: YearMonth) {
        val monthFormatted = month.format(yearMonthFormatter)
        workerExecutor.submit(DownloadCsv::class.java) {
            val fileName = "$projectId/$groupId/$artifactId/$monthFormatted.csv"
            displayName = "Downloading $fileName"
            params(
                    URL("${baseUrl.get()}/slices_csv?p=$projectId&g=$groupId&a=$artifactId&t=raw&from=$monthFormatted&nom=1"),
                    username.get(), password.get(),
                    outputDirectory.file(fileName).get().asFile
            )
        }
    }

    private fun <T> getJson(url: String, authenticator: Authenticator, handler: (JsonObject) -> T) {
        with(URL(url).openConnection() as HttpURLConnection) {
            setAuthenticator(authenticator)
            addRequestProperty("Accept", "application/json")
            inputStream.reader(UTF_8).use {
                handler(Klaxon().parseJsonObject(it))
            }
        }
    }

    open class DownloadCsv @Inject constructor(val url: URL, val username: String, val password: String, val targetFile: File) : Runnable {
        override fun run() {
            with(url.openConnection() as HttpURLConnection) {
                setAuthenticator(PasswordAuthenticator(username, password))
                inputStream.use { input ->
                    targetFile.parentFile.mkdirs()
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    class PasswordAuthenticator(val username: String, val password: String) : Authenticator() {
        override fun getPasswordAuthentication() = PasswordAuthentication(username, password.toCharArray())
    }

    operator fun YearMonth.rangeTo(other: YearMonth) = YearMonthProgression(this, other)

    class YearMonthProgression(override val start: YearMonth, override val endInclusive: YearMonth) : Iterable<YearMonth>, ClosedRange<YearMonth> {
        override fun iterator(): Iterator<YearMonth> = YearMonthIterator(start, endInclusive)
    }

    class YearMonthIterator(start: YearMonth, val endInclusive: YearMonth) : Iterator<YearMonth> {
        private var current = start

        override fun hasNext() = current <= endInclusive

        override fun next(): YearMonth {
            val next = current
            current = current.plusMonths(1)
            return next
        }
    }

}