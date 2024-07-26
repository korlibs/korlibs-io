package korlibs.io.runtime.node

import korlibs.time.*
import korlibs.logger.Logger
import korlibs.io.*
import korlibs.io.async.*
import korlibs.io.file.*
import korlibs.io.file.std.LocalVfs
import korlibs.io.file.std.ShellArgs
import korlibs.io.lang.FileAlreadyExistsException
import korlibs.io.lang.FileNotFoundException
import korlibs.io.lang.IOException
import korlibs.io.net.AsyncClient
import korlibs.io.net.AsyncServer
import korlibs.io.net.http.Http
import korlibs.io.net.http.HttpClient
import korlibs.io.net.http.HttpServer
import korlibs.io.runtime.JsRuntime
import korlibs.io.stream.*
import korlibs.js.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.coroutines.*

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

typealias NodeJsBuffer = Uint8Array

fun Uint8Array.toByteArray() = Int8Array(this.unsafeCast<Int8Array>()).unsafeCast<ByteArray>()
//fun ByteArray.toNodeJsBufferU8(): NodeBuffer = Uint8Array(this.unsafeCast<ArrayBuffer>()).asDynamic()

fun ByteArray.asInt8Array(): Int8Array = this.unsafeCast<Int8Array>()
fun ByteArray.asUint8Array(): Uint8Array {
    val i = this.asInt8Array()
    return Uint8Array(i.buffer, i.byteOffset, i.length)
}

fun ByteArray.toNodeJsBuffer(): NodeJsBuffer = this.asUint8Array().unsafeCast<NodeJsBuffer>()
fun ByteArray.toNodeJsBuffer(offset: Int, size: Int): NodeJsBuffer =
    jsGlobal.asDynamic().Buffer.from(this, offset, size).unsafeCast<NodeJsBuffer>()

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

private external val process: dynamic // node.js

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

internal object JsRuntimeNode : JsRuntime() {
    val nodeProcess get() = process
    override val rawOsName: String = process.platform.unsafeCast<String>()
    private val fs = NodeFS
    private val path = NodePath

    override fun existsSync(path: String): Boolean = fs.existsSync(path)
    override fun currentDir(): String = path.resolve(".")
    override fun openVfs(path: String): VfsFile {
        val rpath = if (path == ".") {
            val path = jsRuntime.currentDir()

            when {
                jsRuntime.existsSync("$path/node_modules")
                    && jsRuntime.existsSync("$path/kotlin")
                    && jsRuntime.existsSync("$path/package.json")
                ->
                    // We are probably on tests `build/js/packages/korlibs-next-korge-test` and resources are in the `kotlin` directory
                    "$path/kotlin"
                else -> path
            }
        } else {
            path
        }
        return NodeJsLocalVfs()[rpath]
    }

    override suspend fun createClient(secure: Boolean): AsyncClient = NodeJsAsyncClient(coroutineContext)
    override suspend fun createServer(port: Int, host: String, backlog: Int, secure: Boolean): AsyncServer =
        NodeJsAsyncServer().init(port, host, backlog)

    override fun createHttpClient(): HttpClient = HttpClient
    override fun createHttpServer(): HttpServer = HttpSeverNodeJs()
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

private class NodeJsAsyncClient(val coroutineContext: CoroutineContext) : AsyncClient {
    private val net = NodeNet.asDynamic()
    private var connection: dynamic = null
    private val input = AsyncRingBuffer()

    override var connected: Boolean = false; private set

    companion object {
        private val logger = Logger("NodeJsAsyncClient")
    }

    private val task = AsyncQueue().withContext(coroutineContext)

    fun setConnection(connection: dynamic) {
        this.connection = connection
        connected = true
        connection?.pause()
        connection?.on("data") { it ->
            val bytes = it.unsafeCast<ByteArray>().copyOf()
            task {
                input.write(bytes)
            }
        }
        connection?.on("error") { it ->
            logger.error { it }
        }
    }

    override suspend fun connect(host: String, port: Int): Unit = suspendCancellableCoroutine { c ->
        connection = net.createConnection(port, host) {
            setConnection(connection)
            c.resume(Unit)
        }
        c.invokeOnCancellation {
            connection.destroy()
            //c.cancel()
        }
        Unit
    }

    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        connection?.resume()
        try {
            return input.read(buffer, offset, len)
        } finally {
            connection?.pause()
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, len: Int): Unit = suspendCancellableCoroutine { c ->
        connection?.write(buffer.toNodeJsBuffer(offset, len)) {
            c.resume(Unit)
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun close() {
        if (connection != null) {
            val deferred = CompletableDeferred<Unit>()
            connection.end { deferred.complete(Unit) }
            deferred.await()
            connection.destroy()
        }
        connection = null
    }
}

private class NodeJsAsyncServer : AsyncServer {
    private val net = NodeNet
    private var server: dynamic = null
    override var requestPort: Int = -1; private set
    override var host: String = ""; private set
    override var backlog: Int = -1; private set
    override var port: Int = -1; private set

    companion object {
        private val logger = Logger("NodeJsAsyncServer")
    }

    private val clientFlow: Channel<dynamic> = Channel(Channel.UNLIMITED)

    override suspend fun accept(): AsyncClient {
        val connection = clientFlow.receive()
        return NodeJsAsyncClient(coroutineContext).also {
            it.setConnection(connection)
        }
    }

    suspend fun init(port: Int, host: String, backlog: Int): AsyncServer {
        server = net.asDynamic().createServer(jsObject(
        )) { connection ->
            clientFlow.trySend(connection)
        }
        clientFlow.invokeOnClose {
            server.close()
        }
        val deferred = CompletableDeferred<Unit>()
        server.on("error") { err ->
            logger.error { err }
        }
        server.listen(port, host, backlog) {
            deferred.complete(Unit)
        }
        deferred.await()
        this.backlog = backlog
        this.requestPort = port
        this.host = host
        this.port = server.address().port
        return this
    }

    override suspend fun close() {
        clientFlow.cancel()
    }
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

private class NodeJsLocalVfs : LocalVfs() {
    private fun getFullPath(path: String): String {
        return path.pathInfo.normalize()
    }

    fun NodeFileStat?.toVfsStat(path: String): VfsStat {
        val stats = this ?: return createNonExistsStat(path)
        return createExistsStat(
            path, stats.isDirectory(), stats.size.toLong(),
            stats.dev.toLong(), stats.ino.toLong(),
            mode = stats.mode.toInt(),
            createTime = DateTime.Companion.fromUnixMillis(stats.ctimeMs),
            modifiedTime = DateTime.Companion.fromUnixMillis(stats.mtimeMs),
            lastAccessTime = DateTime.Companion.fromUnixMillis(stats.atimeMs),
        )
    }

    override suspend fun setAttributes(path: String, attributes: List<Attribute>) {
        attributes.getOrNull<UnixPermissions>()?.let {
            chmod(path, it)
        }
    }

    override suspend fun chmod(path: String, mode: UnixPermissions) {
        val deferred = CompletableDeferred<Unit>()
        NodeFS.chmod(path, mode.rbits) { err ->
            if (err != null) deferred.completeExceptionally(err) else deferred.complete(Unit)
        }
        return deferred.await()
    }

    override suspend fun listFlow(path: String): Flow<VfsFile> {
        val deferred = CompletableDeferred<Array<String>>()
        NodeFS.readdir(path) { err, items ->
            if (err != null) {
                deferred.completeExceptionally(err)
            } else {
                deferred.complete(items)
            }
        }
        val files: List<VfsFile> = deferred.await().map { this.file("$path/$it") }
        return flowOf(*files.toTypedArray())
    }

    override suspend fun stat(path: String): VfsStat {
        val deferred = CompletableDeferred<VfsStat>()
        NodeFS.stat(path) { err, stats ->
            //println("err=$err")
            //println("stats=$stats")
            deferred.completeWith(kotlin.runCatching { stats.toVfsStat(path) })
        }
        return deferred.await()
    }

    override suspend fun exec(path: String, cmdAndArgs: List<String>, env: Map<String, String>, handler: VfsProcessHandler): Int {
        checkExecFolder(path, cmdAndArgs)

        // @TODO: This fails on windows with characters like '&'
        val realCmdAndArgs = ShellArgs.buildShellExecCommandLineArrayForNodeSpawn(cmdAndArgs)

        val process = NodeChildProcess.asDynamic().spawn(realCmdAndArgs.first(), realCmdAndArgs.drop(1).toTypedArray(), jsObject(
            "cwd" to path,
            "env" to env.toJsObject(),
            "encoding" to "buffer",
            "shell" to true
        ))

        val queue = AsyncQueue().withContext(coroutineContext)
        val exitCodeDeferred = CompletableDeferred<Int>()

        process.stdout.on("data") { data: NodeJsBuffer -> queue { handler.onOut(data.toByteArray()) } }
        process.stderr.on("data") { data: NodeJsBuffer -> queue { handler.onErr(data.toByteArray()) } }
        process.on("close") { code: Int -> exitCodeDeferred.complete(code) }

        return exitCodeDeferred.await()
    }

    override suspend fun mkdir(path: String, attributes: List<Attribute>): Boolean = suspendCancellableCoroutine { c ->
        NodeFS.mkdir(getFullPath(path), "777".toInt(8)) { err ->
            c.resume((err == null))
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun rename(src: String, dst: String): Boolean = suspendCancellableCoroutine { c ->
        NodeFS.rename(getFullPath(src), getFullPath(dst)) { err ->
            c.resume((err == null))
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun delete(path: String): Boolean = suspendCancellableCoroutine { c ->
        NodeFS.unlink(getFullPath(path)) { err ->
            c.resume((err == null))
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun rmdir(path: String): Boolean = suspendCancellableCoroutine { c ->
        NodeFS.rmdir(getFullPath(path)) { err ->
            c.resume((err == null))
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun open(path: String, mode: VfsOpenMode): AsyncStream {
        val cmode = when (mode) {
            VfsOpenMode.READ -> "r"
            VfsOpenMode.WRITE -> "r+"
            VfsOpenMode.CREATE_OR_TRUNCATE -> "w+"
            VfsOpenMode.CREATE_NEW -> {
                if (stat(path).exists) throw FileAlreadyExistsException(path)
                "w+"
            }
            VfsOpenMode.CREATE -> "wx+"
            VfsOpenMode.APPEND -> "a+"
        }

        return _open(path, cmode)
    }

    suspend fun _open(path: String, cmode: String): AsyncStream {
        val file = this.file(path)
        return suspendCancellableCoroutine { cc ->
            NodeFS.open(getFullPath(path), cmode) { err: Error?, fd: NodeFD? ->
                //println("OPENED path=$path, cmode=$cmode, err=$err, fd=$fd")
                if (err != null || fd == null) {
                    cc.resumeWithException(FileNotFoundException("Can't open '$path' with mode '$cmode': err=$err"))
                } else {
                    NodeFS.fstat(fd) { _, stats ->
                        cc.resume(NodeFDStream(file, fd).toAsyncStream(if (cmode == "a+") stats?.size?.toLong() ?: 0L else 0L))
                    }
                }
                Unit
            }
            cc.invokeOnCancellation {
                //cc.cancel()
            }
            Unit
        }
    }

    override fun toString(): String = "NodeJsLocalVfs"
}

private class NodeFDStream(val file: VfsFile, var fd: NodeFD?) : AsyncStreamBase() {
    private fun checkFd() {
        if (fd == null) error("File $file already closed")
    }

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = suspendCancellableCoroutine { c ->
        checkFd()
        NodeFS.read(fd, buffer.toNodeJsBuffer(), offset, len, position.toDouble()) { err, bytesRead, buf ->
            if (err != null) {
                c.resumeWithException(IOException("Error reading from $file :: err=$err"))
            } else {
                //println("NODE READ[$file] read: ${bytesRead} : ${buffer.sliceArray(0 until min(buffer.size, 5)).contentToString()}")
                c.resume(bytesRead)
            }
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int): Unit = suspendCancellableCoroutine { c ->
        checkFd()
        NodeFS.write(fd, buffer.toNodeJsBuffer(), offset, len, position.toDouble()) { err, bytesWritten, buffer ->
            if (err != null) {
                c.resumeWithException(IOException("Error writting to $file :: err=$err"))
            } else {
                c.resume(Unit)
            }
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun setLength(value: Long): Unit = suspendCancellableCoroutine { c ->
        checkFd()
        NodeFS.ftruncate(fd, value.toDouble()) { err ->
            if (err != null) {
                c.resumeWithException(IOException("Error setting length to $file :: err=$err"))
            } else {
                c.resume(Unit)
            }
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    override suspend fun getLength(): Long = suspendCancellableCoroutine { c ->
        checkFd()
        NodeFS.fstat(fd) { err, stats ->
            if (err != null) {
                c.resumeWithException(IOException("Error getting length from $file :: err=$err"))
            } else {
                //println("NODE READ getLength: ${stats.size}")
                c.resume(stats.size.toLong())
            }
            Unit
        }
        c.invokeOnCancellation {
            //c.cancel()
        }
        Unit
    }

    //private var closed = false

    override suspend fun close() {
        //if (closed) error("File already closed")
        //closed = true
        if (fd != null) {
            return suspendCancellableCoroutine { c ->
                NodeFS.close(fd) { err ->
                    fd = null
                    if (err != null) {
                        //c.resumeWithException(IOException("Error closing err=$err"))
                        c.resume(Unit) // Allow to close several times
                    } else {
                        c.resume(Unit)
                    }
                    Unit
                }
                c.invokeOnCancellation {
                    //c.cancel()
                }
                Unit
            }
        }
    }
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

private class HttpSeverNodeJs : HttpServer() {
    private var context: CoroutineContext = EmptyCoroutineContext
    private var handler: suspend (req: dynamic, res: dynamic) -> Unit = { req, res -> }

    val http = NodeHTTP.asDynamic()
    val server = http.createServer { req, res ->
        launchImmediately(context) {
            handler(req, res)
        }
    }

    override suspend fun errorHandlerInternal(handler: suspend (Throwable) -> Unit) {
        super.errorHandlerInternal(handler)
    }

    override suspend fun websocketHandlerInternal(handler: suspend (WsRequest) -> Unit) {
        super.websocketHandlerInternal(handler)
    }

    override suspend fun httpHandlerInternal(handler: suspend (Request) -> Unit) {
        context = coroutineContext
        this.handler = { req, res ->
            // req: https://nodejs.org/api/http.html#http_class_http_incomingmessage
            // res: https://nodejs.org/api/http.html#http_class_http_serverresponse

            val method = Http.Method[req.method.unsafeCast<String>()]
            val url = req.url.unsafeCast<String>()
            val headers = Http.Headers(jsToArray(req.rawHeaders).map { "$it" }.zipWithNext())
            handler(object : Request(method, url, headers, RequestConfig()) {
                override suspend fun _handler(handler: (ByteArray) -> Unit) {
                    req.on("data") { chunk ->
                        handler(Int8Array(chunk.unsafeCast<Uint8Array>().buffer).unsafeCast<ByteArray>())
                    }
                }

                override suspend fun _endHandler(handler: () -> Unit) {
                    req.on("end") {
                        handler()
                    }
                    req.on("error") {
                        handler()
                    }
                }

                override suspend fun _sendHeader(code: Int, message: String, headers: Http.Headers) {
                    res.statusCode = code
                    res.statusMessage = message
                    for (header in headers) {
                        res.setHeader(header.first, header.second)
                    }
                }

                override suspend fun _write(data: ByteArray, offset: Int, size: Int): Unit = suspendCancellableCoroutine { c ->
                    res.write(data.toNodeJsBuffer(offset, size)) {
                        c.resume(Unit)
                    }
                    c.invokeOnCancellation {
                        //c.cancel()
                    }
                    Unit
                }

                override suspend fun _end(): Unit = suspendCancellableCoroutine { c ->
                    res.end {
                        c.resume(Unit)
                    }
                    c.invokeOnCancellation {
                        //c.cancel()
                    }
                    Unit
                }
            })
        }
    }

    override suspend fun listenInternal(port: Int, host: String) = suspendCoroutine<Unit> { c ->
        context = c.context
        server.listen(port, host, 511) {
            c.resume(Unit)
        }
    }

    override val actualPort: Int
        get() {
            //korlibs.io.lang.Console.log(server)
            return jsEnsureInt(server.address().port)
        }

    override val actualHost: String
        get() {
            //korlibs.io.lang.Console.log(server)
            return jsEnsureString(server.address().address)
        }

    override suspend fun closeInternal() = suspendCoroutine<Unit> { c ->
        context = c.context
        server.close {
            c.resume(Unit)
        }
    }
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
