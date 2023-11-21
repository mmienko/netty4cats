package cats.netty
package channel

import cats.Applicative
import io.netty.channel.ChannelHandlerContext

import cats.netty.Utils.ValueDiscard

trait ChannelHandlerF[F[_], I] {

  def channelReadF(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit]

  def userEventTriggeredF(evt: AnyRef)(implicit ctx: ChannelHandlerContext): F[Unit]

  def exceptionCaughtF(cause: Throwable)(implicit ctx: ChannelHandlerContext): F[Unit]

  def channelWritabilityChangedF(isWriteable: Boolean)(implicit ctx: ChannelHandlerContext): F[Unit]

  def channelInactiveF(implicit ctx: ChannelHandlerContext): F[Unit]
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
      override def channelReadF(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit] =
        onRead(msg, ctx)

      override def userEventTriggeredF(
        evt: AnyRef
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[AnyRef](evt)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def exceptionCaughtF(
        cause: Throwable
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[Throwable](cause)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def channelWritabilityChangedF(
        isWriteable: Boolean
      )(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[Boolean](isWriteable)
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }

      override def channelInactiveF(implicit ctx: ChannelHandlerContext): F[Unit] = {
        ValueDiscard[ChannelHandlerContext](ctx)
        F.unit
      }
    }
}
