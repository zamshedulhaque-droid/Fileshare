package com.fastshare

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object Proto {
    const val PORT = 8988
    const val STREAMS = 4              // প্যারালাল TCP স্ট্রিম
    const val BUF = 1 shl 20           // 1MB বাফার
    const val MULTI_MIN = 4L shl 20    // 4MB এর ছোট ফাইলে ১টা স্ট্রিম
}

/** রিসিভার: পোর্ট 8988 এ শোনে, ফাইল RandomAccess করে লেখে */
class FileReceiver(
    private val dir: File,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private class Job(val raf: RandomAccessFile, val size: Long, val control: Socket, val name: String) {
        val received = AtomicLong(0)
    }
    private val jobs = ConcurrentHashMap<Long, Job>()
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "recv-accept") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.receiveBufferSize = Proto.BUF
                ss.bind(InetSocketAddress(Proto.PORT))
                server = ss
                onStatus("প্রস্তুত, সেন্ডারের জন্য অপেক্ষা...")
                while (true) {
                    val s = ss.accept()
                    thread { try { handle(s) } catch (e: Exception) { onStatus("ত্রুটি: ${e.message}") } }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        val inp = DataInputStream(s.getInputStream())
        when (inp.readByte().toInt()) {
            0 -> { // কন্ট্রোল: ফাইলের হেডার
                val id = inp.readLong(); val name = inp.readUTF(); val size = inp.readLong()
                dir.mkdirs()
                val f = uniqueFile(name)
                val raf = RandomAccessFile(f, "rw"); raf.setLength(size)
                jobs[id] = Job(raf, size, s, f.name)
                onStatus("গ্রহণ করছে: ${f.name}")
                s.getOutputStream().apply { write(1); flush() } // ack
                if (size == 0L) finish(id)
            }
            1 -> { // ডেটা স্ট্রিম
                val id = inp.readLong(); val off = inp.readLong(); val len = inp.readLong()
                val job = jobs[id] ?: return
                val ch = job.raf.channel
                val buf = ByteArray(Proto.BUF)
                var pos = off; var rem = len
                while (rem > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                    if (n < 0) throw EOFException()
                    ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                    pos += n; rem -= n
                    val total = job.received.addAndGet(n.toLong())
                    onProgress((total * 100 / job.size).toInt())
                }
                s.close()
                if (job.received.get() >= job.size) finish(id)
            }
        }
    }

    private fun finish(id: Long) {
        val job = jobs.remove(id) ?: return
        try { job.raf.close() } catch (_: Exception) {}
        try { job.control.getOutputStream().apply { write(1); flush() } } catch (_: Exception) {}
        onStatus("সম্পন্ন: ${job.name}")
    }

    private fun uniqueFile(name: String): File {
        var f = File(dir, name); var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }
}

/** সেন্ডার: প্রতিটি ফাইল কয়েক ভাগে ভেঙে প্যারালাল পাঠায়, transferTo (zero-copy) দিয়ে */
class FileSender(
    private val ctx: Context,
    private val host: String,
    private val uris: List<Uri>,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    fun start() = thread(name = "sender") {
        try {
            uris.forEachIndexed { i, u -> sendOne(u, i + 1, uris.size) }
            onStatus("সব ফাইল পাঠানো শেষ ✓")
        } catch (e: Exception) { onStatus("ত্রুটি: ${e.message}") }
    }

    private fun sendOne(uri: Uri, idx: Int, count: Int) {
        var name = "file_$idx"; var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0) size = c.getLong(s)
            }
        }
        onStatus("পাঠাচ্ছে ($idx/$count): $name")
        val id = System.nanoTime()
        val control = Socket()
        control.connect(InetSocketAddress(host, Proto.PORT), 10_000)
        val out = DataOutputStream(control.getOutputStream())
        out.writeByte(0); out.writeLong(id); out.writeUTF(name); out.writeLong(size); out.flush()
        control.getInputStream().read() // ack অপেক্ষা

        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("ফাইল খোলা যায়নি")
        pfd.use {
            val fch = FileInputStream(it.fileDescriptor).channel
            val n = if (size < Proto.MULTI_MIN) 1 else Proto.STREAMS
            val chunk = (size + n - 1) / n
            val sent = AtomicLong(0)
            val errors = java.util.Collections.synchronizedList(mutableListOf<Exception>())
            val threads = (0 until n).mapNotNull { k ->
                val off = k * chunk
                val len = minOf(chunk, size - off)
                if (len <= 0) null else thread {
                    try {
                        val sc = SocketChannel.open()
                        sc.socket().sendBufferSize = Proto.BUF
                        sc.socket().tcpNoDelay = true
                        sc.connect(InetSocketAddress(host, Proto.PORT))
                        val o = DataOutputStream(sc.socket().getOutputStream())
                        o.writeByte(1); o.writeLong(id); o.writeLong(off); o.writeLong(len); o.flush()
                        var pos = off; var rem = len
                        while (rem > 0) {
                            val t = fch.transferTo(pos, minOf(rem, 8L shl 20), sc)
                            if (t <= 0) { Thread.sleep(1); continue }
                            pos += t; rem -= t
                            onProgress((sent.addAndGet(t) * 100 / size).toInt())
                        }
                        sc.close()
                    } catch (e: Exception) { errors.add(e) }
                }
            }
            threads.forEach { t -> t.join() }
            if (errors.isNotEmpty()) throw errors[0]
        }
        if (size > 0) control.getInputStream().read() // রিসিভারের "done"
        control.close()
        onProgress(100)
    }
}
