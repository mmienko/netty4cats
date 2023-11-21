package cats.netty
package channel

import cats.effect.Sync
import cats.effect.std.Dispatcher
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
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.Var",
    "DisableSyntax.var",
    "org.wartremover.warts.Null",
    "DisableSyntax.null",
    "DisableSyntax.valInAbstract"
  )
)
// logger injectable for testing purposes
abstract class NettyToCatsEffectRuntimeHandler[F[_]: Sync, I](
  handlerLogger: Logger = NettyToCatsEffectRuntimeHandler.DefaultLogger
) extends ChannelDuplexHandler {

  /*
  TypeParameterMatcher is a Netty trick (used for example in SimpleInboundHandler) to check the type of read messages
  for generic handlers. However, there is limitations on generic types and restrictions on extensibility of this
  handler. Regarding the former, types that explicitly extend AnyVal (like Scala's Int) are unsafe because the matcher
  will match all Objects. Regarding the latter, subclasses of this handler should MUST specify a concrete type for the
  inbound type, I. The TypeParameterMatcher isn't able to resolve type variables.
  Both these limitations are fine since real-life subclasses deal with ByteBuf based classes or POJO's. If this
  limitation is a blocker, then custom TypeParameterMatcher can be made (though non trivial).
   */
  private val inboundTypeMatcher: TypeParameterMatcher =
    TypeParameterMatcher.find(this, classOf[NettyToCatsEffectRuntimeHandler[F, _]], "I")

  /*
  A sequential dispatcher to execute Netty events on the Cats-Effect runtime in the same order as this handler saw them.
  There exists only one per channel.
   */
  private var inboundDispatcher: Dispatcher[F] = null // no boxing

  protected def channelReadF(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit]

  protected def userEventTriggeredF(evt: AnyRef)(implicit ctx: ChannelHandlerContext): F[Unit]

  protected def exceptionCaughtF(cause: Throwable)(implicit ctx: ChannelHandlerContext): F[Unit]

  protected def channelWritabilityChangedF(isWriteable: Boolean)(implicit
    ctx: ChannelHandlerContext
  ): F[Unit]

  protected def channelInactiveF(implicit ctx: ChannelHandlerContext): F[Unit]

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
        channelReadF(msg.asInstanceOf[I])(ctx),
        errorLogMsg = s"channelRead with ${msg.asInstanceOf[I].getClass.getName}"
      )
    else
      ValueDiscard[ChannelHandlerContext](ctx.fireChannelRead(msg))

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
    dispatch(
      userEventTriggeredF(evt)(ctx),
      errorLogMsg = s"userEventTriggered with ${evt.getClass.getName}"
    )

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    dispatch(
      exceptionCaughtF(cause)(ctx),
      errorLogMsg = "exceptionCaught"
    )

  override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit =
    dispatch(
      channelWritabilityChangedF(ctx.channel().isWritable)(ctx),
      errorLogMsg = "channelWritabilityChanged"
    )

  /*
  Dispatch to handlerF and signal that inbound dispatcher can be be closed
   */
  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    dispatch(
      channelInactiveF(ctx),
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
    handlerLogger.error(s"${this.getClass.getSimpleName}: $msg", exception)

}

object NettyToCatsEffectRuntimeHandler {
  private[channel] val DefaultLogger = LoggerFactory.getLogger(getClass)

}
