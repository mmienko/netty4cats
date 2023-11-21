package cats.netty
package http

import java.net.URI
import java.nio.channels.ClosedChannelException

import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import cats.effect.std.Supervisor
import cats.effect.{Async, Sync}
import cats.syntax.all._
import io.netty.channel.unix.Errors.NativeIoException
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext}
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.timeout.{IdleState, IdleStateEvent}
import org.slf4j.{Logger, LoggerFactory, MDC}

import cats.netty.NettySyntax._
import cats.netty.channel.NettyToCatsEffectRuntimeHandler
import cats.netty.http.HttpHandler._
import cats.netty.http.HttpServer.HttpReadTimeoutHandler
import cats.netty.http.websocket.{NettyWebSocket, WebSocketConfig, WebSocketHandler, WebSocketSession}

/**
  * Refines a Netty payload into an HTTP request and handles it using a fall-through stack of
  * controllers
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Null",
    "DisableSyntax.null"
  )
)
class HttpHandler[F[_]](
  configs: Configs[F],
  controllers: List[HttpController[F]],
  supervisor: Supervisor[F]
)(implicit F: Async[F])
    extends NettyToCatsEffectRuntimeHandler[F, FullHttpRequest] {

  private val logger: Logger =
    LoggerFactory.getLogger(this.getClass)

  override def channelReadF(
    request: FullHttpRequest
  )(implicit ctx: ChannelHandlerContext): F[Unit] =
    F.bracket(F.unit)(_ =>
      if (request.decoderResult().isSuccess)
        routeRequestToController(request, new URI(request.uri()))
      else
        handleHttpDecodingFailure(request)
    )(_ => F.delay(request.release()).void)
      .flatMap(handleResponseStatus(request.some))

  private def handleHttpDecodingFailure(
    request: FullHttpRequest
  )(implicit ctx: ChannelHandlerContext) =
    for {
      status <- F.delay(request.decoderResult().cause() match {
        case cause: TooLongFrameException if isTooLongHeaderException(cause) =>
          HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE

        case cause: TooLongFrameException if isTooLongInitialLineException(cause) =>
          HttpResponseStatus.REQUEST_URI_TOO_LONG

        case _ =>
          HttpResponseStatus.BAD_REQUEST
      })

      _ <- sendResponse(
        status,
        /*
        Always close connection as Netty will continue to discard bytes until connection is closed.
        For example, if request is garbled or HTTP header is too large, then we will send a response, but Netty's
        HttpObjectDecoder enters a permanent final state that will discord all incoming bytes. So if a new request
        comes over the same connection, it will NOT hit any E@E logic. The client will never get a response because
        bytes are ignored. Then client (if properly configured) will timeout waiting for a response.
         */
        keepAlive = false,
        configs.extraHeaders
      )

      // Netty will set uri to "/bad-request" if initial HTTP line cannot be decoded
      _ <- logWarn(request, status, "Failed to decode HTTP request")(request.decoderResult().cause)
    } yield status

  override def userEventTriggeredF(
    evt: AnyRef
  )(implicit ctx: ChannelHandlerContext): F[Unit] = evt match {
    case idleStateEvent: IdleStateEvent if idleStateEvent.state() == IdleState.READER_IDLE =>
      val status = HttpResponseStatus.REQUEST_TIMEOUT
      sendResponse(status, keepAlive = false, configs.extraHeaders)
        .flatMap(_ => handleResponseStatus(request = None)(status))

    case _ =>
      F.delay {
        putServerNameInLogs()
        Logging.MDC.putPipelineEvent(evt.toString)
        logger.error("Unknown channel event")
        MDC.clear()
      } *> send500AndClose()
  }

  override def exceptionCaughtF(
    cause: Throwable
  )(implicit ctx: ChannelHandlerContext): F[Unit] = {
    cause match {
      case error: NativeIoException if error.getMessage.contains(ClientTcpForceClosedMessage) =>
        /*
        After a patch for longer lived push side connections on event-edge (keep alive), we started to see an increase
        of "peer reset connection" events from Envelop-Delivery (Akka HTTP client). Overall volume of these events is
        very low. However, they were being logged as ERROR, throwing off metrics. While this isn't clean client side
        behavior, there isn't much for E@E. Could possibly collect metrics/log for reporting purposes, but ultimately
        action must be taken on the client side.
         */
        F.unit

      case _ =>
        F.delay {
          putServerNameInLogs()
          logger.error("Unknown channel exception", cause)
          MDC.clear()
        } *>
          send500AndClose()
    }
  }

  override def channelWritabilityChangedF(isWriteable: Boolean)(implicit
    ctx: ChannelHandlerContext
  ): F[Unit] = F.unit

  override def channelInactiveF(implicit ctx: ChannelHandlerContext): F[Unit] = F.unit

  private def routeRequestToController(
    request: FullHttpRequest,
    uri: URI
  )(implicit ctx: ChannelHandlerContext) =
    F.delay(controllers.find(_.routingLogic.apply(uri, request)))
      .flatMap {
        case None =>
          buildResponse(HttpResponseStatus.NOT_FOUND, keepAlive = true, configs.extraHeaders)
            .map(HttpController.Response[F])
            .widen[HttpController.Result[F]]

        case Some(controller) =>
          controller
            .handle(request)
            .handleErrorWith { cause =>
              `500`(keepAlive = true)
                .map(HttpController.Response[F])
                .flatTap(resp =>
                  F.delay {
                    putRequestAndResponseInLog(request, resp.value.status())
                    MDC.put("controller", controller.getClass.getSimpleName)
                    logger.error("HTTP controller failed", cause)
                    MDC.clear()
                  }
                )
                .widen[HttpController.Result[F]]
            }
      }
      .flatMap {
        case HttpController.Response(response) =>
          ctx
            .writeAndFlushF(response)
            /*
            Ignore future, via void, so that sending a HTTP response has fire-and-forget semantics. Business logic
            won't be notified which is sufficient and is in line with most other HTTP frameworks. The one case we might
            care if a response isn't sent, is connection-pairing. Business logic might want to clean up resources if
            response isn't send at all (channel is closed before response is flushed). Namely, delete pairing info
            from persistence. This point can be revisited
             */
            .as(response.status())

        case HttpController.SwitchToWebSocket(
              config,
              webSocketSession,
              subProtocol,
              extensions,
              response
            ) =>
          (for {
            /*
            Until backpressure is supported, cautiously stop reads until CE runtime processes websocket connection,
            in the unlikely event that a client sends WS frames before receiving the handshake response.
             */
            _ <- ctx.setAutoRead(false) // config is safe to mutate

            /*
             Cleanup pipeline. However, any pipeline mutation is concurrent to Netty's pipeline mutation, which
             occurs in the event of channel closure (this is an edge case where client sends WebSocket request
             and immediately closes)
             */
            _ <- (
              ctx.removeHandler(HttpServerKeepAliveHandlerClass) *>
                ctx.removeHandler(HttpObjectAggregatorClass) *>
                ctx.removeHandler(HttpReadTimeoutHandlerClass) *>
                ctx.removeHandler(HttpPipeliningBlockerHandler.name).attempt *>
                ctx.removeHandler(ctx.handler()).void
            ).recoverWith { case _: NoSuchElementException =>
              handleClosedWebSocket(request, webSocketSession, response)
            }

            // Add WebSocket Handlers
            _ <- ctx.addLast("wsencoder", new WebSocket13FrameEncoder(false))
            _ <- ctx.addLast("wsdecoder", new WebSocket13FrameDecoder(toDecoderConfig(config)))
            _ <- extensions.traverse_(ext =>
              ctx.addLast(ext.newExtensionDecoder()) *>
                ctx.addLast(ext.newExtensionEncoder())
            )
            _ <- ctx.addLast("Utf8FrameValidator", new Utf8FrameValidator(DoNotCloseOnUtf8Errors))
            wsHandlerName = WebSocketHandler.NettyHandlerName
            webSocket = new NettyWebSocket[F](
              config.closeTimeout,
              wsHandlerName,
              ctx.channel(),
              ctx.executor()
            )
            handler <- F.delay(WebSocketHandler(configs.serverName, webSocket, webSocketSession))
            _ <- ctx.addLast(wsHandlerName, handler)
            wsCtx <- ctx.getContext(handler)
            _ <- F.whenA(wsCtx == null)(handleClosedWebSocket(request, webSocketSession, response))

            // If message is sent, notify upper layer about start of WebSocket or error (like a closed connection)
            _ <- Utils
              .fromNetty[F]
              .apply(ctx.channelWriteAndFlush(response))
              .attempt
              .flatMap {
                case Left(e) =>
                  ctx.closeF *>
                    webSocketSession
                      .failedToEstablish(e, request.headers(), response.status())
                      .handleErrorWith(
                        logError(
                          request,
                          response.status(),
                          "Failed to notify of non-established WebSocket connection"
                        )
                      )

                case Right(_) =>
                  val s = new NettyPipelineEventScheduler[F](wsCtx)
                  (
                    ctx.removeHandler(HttpCodecClass) *>
                      webSocketSession.connected(subProtocol, webSocket, s)(wsCtx) *>
                      ctx.setAutoRead(true)
                  ).recoverWith {
                    case _: NoSuchElementException =>
                      handleClosedWebSocket(request, webSocketSession, response)

                    case e: Throwable =>
                      F.whenA(ctx.channel().isActive)(
                        webSocket.closeByServer(WebSocketCloseStatus.INTERNAL_SERVER_ERROR)
                      ) *>
                        logError(request, response.status(), "WebSocket Connect Failed")(e)
                  }
              }
          } yield response.status())
            .recoverWith { case WebSocketResponseNotSent =>
              response.status().pure[F] // upgrade was short circuited
            }
      }

  private def send500AndClose()(implicit ctx: ChannelHandlerContext): F[Unit] =
    for {
      response <- `500`(keepAlive = false)

      _ <- F.delay(ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE))

      _ <- handleResponseStatus(request = None)(response.status())
    } yield ()

  private def `500`(keepAlive: Boolean): F[DefaultFullHttpResponse] =
    buildResponse[F](
      HttpResponseStatus.INTERNAL_SERVER_ERROR,
      keepAlive,
      configs.extraHeaders
    )

  // This is invoked when HttpHandler was removed from pipeline b/c channel was closed, i.e. ctx.isRemoved is true
  private def handleClosedWebSocket(
    request: FullHttpRequest,
    webSocketSession: WebSocketSession[F],
    response: FullHttpResponse
  ) =
    webSocketSession
      .failedToEstablish(WebSocketResponseNotSent, request.headers(), response.status())
      .handleErrorWith(
        logError(
          request,
          response.status(),
          "Failed to notify of non-established WebSocket connection due to closure"
        )
      ) *> F.raiseError[Unit](WebSocketResponseNotSent)

  /*
  MDC is generic across logger different logger implementations as opposed to Structured Arguments which is specific
  to Logback. MDC is also safe as long as we always set, log, and clear inside a single F.
   */
  private def putServerNameInLogs() =
    Logging.MDC.putServerName(configs.serverName)

  private def putRequestAndResponseInLog(request: FullHttpRequest, status: HttpResponseStatus) = {
    putServerNameInLogs()
    MDC.put("uri", request.uri())
    MDC.put("status", status.code().toString)
    MDC.put("headers", redactedHeaders(request))
  }

  private def logError(request: FullHttpRequest, status: HttpResponseStatus, msg: String)(
    e: Throwable
  ) = F.delay {
    putRequestAndResponseInLog(request, status)
    logger.error(msg, e)
    MDC.clear()
  }

  private def logWarn(request: FullHttpRequest, status: HttpResponseStatus, msg: String)(
    e: Throwable
  ) = F.delay {
    putRequestAndResponseInLog(request, status)
    logger.warn(msg, e)
    MDC.clear()
  }

  private def redactedHeaders(request: FullHttpRequest) =
    request
      .headers()
      .iteratorAsString()
      .asScala
      .toList
      .map { e =>
        if (configs.redactHeader(e.getKey))
          s"${e.getKey}=<redacted>"
        else
          s"${e.getKey}=${e.getValue}"
      }
      .mkString(", ")

  private def handleResponseStatus(
    request: Option[FullHttpRequest]
  )(status: HttpResponseStatus): F[Unit] = supervisor
    .supervise( // Dont' block processing of future requests
      configs
        .handleResponseStatus(status)
        .handleErrorWith { e =>
          val msg = "Failed to process Post Response callback"
          request match {
            case Some(r) =>
              logWarn(r, status, msg)(e)

            case None =>
              F.delay {
                putServerNameInLogs()
                logger.warn(msg, e)
                MDC.clear()
              }
          }
        }
    )
    .void

}

object HttpHandler {

  /*
  Below string is literal string from unix error message. Message construction can be found at
  `io.netty.channel.unix.NativeIoException`. Prod logs show "readAddress(..) failed: Connection reset by peer".
  "Connection reset by peer" happens when peer, in this case the client, sent a RST packet to forcefully close
  connection. The peer didn't follow the friendly close protocol, but at least it didn't leave us hanging without
  any notification.
   */
  private val ClientTcpForceClosedMessage = "Connection reset by peer"

  private val HttpCodecClass = classOf[HttpServerCodec]
  private val HttpServerKeepAliveHandlerClass = classOf[HttpServerKeepAliveHandler]
  private val HttpObjectAggregatorClass = classOf[HttpObjectAggregator]
  private val HttpReadTimeoutHandlerClass = classOf[HttpReadTimeoutHandler]
  private val DoNotCloseOnUtf8Errors = false

  def apply[F[_]: Async](
    configs: Configs[F],
    controllers: List[HttpController[F]],
    supervisor: Supervisor[F]
  ): HttpHandler[F] =
    new HttpHandler(
      configs,
      controllers,
      supervisor
    )

  private def isTooLongHeaderException(cause: TooLongFrameException) =
    cause.getMessage.contains("header")

  private def isTooLongInitialLineException(cause: TooLongFrameException) =
    cause.getMessage.contains("line")

  private def sendResponse[F[_]: Sync](
    status: HttpResponseStatus,
    keepAlive: Boolean,
    extraHeaders: HttpHeaders
  )(implicit ctx: ChannelHandlerContext) =
    for {
      response <- buildResponse(status, keepAlive, extraHeaders)

      _ <- Sync[F].delay(ctx.writeAndFlush(response))
    } yield response.status()

  private def buildResponse[F[_]: Sync](
    status: HttpResponseStatus,
    keepAlive: Boolean,
    extraHeaders: HttpHeaders
  ) =
    for {
      response <- Sync[F].delay(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status))
      _ <- Sync[F].delay(response.headers().add(extraHeaders))
      _ <- Sync[F].delay(HttpUtil.setContentLength(response, 0))
      _ <- Sync[F].delay(HttpUtil.setKeepAlive(response, keepAlive))
    } yield response

  private def toDecoderConfig(config: WebSocketConfig) =
    WebSocketDecoderConfig
      .newBuilder()
      .maxFramePayloadLength(config.maxFramePayloadLength)

      // Server's must set this to true
      .expectMaskedFrames(true)

      // Allows to loosen the masking requirement on received frames. Should NOT be set.
      .allowMaskMismatch(false)
      .allowExtensions(config.allowExtensions)
      /*
      Have our custom handler manage the close. There's also drawbacks to testing protocol failures with
      EmbeddedChannel; WebSocket08FrameDecoder sends the Close frame, then closes channel, and lastly fires the
      CorruptedWebSocketFrameException. On a real event loop, the send then close probably won't happen after
      exception is throw. In unit tests, this closure results in the pipeline being cleaned up, so our custom
      WebSocketHandler never gets the exception because it's no longer in the pipeline.
       */
      .closeOnProtocolViolation(false)
      // We install a custom one; the Decoder handler doesn't act on this value anyway.
      .withUTF8Validator(false)
      .build

  private[http] final case class Configs[F[_]](
    serverName: String,
    extraHeaders: HttpHeaders,
    redactHeader: String => Boolean,
    handleResponseStatus: HttpResponseStatus => F[Unit]
  )

  private object WebSocketResponseNotSent extends ClosedChannelException with NoStackTrace
}
