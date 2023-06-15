package cats.netty
package http.websocket

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyList

/**
  * @param maxFramePayloadLength
  *   - limit on payload length from Text and Binary Frames. TODO: Only applies to inbound data
  *     frames.
  * @param allowExtensions
  *   - WS extensions like those for compression
  */
final case class WebSocketConfig(
  maxFramePayloadLength: Int,
  allowExtensions: Boolean,
  subProtocols: NonEmptyList[String],
  closeTimeout: FiniteDuration
)
