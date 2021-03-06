package uos.dev.restcli

import com.github.ajalt.mordant.TermColors
import mu.KotlinLogging
import uos.dev.restcli.executor.OkhttpRequestExecutor
import uos.dev.restcli.jsbridge.JsClient
import uos.dev.restcli.parser.Parser
import uos.dev.restcli.parser.Request
import uos.dev.restcli.parser.RequestEnvironmentInjector
import uos.dev.restcli.report.AsciiArtTestReportGenerator
import uos.dev.restcli.report.TestGroupReport
import uos.dev.restcli.report.TestReportPrinter
import uos.dev.restcli.report.TestReportStore
import java.io.FileReader
import java.io.PrintWriter

class HttpRequestFilesExecutor constructor(
    private val httpFilePaths: Array<String>,
    private val environmentName: String?,
    private val customEnvironment: CustomEnvironment,
    private val logLevel: HttpLoggingLevel
) : Runnable {
    private val parser: Parser = Parser()
    private val jsClient: JsClient = JsClient()
    private val requestEnvironmentInjector: RequestEnvironmentInjector =
        RequestEnvironmentInjector()
    private val logger = KotlinLogging.logger {}
    private val t: TermColors = TermColors()

    override fun run() {
        if (httpFilePaths.isEmpty()) {
            logger.error { t.red("HTTP request file[s] is required") }
            return
        }
        val environment = (environmentName?.let { EnvironmentLoader().load(it) } ?: emptyMap())
            .toMutableMap()
        val executor = OkhttpRequestExecutor(logLevel.toOkHttpLoggingLevel())
        val testGroupReports = mutableListOf<TestGroupReport>()
        httpFilePaths.forEach { httpFilePath ->
            logger.info("\n__________________________________________________\n")
            logger.info(t.bold("HTTP REQUEST FILE: $httpFilePath"))
            TestReportStore.clear()
            executeHttpRequestFile(
                httpFilePath,
                environment,
                executor
            )
            logger.info("\n__________________________________________________\n")

            TestReportPrinter(httpFilePath).print(TestReportStore.testGroupReports)
            testGroupReports.addAll(TestReportStore.testGroupReports)
        }
        val consoleWriter = PrintWriter(System.out)
        AsciiArtTestReportGenerator().generate(testGroupReports, consoleWriter)
        consoleWriter.flush()
    }

    fun allTestsFinishedWithSuccess(): Boolean {
        return TestReportStore.testGroupReports
            .flatMap { it.testReports }
            .all { it.isPassed }
    }

    private fun executeHttpRequestFile(
        httpFilePath: String,
        environment: Map<String, String>,
        executor: OkhttpRequestExecutor
    ) {
        val requests = try {
            parser.parse(FileReader(httpFilePath))
        } catch (e: Exception) {
            logger.error(e) { "Can't parse $httpFilePath" }
            return
        }
        var requestIndex = -1
        while (requestIndex < requests.size) {
            val requestName = TestReportStore.nextRequestName
            TestReportStore.setNextRequest(null)
            if (requestName == REQUEST_NAME_END) {
                logger.warn { t.yellow("Next request is _END_ -> FINISH.") }
                return
            }
            if (requestName == null) {
                requestIndex++
            } else {
                val indexOfRequestName = requests.indexOfFirst { it.name == requestName }
                requestIndex = if (indexOfRequestName < 0) {
                    logger.warn {
                        t.yellow(
                            "Request name: $requestName is not defined yet." +
                                    " So continue execute the request by order"
                        )
                    }
                    requestIndex + 1
                } else {
                    indexOfRequestName
                }
            }

            if (requestIndex < 0 || requestIndex >= requests.size) {
                return
            }

            val rawRequest = requests[requestIndex]

            runCatching {
                val jsGlobalEnv = jsClient.globalEnvironment()
                val request = requestEnvironmentInjector.inject(
                    rawRequest,
                    customEnvironment,
                    environment,
                    jsGlobalEnv
                )
                val trace = TestGroupReport.Trace(
                    httpTestFilePath = httpFilePath,
                    scriptHandlerStartLine = request.scriptHandlerStartLine
                )
                TestReportStore.addTestGroupReport(request.requestTarget, trace)
                logger.info("\n__________________________________________________\n")
                logger.info(t.bold("##### ${request.method.name} ${request.requestTarget} #####"))
                executeSingleRequest(executor, request)
            }.onFailure { logger.error { t.red(it.message.orEmpty()) } }
        }
    }

    private fun executeSingleRequest(executor: OkhttpRequestExecutor, request: Request) {
        runCatching { executor.execute(request) }
            .onSuccess { response ->
                jsClient.updateResponse(response)
                request.scriptHandler?.let { script ->
                    val testTitle = t.bold("TESTS:")
                    logger.info("\n$testTitle")
                    jsClient.execute(script)
                }
            }
            .onFailure {
                val hasScriptHandler = request.scriptHandler != null
                if (hasScriptHandler) {
                    logger.info(t.yellow("[SKIP TEST] Because: ") + it.message.orEmpty())
                }
            }
    }

    companion object {
        /**
         * The specific request name. If the next request is sets to this name, the executor will
         * be end immediately.
         */
        const val REQUEST_NAME_END: String = "_END_"
    }
}
