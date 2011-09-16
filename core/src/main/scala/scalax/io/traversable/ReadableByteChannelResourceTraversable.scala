package scalax.io
package traversable
import java.io.Reader
import scalax.io.CloseableIterator
import scalax.io.LongTraversable
import scalax.io.LongTraversableLike
import scalax.io.OpenedResource
import java.nio.channels.ReadableByteChannel
import java.nio.{ ByteBuffer => NioByteBuffer }

class ReadableByteChannelTraversable(
  resourceOpener: => OpenedResource[ReadableByteChannel],
  sizeFunc: () => Option[Long],
  val start: Long,
  val end: Long)
  extends LongTraversable[Byte]
  with LongTraversableLike[Byte, LongTraversable[Byte]] {

  protected[io] def iterator: CloseableIterator[Byte] = {
    val resource = resourceOpener
    val buffer = resource.get match {
      case seekable: SeekableByteChannel => Buffers.nioDirectBuffer(Some(seekable.size))
      case _ => Buffers.nioDirectBuffer(sizeFunc())
    }
    resource.get match {
      case seekable: SeekableByteChannel =>
        new SeekableByteChannelIterator(buffer, resource.asInstanceOf[OpenedResource[SeekableByteChannel]], start, end)
      case _ =>
        new ReadableByteChannelIterator(buffer, resource, start, end)
    }
  }

  override lazy val hasDefiniteSize = sizeFunc().nonEmpty
  override def lsize = sizeFunc() match {
    case Some(size) => size
    case None => super.size
  }
  override def size = lsize.toInt

}

private[traversable] class ReadableByteChannelIterator(
  buffer: NioByteBuffer,
  openResource: OpenedResource[ReadableByteChannel],
  startIndex: Long,
  endIndex: Long) extends CloseableIterator[Byte] {
  private final val inConcrete = openResource.get

  skip(startIndex)

  private final var read = inConcrete.read(buffer)
  private final var i = 0
  final def hasNext = {
    if (i < read) true
    else {
      i = 0
      buffer.clear()
      read = inConcrete.read(buffer)
      i < read
    }
  }
  @specialized(Byte)
  final def next = {
    i += 1
    buffer.get(i - 1)
  }
  def doClose() = openResource.close()

  def skip(count: Long) {
    if (count > 0) {
      val toRead = (buffer.capacity.toLong min count).toInt

      buffer.clear()
      buffer.limit(toRead)

      val read = inConcrete read buffer
      if (read > -1) skip(count - read)
    }
  }
}

private[traversable] class SeekableByteChannelIterator(
  buffer: NioByteBuffer,
  openResource: OpenedResource[SeekableByteChannel],
  startIndex: Long,
  endIndex: Long) extends CloseableIterator[Byte] {
  private final val channel = openResource.get
  channel.position(startIndex)
  channel.read(buffer)
  buffer.flip
  private final var position = startIndex

  def hasNext = {
    if (buffer.hasRemaining) true
    else {
      channel.position(position)
      buffer.clear
      (channel read buffer)
      buffer.flip
      buffer.hasRemaining()
    }
  }
  @specialized(Byte)
  def next = {
    position += 1
    buffer.get()
  }
  def doClose() = openResource.close()
}