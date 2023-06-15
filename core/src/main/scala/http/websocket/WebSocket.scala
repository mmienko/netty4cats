package cats.netty
package http.websocket

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.FiniteDuration

import cats.effect.Async
import cats.syntax.all._
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.EventExecutor

import cats.netty.NettySyntax._
import cats.netty.Utils.ValueDiscard
import cats.netty.http.websocket.NettyWebSocket._

trait WebSocket[F[_]] {
  def writeAndFlush(frame: BinaryWebSocketFrame): F[Unit]

  def writeAndFlush(frame: TextWebSocketFrame): F[Unit]

  def writeAndFlush(frame: PingWebSocketFrame): F[Unit]

  def writeAndFlush(frame: PongWebSocketFrame): F[Unit]

  // For backwards compatibility, will be rename/refactor to only writeAndFlush, but with these semantics
  def writeAndFlushSync(frame: TextWebSocketFrame): F[Unit]

  // For backwards compatibility, will be rename/refactor to only writeAndFlush, but with these semantics
  def writeAndFlushSync(frame: BinaryWebSocketFrame): F[Unit]

  // For backwards compatibility, will be rename/refactor to only writeAndFlush, but with these semantics
  def writeAndFlushSync(frame: PingWebSocketFrame): F[Unit]

  // For backwards compatibility, will be rename/refactor to only writeAndFlush, but with these semantics
  def writeAndFlushSync(frame: PongWebSocketFrame): F[Unit]

  def updateReadTimeout(timeout: FiniteDuration): F[Unit]

  def setAutoRead(autoRead: Boolean): F[Unit]

  def isWritable: F[Boolean]

  def close(status: WebSocketCloseStatus): F[Unit]

  def isClosed: F[Boolean]
}

//Controls what frames and how they should be sent.
@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Any"
  )
)
class NettyWebSocket[F[_]](
  closeTimeout: FiniteDuration,
  webSocketHandlerName: String,
  channel: Channel,
  executor: EventExecutor
)(implicit F: Async[F])
    extends WebSocket[F] {

  // Not a Ref b/c this interfaces w/ Netty
  private val closeReason =
    new AtomicReference[Option[(WebSocketCloseStatus, CloseInitiator)]](None)

  override def writeAndFlush(frame: BinaryWebSocketFrame): F[Unit] =
    sendAndForgetF(frame)

  override def writeAndFlush(frame: TextWebSocketFrame): F[Unit] =
    sendAndForgetF(frame)

  override def writeAndFlush(frame: PingWebSocketFrame): F[Unit] =
    sendAndForgetF(frame)

  override def writeAndFlush(frame: PongWebSocketFrame): F[Unit] =
    sendAndForgetF(frame)

  override def writeAndFlushSync(frame: TextWebSocketFrame): F[Unit] =
    sendF(frame)

  override def writeAndFlushSync(frame: BinaryWebSocketFrame): F[Unit] =
    sendF(frame)

  override def writeAndFlushSync(frame: PingWebSocketFrame): F[Unit] =
    sendF(frame)

  override def writeAndFlushSync(frame: PongWebSocketFrame): F[Unit] =
    sendF(frame)

  override def updateReadTimeout(timeout: FiniteDuration): F[Unit] = for {
    handler <- F.delay(
      new IdleStateHandler(timeout.length, NoWriteTimeout, NoAllIdleTimeout, timeout.unit)
    )
    p = channel.pipeline()
    // IdleStateHandler destroys itself (and it's timeouts) upon removal from pipeline
    _ <- F
      .delay(Option(p.get(WebSocketReadTimeoutHandler)))
      .flatMap {
        case Some(oldHandler) =>
          F.delay(p.replace(oldHandler, WebSocketReadTimeoutHandler, handler))

        case None =>
          F.delay(p.addBefore(webSocketHandlerName, WebSocketReadTimeoutHandler, handler))
      }
      .void
      .recover { case _: NoSuchElementException => }
  } yield ()

  override def setAutoRead(autoRead: Boolean): F[Unit] =
    channel.setAutoRead(autoRead)

  override def isWritable: F[Boolean] =
    F.delay(channel.isWritable)

  override def isClosed: F[Boolean] = F.delay(!channel.isActive)

  override def close(status: WebSocketCloseStatus): F[Unit] =
    close(new CloseWebSocketFrame(status), CloseInitiator.Application)

  private[websocket] def closeByServer(status: Int, reason: String): F[Unit] =
    close(new CloseWebSocketFrame(status, reason), CloseInitiator.Server)

  private[netty] def closeByServer(status: WebSocketCloseStatus): F[Unit] =
    close(new CloseWebSocketFrame(status), CloseInitiator.Server)

  private[websocket] def finishClientInitiatedClose(frame: CloseWebSocketFrame): F[Unit] =
    close(frame, CloseInitiator.Client)

  private def close(frame: CloseWebSocketFrame, initiator: CloseInitiator): F[Unit] =
    for {
      /*
      First close frame sent is the close reason for the connection since that's what client receives.
      Frame properties can only be read before writeAndFlush since they are part of ByteBuffer which gets
      released/cleared after Netty sends the frame.
       */
      // TODO: use mutual exclusion here instead of in application code
      _ <- F.delay {
        closeReason.compareAndSet(
          None,
          (new WebSocketCloseStatus(frame.statusCode(), frame.reasonText(), false), initiator).some
        )
      }

      closeFrameSent <- channel.writeAndFlushF(frame)

      /*
      Frame not sent b/c of:
        1) client backpressure
        2) slow/overloaded event-loop, thus write not processed
      Could likely disambiguate by looking at channel outbound buffer
       */
      timeoutTask <- F.delay(
        executor
          .schedule(
            new Runnable {

              override def run(): Unit =
                if (closeFrameSent.cancel(false)) {
                  // Override since close took too long to send
                  closeReason.set(
                    (
                      WebSocketCloseStatus.ABNORMAL_CLOSURE,
                      CloseInitiator.Server
                    ).some
                  )
                  ValueDiscard[ChannelFuture](channel.close())
                  // Netty will release Frame upon cancellation
                }
            },
            closeTimeout.length,
            closeTimeout.unit
          )
      )

      _ <- F.delay(
        closeFrameSent
          .addListener((_: ChannelFuture) => {
            /*
            If timeout task cancels the future, this listener is still executed and isCancelled = true; the `if` guard
            keeps logic mutually exclusive. No cancellation means close frame was sent, so cancel timeout task and
            close channel.
             */
            if (!closeFrameSent.isCancelled) {
              ValueDiscard[Boolean](timeoutTask.cancel(false))
              ValueDiscard[ChannelFuture](channel.close())
            }
          })
      )
    } yield ()

  private[websocket] def getCloseReason: F[
    Option[(WebSocketCloseStatus, CloseInitiator)]
  ] = F.delay(closeReason.get())

  private[websocket] def setCloseReason(
    status: WebSocketCloseStatus,
    initiator: CloseInitiator
  ): F[Unit] = F.delay {
    closeReason.set((status, initiator).some)
  }

  private def sendAndForget(x: WebSocketFrame): ChannelFuture =
    channel.writeAndFlush(x)

  private def sendAndForgetF(x: WebSocketFrame): F[Unit] =
    getCloseReason.flatMap {
      case Some(_) =>
        F.delay(ReferenceCountUtil.release(x)).void

      case None =>
        F.delay(sendAndForget(x)).void
    }

  private def sendF(x: WebSocketFrame): F[Unit] = {
    getCloseReason.flatMap {
      case Some(_) =>
        F.delay(ReferenceCountUtil.release(x)) *>
          F.raiseError(new ClosedChannelException)

      case None =>
        Utils.fromNetty[F](F.delay(sendAndForget(x))).void
    }
  }
}

object NettyWebSocket {
  private val NoWriteTimeout = 0L
  private val NoAllIdleTimeout = 0L
  private val WebSocketReadTimeoutHandler = "WebSocketReadTimeoutHandler"
}
