package cats.netty
package http

import cats.effect.std.Dispatcher
import io.netty.channel.{ChannelConfig, ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.http._
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory

import cats.netty.Utils.ValueDiscard

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Var",
    "DisableSyntax.var"
  )
)
class HttpPipeliningBlockerHandler[F[_]](
  notifyStatus: HttpResponseStatus => F[Unit],
  dispatcher: Dispatcher[F]
) extends ChannelDuplexHandler {

  private var clientAttemptingHttpPipelining = false
  private var isHttpRequestInFlight = false

  private val logger = LoggerFactory.getLogger(getClass)

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case request: FullHttpRequest =>
        if (!isHttpRequestInFlight) {
          isHttpRequestInFlight = true
          super.channelRead(ctx, msg)
        } else {
          /*
          Stop reading since we're going to close channel
           */
          ValueDiscard[ChannelConfig](ctx.channel().config().setAutoRead(false))
          ValueDiscard[Boolean](ReferenceCountUtil.release(request))
          clientAttemptingHttpPipelining = true
        }

      case _ =>
        super.channelRead(ctx, msg)
    }

  override def write(
    ctx: ChannelHandlerContext,
    msg: Any,
    promise: ChannelPromise
  ): Unit = {
    msg match {
      case _: FullHttpResponse =>
        super.write(ctx, msg, promise)
        isHttpRequestInFlight = false
        if (clientAttemptingHttpPipelining) {
          // TODO: at some point, this can be made more robust to check if 1st response was sent.
          //  Perhaps channel is closed. In which case, don't need to send.
          val response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.TOO_MANY_REQUESTS
          )
          HttpUtil.setKeepAlive(response, false)
          HttpUtil.setContentLength(response, 0)
          val _ = ctx.writeAndFlush(response)
          try {
            dispatcher.unsafeRunAndForget(notifyStatus(HttpResponseStatus.TOO_MANY_REQUESTS))
          } catch {
            case e: IllegalStateException =>
              logger.error("Failed to notify of HTTP status 429", e)
          }
        }

      case _ =>
        super.write(ctx, msg, promise)
    }
  }
}

object HttpPipeliningBlockerHandler {
  val name: String = "HttpPipeliningBlockerHandler"
}
