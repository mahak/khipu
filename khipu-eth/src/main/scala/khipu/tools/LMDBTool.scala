package khipu.tools

import java.io.File
import java.nio.ByteBuffer
import khipu.Hash
import khipu.crypto
import scala.util.Random
import org.lmdbjava.Env
import org.lmdbjava.EnvFlags
import org.lmdbjava.Dbi
import org.lmdbjava.DbiFlags
import org.lmdbjava.GetOp
import org.lmdbjava.SeekOp
import scala.collection.mutable

/**
 * Fill memory:
 * # stress -m 1 --vm-bytes 25G --vm-keep
 */
object LMDBTool {
  def main(args: Array[String]) {
    val dbTool = new LMDBTool()

    dbTool.test("table1", 50000000)
    dbTool.closeEnv()
    System.exit(0)
  }
}
class LMDBTool() {
  private def xf(n: Double) = "%1$10.1f".format(n)

  val cacheSize = 1024 * 1024 * 1024L // 1G
  val bufLen = 1024 * 1024
  val mapSize = 100 * 1024 * 1024 * 1024L

  val COMPILED_MAX_KEY_SIZE = 511

  val averKeySize = 4
  val averDataSize = 1024
  val hashNumElements = 300000000

  val home = {
    val h = new File("/home/dcaoyuan/tmp")
    if (!h.exists) {
      h.mkdirs()
    }
    println(s"lmdb home: $h")
    h
  }

  val env = Env.create()
    .setMapSize(mapSize)
    .setMaxDbs(6)
    .open(home, EnvFlags.MDB_NORDAHEAD)

  def test(tableName: String, num: Int) = {
    val table = if (averDataSize > COMPILED_MAX_KEY_SIZE) {
      env.openDbi(tableName, DbiFlags.MDB_CREATE)
    } else {
      env.openDbi(tableName, DbiFlags.MDB_CREATE, DbiFlags.MDB_DUPSORT)
    }

    val keys = write(table, num)
    read(table, keys)

    table.close()
  }

  def write(table: Dbi[ByteBuffer], num: Int) = {
    val keyBuf = ByteBuffer.allocateDirect(env.getMaxKeySize)
    val valBuf = ByteBuffer.allocateDirect(100 * 1024) // will grow when needed

    val keys = new java.util.ArrayList[Array[Byte]]()
    val start0 = System.nanoTime
    var start = System.nanoTime
    var elapsed = 0L
    var totalElapsed = 0L
    var i = 0
    val nKeysToRead = 1000000
    val keyInterval = math.max(num / nKeysToRead, 1)
    while (i < num) {

      var j = 0
      val txn = env.txnWrite()
      while (j < 4000 && i < num) {
        val v = Array.ofDim[Byte](averDataSize)
        Random.nextBytes(v)
        val k = crypto.kec256(v)

        start = System.nanoTime

        val theKey = if (averDataSize > COMPILED_MAX_KEY_SIZE) k else sliceBytes(k)
        try {
          keyBuf.put(theKey).flip()
          valBuf.put(v).flip()
          if (!table.put(txn, keyBuf, valBuf)) {
            println(s"put failed: ${khipu.toHexString(theKey)}")
          }
        } catch {
          case ex: Throwable =>
            txn.abort()
            println(ex)
        } finally {
          keyBuf.clear()
          valBuf.clear()
        }

        val duration = System.nanoTime - start
        elapsed += duration
        totalElapsed += duration

        if (i % keyInterval == 0) {
          keys.add(k)
        }

        j += 1
        i += 1
      }

      start = System.nanoTime

      try {
        txn.commit()
      } catch {
        case ex: Throwable =>
          txn.abort()
          println(ex)
      } finally {
        txn.close()
      }

      val duration = System.nanoTime - start
      elapsed += duration
      totalElapsed += duration

      if (i > 0 && i % 100000 == 0) {
        val speed = 100000 / (elapsed / 1000000000.0)
        println(s"${java.time.LocalTime.now} $i ${xf(speed)}/s - write")
        start = System.nanoTime
        elapsed = 0L
      }
    }

    val speed = i / (totalElapsed / 1000000000.0)
    println(s"${java.time.LocalTime.now} $i ${xf(speed)}/s - write all in ${xf((totalElapsed / 1000000000.0))}s")

    keys
  }

  def read(table: Dbi[ByteBuffer], keys: java.util.ArrayList[Array[Byte]]) {
    java.util.Collections.shuffle(keys)

    val keyBuf = ByteBuffer.allocateDirect(env.getMaxKeySize)

    val start0 = System.nanoTime
    var start = System.nanoTime
    val itr = keys.iterator
    var i = 0
    while (itr.hasNext) {
      val k = itr.next
      val theKey = if (averDataSize > COMPILED_MAX_KEY_SIZE) k else sliceBytes(k)
      keyBuf.put(theKey).flip()

      val txn = env.txnRead()
      try {
        val cursor = table.openCursor(txn)

        var gotData: Option[Array[Byte]] = None
        if (cursor.get(keyBuf, GetOp.MDB_SET_KEY)) {
          val data = Array.ofDim[Byte](cursor.`val`.remaining)
          cursor.`val`.get(data)
          val fullKey = crypto.kec256(data)
          if (java.util.Arrays.equals(fullKey, k)) {
            gotData = Some(data)
          }

          while (gotData.isEmpty && cursor.seek(SeekOp.MDB_NEXT_DUP)) {
            val data = Array.ofDim[Byte](cursor.`val`.remaining)
            cursor.`val`.get(data)
            val fullKey = crypto.kec256(data)
            if (java.util.Arrays.equals(fullKey, k)) {
              gotData = Some(data)
            }
          }
        }

        cursor.close()

        if (gotData.isEmpty) {
          println(s"===> no data for ${khipu.toHexString(theKey)} of ${khipu.toHexString(k)}")
        }
      } catch {
        case ex: Throwable =>
          txn.abort()
          println(ex)
          null
      }
      txn.commit()
      txn.close()

      if (i > 0 && i % 10000 == 0) {
        val elapsed = (System.nanoTime - start) / 1000000000.0 // sec
        val speed = 10000 / elapsed
        val hashKey = Hash(k)
        println(s"${java.time.LocalTime.now} $i ${xf(speed)}/s - 0x$hashKey")
        start = System.nanoTime
      }

      keyBuf.clear()
      i += 1
    }

    val totalElapsed = (System.nanoTime - start0) / 1000000000.0 // sec
    val speed = i / totalElapsed
    println(s"${java.time.LocalTime.now} $i ${xf(speed)}/s - read all in ${xf(totalElapsed)}s")
  }

  final def sliceBytes(bytes: Array[Byte]) = {
    val slice = Array.ofDim[Byte](4)
    System.arraycopy(bytes, 0, slice, 0, 4)
    slice
  }

  final def intToBytes(i: Int) = ByteBuffer.allocate(4).putInt(i).array
  final def bytesToInt(bytes: Array[Byte]) = ByteBuffer.wrap(bytes).getInt

  def closeEnv() {
    env.close()
  }
}

