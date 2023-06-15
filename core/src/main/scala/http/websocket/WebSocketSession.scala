package cats.netty
package http.websocket

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.{HttpHeaders, HttpResponseStatus}

trait WebSocketSession[F[_]] extends WebSocketListener[F] {

  def connected(
    subProtocol: Option[String],
    webSocket: WebSocket[F],
    pipelineEventScheduler: PipelineEventScheduler[F]
  )(implicit
    ctx: ChannelHandlerContext
  ): F[Unit]

  def failedToEstablish(
    cause: Throwable,
    httpHeaders: HttpHeaders,
    status: HttpResponseStatus
  ): F[Unit]
}
