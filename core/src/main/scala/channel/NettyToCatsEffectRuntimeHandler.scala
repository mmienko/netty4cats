package cats.netty
package channel

import cats.effect.std.Dispatcher
import cats.effect.Sync
import cats.syntax.all._
import io.netty.channel._
import io.netty.util.AttributeKey
import io.netty.util.internal.TypeParameterMatcher
import org.slf4j.{Logger, LoggerFactory}

import cats.netty.Utils.ValueDiscard

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.AsInstanceOf",
    "org.wartremover.warts.Var",
    "DisableSyntax.var",
    "org.wartremover.warts.Null",
    "DisableSyntax.null",
    "DisableSyntax.valInAbstract"
  )
)
abstract class NettyToCatsEffectRuntimeHandler[F[_]: Sync, I] private[channel] (
  channelHandler: ChannelHandlerF[F, I],
  logger: Logger // injectable for testing purposes
) extends ChannelDuplexHandler {

  /*
  These TypeParameterMatcher is a Netty trick used in SimpleInboundHandler, except we use for both inbound and
  outbound types.
   */
  private val inboundTypeMatcher: TypeParameterMatcher =
    TypeParameterMatcher.find(this, classOf[NettyToCatsEffectRuntimeHandler[F, _]], "I")

  /*
  A sequential dispatcher to execute Netty events on the Cats-Effect runtime in the same order as this handler saw them.
  There exists only one per channel.
   */
  private var inboundDispatcher: Dispatcher[F] = null // no boxing

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    inboundDispatcher = ctx
      .channel()
      .attr(AttributeKey.valueOf[Dispatcher[F]]("InboundDispatcher"))
      .get()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    /*
     * Backpressure not supported yet. To do so, read-future would be saved. Then on next read, inspect if future is
     * complete. If so, dispatch. Otherwise, queue read (up to some configurable threshold) then turn off autoread.
     * When read future completes, then turn autoread back on and pull (all reads) from queue. This process essentially
     * turns CE3 runtime backpressure into network backpressure.
     */
    if (inboundTypeMatcher.`match`(msg))
      dispatch(
        channelHandler.channelRead(msg.asInstanceOf[I])(ctx),
        errorLogMsg = s"channelRead with ${msg.asInstanceOf[I].getClass.getName}"
      )
    else
      ValueDiscard[ChannelHandlerContext](ctx.fireChannelRead(msg))

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
    dispatch(
      channelHandler.userEventTriggered(evt)(ctx),
      errorLogMsg = s"userEventTriggered with ${evt.getClass.getName}"
    )

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    dispatch(
      channelHandler.exceptionCaught(cause)(ctx),
      errorLogMsg = "exceptionCaught"
    )

  override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit =
    dispatch(
      channelHandler.channelWritabilityChanged(ctx.channel().isWritable)(ctx),
      errorLogMsg = "channelWritabilityChanged"
    )

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    dispatch(
      channelHandler.channelInactive(ctx),
      errorLogMsg = "channelInactive"
    )
  }

  private def dispatch(io: F[Unit], errorLogMsg: => String): Unit =
    try {
      inboundDispatcher.unsafeRunAndForget(
        io.handleErrorWith(exception => Sync[F].delay(logError(errorLogMsg, exception)))
      )
    } catch {
      case e: IllegalStateException =>
        logError(errorLogMsg, e)
    }

  private def logError(msg: => String, exception: Throwable) =
    logger.error(s"${channelHandler.getClass.getSimpleName}: $msg", exception)

}

object NettyToCatsEffectRuntimeHandler {
  private[channel] val DefaultLogger = LoggerFactory.getLogger(getClass)

}
