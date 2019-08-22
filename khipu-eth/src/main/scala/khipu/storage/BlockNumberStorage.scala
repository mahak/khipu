package khipu.storage

import java.nio.ByteBuffer
import khipu.Hash
import khipu.storage.datasource.DataSource
import khipu.storage.datasource.LmdbDataSource
import khipu.util.SimpleMapWithUnconfirmed
import scala.collection.mutable

object BlockNumberStorage {
  val namespace: Array[Byte] = Array[Byte]()
}
/**
 * This class is used to store the blockhash -> blocknumber
 */
final class BlockNumberStorage(val source: DataSource, unconfirmedDepth: Int) extends SimpleMapWithUnconfirmed[Hash, Long](unconfirmedDepth) {
  type This = BlockNumberStorage

  import BlockNumberStorage._

  def topic = source.topic

  private def keyToBytes(k: Hash): Array[Byte] = k.bytes
  private def valueToBytes(v: Long): Array[Byte] = ByteBuffer.allocate(8).putLong(v).array
  private def valueFromBytes(bytes: Array[Byte]): Long = ByteBuffer.wrap(bytes).getLong

  override protected def doGet(key: Hash): Option[Long] = {
    source.get(namespace, keyToBytes(key)).map(valueFromBytes)
  }

  override protected def doUpdate(toRemove: Iterable[Hash], toUpsert: Iterable[(Hash, Long)]): This = {
    val remove = toRemove map { key => keyToBytes(key) }
    val upsert = toUpsert map {
      case (key, value) => (keyToBytes(key) -> valueToBytes(value))
    }
    source.update(namespace, remove, upsert)
    this
  }

  def count = source.asInstanceOf[LmdbDataSource].count
}
