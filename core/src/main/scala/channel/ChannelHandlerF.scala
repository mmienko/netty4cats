package cats.netty
package channel

import cats.Applicative
import cats.effect.{Async, Deferred}
import cats.syntax.all._
import io.netty.channel.ChannelHandlerContext
import org.slf4j.Logger

import cats.netty.Utils.ValueDiscard
import cats.netty.channel.NettyToCatsEffectRuntimeHandler.DefaultLogger

trait ChannelHandlerF[F[_], I] {

  def channelRead(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit]

  def userEventTriggered(evt: AnyRef)(implicit ctx: ChannelHandlerContext): F[Unit]

  def exceptionCaught(cause: Throwable)(implicit ctx: ChannelHandlerContext): F[Unit]

  def channelWritabilityChanged(isWriteable: Boolean)(implicit ctx: ChannelHandlerContext): F[Unit]

  def channelInactive(implicit ctx: ChannelHandlerContext): F[Unit]
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments"
  )
)
object ChannelHandlerF {

  /**
    * Should be used for testing as a quick way to create handlers.
    *
    * @param onRead
    *   channelRead
    * @tparam I
    * @return
    */
  def onlyChannelRead[F[_], I](
    onRead: (I, ChannelHandlerContext) => F[Unit]
  )(implicit F: Applicative[F]): ChannelHandlerF[F, I] =
    new ChannelHandlerF[F, I] {
      override def channelRead(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit] =
        onRead(msg, ctx)

      override def userEventTriggered(
        evt: AnyRef
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[AnyRef](evt)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def exceptionCaught(
        cause: Throwable
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[Throwable](cause)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def channelWritabilityChanged(
        isWriteable: Boolean
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[Boolean](isWriteable)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def channelInactive(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }
    }

  def asNetty[F[_]: Async, I](
    handler: ChannelHandlerF[F, I],
    logger: Logger = DefaultLogger // injectable for testing purposes
  ): F[NettyToCatsEffectRuntimeHandler[F, I]] =
    Deferred[F, Unit].map(
      new NettyToCatsEffectRuntimeHandler(
        handler,
        _,
        logger
      ) {}
    )

}
