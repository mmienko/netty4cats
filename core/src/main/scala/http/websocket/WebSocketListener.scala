package cats.netty
package http.websocket

import io.netty.handler.codec.http.websocketx.{WebSocketCloseStatus, WebSocketFrame}

trait WebSocketListener[F[_]] {

  def handleFrame(frame: WebSocketFrame): F[Unit]

  def handlePipelineEvent(evt: AnyRef): F[Unit]

  def handleException(cause: Throwable): F[Unit]

  def handleWritabilityChange(): F[Unit]

  def handleClosed(
    status: WebSocketCloseStatus,
    closeInitiator: CloseInitiator
  ): F[Unit]
}
