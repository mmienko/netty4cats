package cats.netty
package channel

import java.lang.{Boolean => JBoolean}
import java.net.{InetAddress, NetworkInterface}

import io.netty.buffer.ByteBufAllocator
import io.netty.channel.{ChannelOption => JChannelOption, MessageSizeEstimator, RecvByteBufAllocator, WriteBufferWaterMark}

/**
  * Wrapper over io.netty.channel.ChannelOption All configs except auto-read are exposed to avoid
  * corrupting backpressure
  */
sealed trait ChannelOption {
  type Value
  val key: JChannelOption[Value]
  val value: Value
}

object ChannelOption {

  def apply[A](key0: JChannelOption[A], value0: A): ChannelOption =
    new ChannelOption {
      type Value = A
      val key = key0
      val value = value0
    }

  def allocator(value: ByteBufAllocator): ChannelOption =
    apply(JChannelOption.ALLOCATOR, value)

  def rcvBufAllocator(value: RecvByteBufAllocator): ChannelOption =
    apply(JChannelOption.RCVBUF_ALLOCATOR, value)

  def messageSizeEstimator(value: MessageSizeEstimator): ChannelOption =
    apply(JChannelOption.MESSAGE_SIZE_ESTIMATOR, value)

  /* */

  def connectTimeoutMillis(value: Int): ChannelOption =
    apply(JChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(value))

  /* */

  def maxMessagesPerWrite(value: Int): ChannelOption =
    apply(JChannelOption.MAX_MESSAGES_PER_WRITE, Integer.valueOf(value))

  def writeSpinCount(value: Int): ChannelOption =
    apply(JChannelOption.WRITE_SPIN_COUNT, Integer.valueOf(value))

  def writeBufferWaterMark(value: WriteBufferWaterMark): ChannelOption =
    apply(JChannelOption.WRITE_BUFFER_WATER_MARK, value)

  /* */

  def allowHalfClosure(value: Boolean): ChannelOption =
    apply(JChannelOption.ALLOW_HALF_CLOSURE, JBoolean.valueOf(value))

  def autoClose(value: Boolean): ChannelOption =
    apply(JChannelOption.AUTO_CLOSE, JBoolean.valueOf(value))

  /* */

  def soBroadcast(value: Boolean): ChannelOption =
    apply(JChannelOption.SO_BROADCAST, JBoolean.valueOf(value))

  def soKeepAlive(value: Boolean): ChannelOption =
    apply(JChannelOption.SO_KEEPALIVE, JBoolean.valueOf(value))

  def soSendBuffer(value: Int): ChannelOption =
    apply(JChannelOption.SO_SNDBUF, Integer.valueOf(value))

  def soReceiveBuffer(value: Int): ChannelOption =
    apply(JChannelOption.SO_RCVBUF, Integer.valueOf(value))

  def soReuseAddress(value: Boolean): ChannelOption =
    apply(JChannelOption.SO_REUSEADDR, JBoolean.valueOf(value))

  def soLinger(value: Int): ChannelOption =
    apply(JChannelOption.SO_LINGER, Integer.valueOf(value))

  def soBacklog(value: Int): ChannelOption =
    apply(JChannelOption.SO_BACKLOG, Integer.valueOf(value))

  def soTimeout(value: Int): ChannelOption =
    apply(JChannelOption.SO_TIMEOUT, Integer.valueOf(value))

  /* */

  def ipTos(value: Int): ChannelOption =
    apply(JChannelOption.IP_TOS, Integer.valueOf(value))

  def ipMulticastAddr(value: InetAddress): ChannelOption =
    apply(JChannelOption.IP_MULTICAST_ADDR, value)

  def ipMulticastIf(value: NetworkInterface): ChannelOption =
    apply(JChannelOption.IP_MULTICAST_IF, value)

  def ipMulticastTtl(value: Int): ChannelOption =
    apply(JChannelOption.IP_MULTICAST_TTL, Integer.valueOf(value))

  def ipMulticastLoopDisabled(value: Boolean): ChannelOption =
    apply(JChannelOption.IP_MULTICAST_LOOP_DISABLED, JBoolean.valueOf(value))

  /* */

  def tcpNoDelay(value: Boolean): ChannelOption =
    apply(JChannelOption.TCP_NODELAY, JBoolean.valueOf(value))

  def tcpFastopenConnect(value: Boolean): ChannelOption =
    apply(JChannelOption.TCP_FASTOPEN_CONNECT, JBoolean.valueOf(value))

  def tcpFastopen(value: Int): ChannelOption =
    apply(JChannelOption.TCP_FASTOPEN, Integer.valueOf(value))

}
