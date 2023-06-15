package cats.netty
package http

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.{Dispatcher, Queue, Supervisor}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.{EmptyHttpHeaders, FullHttpResponse, HttpRequest, HttpResponseStatus}

import cats.netty.testkit.EmbeddedChannelF
import cats.netty.testkit.NettyCodec.{NettyDecoder, NettyEncoder}

class HttpClientConnection private (
  val channel: EmbeddedChannelF[IO],
  val statues: Queue[IO, HttpResponseStatus]
) {
  def sendRequest[A <: HttpRequest](reqs: A*): IO[Unit] = channel.writeInboundEncoded(reqs: _*)

  def readResponse[A](f: FullHttpResponse => A): IO[A] = channel.readOutboundDecoded(f)

  def sendWebSocketFrame[A <: WebSocketFrame: NettyEncoder](reqs: A*): IO[Unit] =
    channel.writeInboundEncoded(reqs: _*)

  def readWebSocketFrame[A, B <: WebSocketFrame: NettyDecoder](f: B => A): IO[A] =
    channel.readOutboundDecoded(f)

  def isActive: Boolean = channel.isActive

  def close: IO[Unit] = channel.close

  def finishAndReleaseAll: IO[Boolean] = channel.finishAndReleaseAll

  def underlying: EmbeddedChannel = channel.underlying
}

object HttpClientConnection {

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments"
    )
  )
  def apply(
    controllers: List[HttpController[IO]],
    requestTimeoutPeriod: FiniteDuration = 1.minute,
    maxHttpContentLength: Int Refined NonNegative = 1,
    maxInitialLineLength: Int Refined NonNegative = 100,
    maxHeaderSize: Int Refined NonNegative = 1000
  )(implicit dispatcher: Dispatcher[IO], supervisor: Supervisor[IO]): IO[HttpClientConnection] = {
    for {
      statuses <- Queue.unbounded[IO, HttpResponseStatus]

      conn <- IO {
        new HttpClientConnection(
          EmbeddedChannelF(
            dispatcher,
            HttpServer.onNewConnection[IO, EmbeddedChannel](
              HttpServer.Config[IO](
                serverName = "test-http-server",
                requestTimeoutPeriod,
                blockHttpPipelinedRequests = true,
                parsing = HttpServer.Config.Parsing(
                  maxHttpContentLength,
                  maxInitialLineLength,
                  maxHeaderSize
                ),
                extraHeaders = EmptyHttpHeaders.INSTANCE,
                handleResponseStatus = statuses.offer,
                redactHeader = _ => false
              ),
              controllers,
              supervisor,
              dispatcher,
              onNewHttpConnection = _ => IO.unit
            )(_)
          ),
          statuses
        )
      }

      _ <- conn.channel.waitForReadsToProcess
    } yield conn
  }

}
