package cats.netty
package http.websocket

import java.util

import scala.collection.mutable

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.websocketx.{WebSocketCloseStatus, WebSocketFrame}
import io.netty.handler.codec.http.{HttpHeaders, HttpResponseStatus}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Var",
    "DisableSyntax.var",
    "DisableSyntax.while"
  )
)
class MockWebSocketSession extends WebSocketSession[IO] {

  // Double option to discern between connected and non-connected states
  val subProtocol: Deferred[IO, Option[String]] = Deferred.unsafe[IO, Option[String]]
  val connectionFailed: Deferred[IO, Throwable] = Deferred.unsafe[IO, Throwable]
  val webSocket: Deferred[IO, WebSocket[IO]] = Deferred.unsafe[IO, WebSocket[IO]]
  val pipelineEvents: Deferred[IO, mutable.Queue[AnyRef]] =
    Deferred.unsafe[IO, mutable.Queue[AnyRef]]
  val exception: Deferred[IO, Throwable] = Deferred.unsafe[IO, Throwable]
  val closeReason: Deferred[IO, (WebSocketCloseStatus, CloseInitiator)] =
    Deferred.unsafe[IO, (WebSocketCloseStatus, CloseInitiator)]

  private val pipelineEventsQueue = mutable.Queue.empty[AnyRef]
  private val framesQueue: util.Queue[WebSocketFrame] =
    new util.ArrayDeque[WebSocketFrame](100)

  override def connected(
    subProtocol: Option[String],
    webSocket: WebSocket[IO],
    pipelineEventScheduler: PipelineEventScheduler[IO]
  )(implicit
    ctx: ChannelHandlerContext
  ): IO[Unit] =
    for {
      _ <- this.subProtocol.complete(subProtocol)
      _ <- this.webSocket.complete(webSocket)
    } yield ()

  override def failedToEstablish(
    cause: Throwable,
    httpHeaders: HttpHeaders,
    status: HttpResponseStatus
  ): IO[Unit] = this.connectionFailed.complete(cause).void

  override def handleFrame(frame: WebSocketFrame): IO[Unit] = IO { framesQueue.add(frame) }.void

  override def handleWritabilityChange(): IO[Unit] = IO.unit

  def getAllFrames: List[WebSocketFrame] = {
    var list = List.empty[WebSocketFrame]
    while (!framesQueue.isEmpty) {
      list = framesQueue.poll() :: list
    }
    list
  }

  def hasFrames: Boolean = !framesQueue.isEmpty

  override def handlePipelineEvent(evt: AnyRef): IO[Unit] =
    IO(pipelineEventsQueue.enqueue(evt)) *>
      pipelineEvents.complete(pipelineEventsQueue).void

  override def handleException(cause: Throwable): IO[Unit] = exception.complete(cause).void

  override def handleClosed(
    status: WebSocketCloseStatus,
    closeInitiator: CloseInitiator
  ): IO[Unit] = closeReason.complete((status, closeInitiator)).void
}

object MockWebSocketSession {

  def apply(): MockWebSocketSession =
    new MockWebSocketSession
}
