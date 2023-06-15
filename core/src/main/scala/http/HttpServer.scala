package cats.netty
package http

import scala.concurrent.duration._

import cats.effect.std.{Dispatcher, Supervisor}
import cats.effect.{Async, Resource}
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import io.netty.channel.Channel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.timeout.IdleStateHandler

import cats.netty.Server.{Host, Port}
import cats.netty.channel.ChannelOption
import cats.netty.channel.NettyToCatsChannelInitializer.{Handlers, PipelineMutation}

object HttpServer {

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments"
    )
  )
  def bind[F[_]: Async](
    resources: NettyServerResources,
    host: Host,
    port: Port,
    config: Config[F],
    controllers: List[HttpController[F]],
    onNewHttpConnection: SocketChannel => F[Unit],
    options: List[ChannelOption] = Nil
  ): Resource[F, Server[F]] = {
    for {
      // Only used in HttpPipeliningBlockerHandler to dispatch status notifications, will be removed once logic
      // between that handler and HttpHandler is merged.
      disp <- Dispatcher.parallel[F]
      supervisor <- Supervisor[F]
      server <- Server.bind[F, SocketChannel](
        resources,
        host,
        port,
        onNewConnection(config, controllers, supervisor, disp, onNewHttpConnection),
        options
      )
    } yield server
  }

  def onNewConnection[F[_]: Async, C <: Channel](
    config: Config[F],
    controllers: List[HttpController[F]],
    supervisor: Supervisor[F],
    dispatcher: Dispatcher[F],
    onNewHttpConnection: C => F[Unit]
  )(channel: C): F[Handlers[F]] =
    onNewHttpConnection(channel).as(
      Handlers[F](
        List[PipelineMutation](
          PipelineMutation.Add(
            new HttpServerCodec(
              config.parsing.maxInitialLineLength.value,
              config.parsing.maxHeaderSize.value,
              config.parsing.maxChunkSize
            )
          ),
          PipelineMutation.Add(
            new HttpServerKeepAliveHandler
          ),
          PipelineMutation.Add(
            new HttpObjectAggregator(
              config.parsing.maxHttpContentLength.value
            )
          ),
          PipelineMutation.Add(
            new HttpReadTimeoutHandler(config.requestTimeoutPeriod)
          )
        ).appendedAll(
          if (config.blockHttpPipelinedRequests)
            List(
              PipelineMutation.AddByName(
                HttpPipeliningBlockerHandler.name,
                new HttpPipeliningBlockerHandler[F](
                  config.handleResponseStatus,
                  dispatcher
                )
              )
            )
          else Nil
        ),
        HttpHandler[F](
          HttpHandler.Configs[F](
            config.serverName,
            config.extraHeaders,
            config.redactHeader,
            config.handleResponseStatus
          ),
          controllers,
          supervisor
        )
      )
    )

  /**
    * @param serverName
    *   name used in logs
    * @param requestTimeoutPeriod
    *   limit on how long connection can remain open w/o any requests
    * @param blockHttpPipelinedRequests
    *   optional flag to block HTTP Pipelining (sending multiple requests w/o waiting for responses)
    *   if client, proxy, ALB, doesn't provide this guarantee
    * @param parsing
    *   parsing configs
    * @param extraHeaders
    *   headers added to virtually all responses
    * @param handleResponseStatus
    *   async response status notification; should be a quick function like collecting metrics
    * @param redactHeader
    *   Marks value in logs with "&lt redacted &gt"
    */
  final case class Config[F[_]](
    serverName: String,
    requestTimeoutPeriod: FiniteDuration,
    blockHttpPipelinedRequests: Boolean,
    parsing: Config.Parsing,
    extraHeaders: HttpHeaders,
    handleResponseStatus: HttpResponseStatus => F[Unit],
    redactHeader: String => Boolean
  )

  object Config {

    /**
      * @param maxHttpContentLength
      *   - limit on body/entity size
      * @param maxInitialLineLength
      *   - limit on how long url can be, along with HTTP preamble, i.e. "GET HTTP 1.1 ..."
      * @param maxHeaderSize
      *   - limit on size of single header
      */
    final case class Parsing(
      maxHttpContentLength: Int Refined NonNegative,
      maxInitialLineLength: Int Refined NonNegative,
      maxHeaderSize: Int Refined NonNegative
    ) {
      def maxChunkSize: Int Refined NonNegative = Parsing.DefaultMaxChunkSize
    }

    object Parsing {

      private val DefaultMaxChunkSize: Int Refined NonNegative =
        8192 // Netty default

      val DefaultMaxHttpContentLength: Int Refined NonNegative =
        65536 // Netty default

      val DefaultMaxInitialLineLength: Int Refined NonNegative =
        4096 // Netty default

      val DefaultMaxHeaderSize: Int Refined NonNegative = 8192 // Netty default

      val default: Parsing = new Parsing(
        DefaultMaxHttpContentLength,
        DefaultMaxInitialLineLength,
        DefaultMaxHeaderSize
      )
    }

  }

  private[http] class HttpReadTimeoutHandler(requestTimeoutPeriod: FiniteDuration)
      extends IdleStateHandler(requestTimeoutPeriod.length, 0, 0, requestTimeoutPeriod.unit)
}
