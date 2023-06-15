package cats.netty
package http.websocket

import cats.effect.Sync
import cats.syntax.all._
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.{ChannelInputShutdownEvent, ChannelOutputShutdownEvent}
import io.netty.channel.unix.Errors.NativeIoException
import io.netty.handler.codec.http.websocketx._
import org.slf4j.{Logger, LoggerFactory, MDC}

import cats.netty.channel.ChannelHandlerF
import cats.netty.http.Logging

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.Null",
    "DisableSyntax.var",
    "DisableSyntax.null"
  )
)
class WebSocketHandler[F[_]](
  serverName: String,
  webSocket: NettyWebSocket[F],
  wsListener: WebSocketListener[F]
)(implicit F: Sync[F])
    extends ChannelHandlerF[F, WebSocketFrame] {

  private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  override def channelRead(
    frame: WebSocketFrame
  )(implicit ctx: ChannelHandlerContext): F[Unit] =
    frame match {
      case close: CloseWebSocketFrame =>
        webSocket.finishClientInitiatedClose(close)

      case _ =>
        wsListener
          .handleFrame(frame)
          .handleErrorWith { error =>
            F.bracket(F.unit)(_ =>
              F.delay {
                putServerNameInLogs()
                MDC.put("isFinal", frame.isFinalFragment.toString)
                MDC.put("size", frame.content().readableBytes().toString)
                logger.error("Failed to handle WS frame", error)
                MDC.clear()
              }
            )(_ => F.delay(frame.release()).attempt.void)
          }
    }

  override def channelWritabilityChanged(isWriteable: Boolean)(implicit
    ctx: ChannelHandlerContext
  ): F[Unit] =
    wsListener.handleWritabilityChange()

  override def userEventTriggered(
    evt: AnyRef
  )(implicit ctx: ChannelHandlerContext): F[Unit] = {
    evt match {
      /*
      Netty has extra notifications during some abnormal shutdowns, e.g. socket is closed during a flush. Such
      abnormal closure should be reported as such by this class.
       */
      case _: ChannelInputShutdownEvent | _: ChannelOutputShutdownEvent =>
        F.unit

      case _ =>
        wsListener
          .handlePipelineEvent(evt)
          .handleErrorWith { error =>
            F.delay {
              putServerNameInLogs()
              Logging.MDC.putPipelineEvent(evt.toString)
              logger.error("Failed to handle Pipeline Event", error)
              MDC.clear()
            }
          }
    }
  }

  private def putServerNameInLogs() = {
    Logging.MDC.putServerName(serverName)
  }

  override def exceptionCaught(
    cause: Throwable
  )(implicit ctx: ChannelHandlerContext): F[Unit] =
    cause match {
      case error: CorruptedWebSocketFrameException =>
        for {
          closeStatus <- error.closeStatus().pure[F]

          // Logic is adapted from Netty's WebSocket08FrameDecoder, namely use Exception's message as close-reason.
          // For reporting purposes, we also want to use the same reason that we send to client.
          reason = Option(error.getMessage).getOrElse(closeStatus.reasonText())

          _ <- webSocket.closeByServer(closeStatus.code(), reason)
        } yield ()

      /*
        Epoll related error when attempting socket I/O operations.

        For example, in prod we see "readAddress(..) failed: Connection reset by peer" which happens when the peer, in
        this case the client, sent a RST packet to forcefully close connection. Perhaps the program crashed or router
        was disconnected. Instead of letting the connection hang indefinitely, it did the next best thing and
        notify us, the server, that it's finished with the connection. Normally, there's a two-way close handshake.

        In Netty, these error originate from io.netty.channel.unix.FileDescriptor (e.g. readAddress) which is used in
        io.netty.channel.epoll.AbstractEpollChannel (e.g. doReadBytes). If it throws the NativeIoException, Netty will
        first send exception through channel, then close the connection. This can be seen in
        io.netty.channel.epoll.AbstractEpollStreamChannel.handleReadException.
       */
      case error: NativeIoException =>
        for {
          status <- F.delay(
            new WebSocketCloseStatus(
              WebSocketCloseStatus.ABNORMAL_CLOSURE.code(),
              error.toString,
              /*
              Turn off status code validation as Netty checks that status codes comply with RFC-6455. This is created
              reporting purposes only, not to send to peer. Therefore it's safe to turn off validation.
               */
              false
            )
          )

          _ <- webSocket.setCloseReason(status, CloseInitiator.Client)
        } yield ()

      case _ =>
        wsListener.handleException(cause).handleErrorWith { error =>
          F.delay {
            putServerNameInLogs()
            MDC.put("originalCause", Logging.showError.show(cause))
            logger.error("Failed to handle error", error)
            MDC.clear()
          }
        }
    }

  override def channelInactive(implicit context: ChannelHandlerContext): F[Unit] =
    for {
      reason <- webSocket.getCloseReason.map(
        _.getOrElse(
          (WebSocketCloseStatus.ABNORMAL_CLOSURE, CloseInitiator.Client)
        )
      )
      (status, initiator) = reason

      _ <- wsListener
        .handleClosed(status, initiator)
        .handleErrorWith { error =>
          F.delay {
            putServerNameInLogs()
            MDC.put("status", status.toString)
            MDC.put("initiator", initiator.show)
            logger.error("Failed to handle close", error)
            MDC.clear()
          }
        }
    } yield ()
}

object WebSocketHandler {

  val NettyHandlerName: String = "WebSocketHandler"

  def apply[F[_]: Sync](
    serverName: String,
    webSocket: NettyWebSocket[F],
    wsListener: WebSocketListener[F]
  ): WebSocketHandler[F] =
    new WebSocketHandler(
      serverName,
      webSocket,
      wsListener
    )

  final case class Config(
    forceCloseTimeoutMillis: Long,
    closeStatus: WebSocketCloseStatus
  )
}
