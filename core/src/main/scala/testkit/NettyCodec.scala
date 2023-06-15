package cats.netty
package testkit

import java.util

import cats.data.NonEmptyList
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker
import io.netty.handler.codec.http.websocketx.{WebSocket13FrameDecoder, WebSocket13FrameEncoder, WebSocketFrame}

object NettyCodec {
  private val wsCompressionHandshaker =
    new PerMessageDeflateClientExtensionHandshaker().handshakeExtension(
      new WebSocketExtensionData("permessage-deflate", new util.HashMap[String, String](1))
    )

  implicit val httpRequestResponseCodec: NettyEncoder[HttpRequest] with NettyDecoder[HttpResponse] =
    new NettyEncoder[HttpRequest] with NettyDecoder[HttpResponse] {
      override def encoders: NonEmptyList[ChannelHandler] = NonEmptyList.one(new HttpRequestEncoder)

      override def decoders: NonEmptyList[ChannelHandler] =
        NonEmptyList.of(new HttpResponseDecoder(), new HttpObjectAggregator(Int.MaxValue))
    }

  implicit val defaultWsCodec: Codec[WebSocketFrame] =
    webSocketEncoders(withCompression = false)

  def webSocketEncoders(withCompression: Boolean): Codec[WebSocketFrame] =
    new NettyEncoder[WebSocketFrame] with NettyDecoder[WebSocketFrame] {
      override def encoders: NonEmptyList[ChannelHandler] = {
        val xs = NonEmptyList.one(new WebSocket13FrameEncoder(true))
        if (withCompression)
          xs.prepend(wsCompressionHandshaker.newExtensionEncoder())
        else
          xs
      }

      override def decoders: NonEmptyList[ChannelHandler] = {
        val xs = NonEmptyList.one(new WebSocket13FrameDecoder(false, withCompression, 65536))
        if (withCompression)
          xs.append(wsCompressionHandshaker.newExtensionDecoder())
        else
          xs
      }
    }

  @SuppressWarnings(
    Array(
      "scalafix:DisableSyntax.contravariant"
    )
  )
  type Codec[-A] = NettyEncoder[A] with NettyDecoder[A]

  /**
    * A type class to describe how to encode a type `A` into a byte buffer.
    *
    * @tparam A
    *   The contravariance allows one encoder to be used for all subtype payloads
    */
  @SuppressWarnings(
    Array(
      "scalafix:DisableSyntax.contravariant"
    )
  )
  trait NettyEncoder[-A] {
    def encoders: NonEmptyList[ChannelHandler]
  }

  /**
    * A type class to describe how decode an outgoing byte buffer into a message of type `A`
    *
    * @tparam A
    *   The contravariance allows one decoder to be used for all subtype payloads
    */
  @SuppressWarnings(
    Array(
      "scalafix:DisableSyntax.contravariant"
    )
  )
  trait NettyDecoder[-A] {
    def decoders: NonEmptyList[ChannelHandler]
  }
}
